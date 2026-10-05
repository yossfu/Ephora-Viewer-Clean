package com.lumiyaviewer.lumiya.renderer.filament

import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.filamat.MaterialBuilder
import com.lumiyaviewer.lumiya.renderer.AlphaMode
import com.lumiyaviewer.lumiya.renderer.MaterialDesc
import com.lumiyaviewer.lumiya.renderer.TextureHandle

/**
 * The viewer's surface material.
 *
 * Filament is a generic renderer: it knows nothing about Second Life. This class
 * is the translation layer — it turns a [MaterialDesc] (base colour, texture,
 * per-face UV mapping, fullbright flag, Second Life material code) into a
 * Filament `MaterialInstance`.
 *
 * The material itself is compiled on the device, at runtime, by `filamat` (the
 * same compiler the desktop `matc` uses), so the shader below is ordinary
 * Filament material source and no build step is needed to change it. A material
 * is compiled per (blending mode, double sided) pair, lazily: the first frames
 * only ever need the opaque variants.
 *
 * Only the material function is passed to [MaterialBuilder.material]; every
 * other property (parameters, required vertex attributes, blending) is declared
 * through the builder, which is how Filament's runtime material builder works.
 */
class FilamentMaterials(
    private val engine: Engine,
    targetApi: MaterialBuilder.TargetApi = MaterialBuilder.TargetApi.OPENGL
) {

    private var targetApi: MaterialBuilder.TargetApi = targetApi

    /**
     * TEST 3A: la clave deriva de los requisitos reales del shader. Todo lo que
     * el fragment recibe por parámetro (baseColor/tinta, textura,
     * uvTransform/uvRotation/planarMode/planarBasisU/planarBasisV,
     * metallic/roughness del materialCode, emissive del fullbright) es uniforme
     * por instancia y NO cambia el programa compilado.
     * Lo único que el builder fija por variante es el peldano, blending (de
     * alphaMode) y doubleSided. Por eso la clave son esos tres campos — y al
     * ser `data class`, el HashMap por fin acierta en vez de compilar por
     * solicitud.
     */
    private val compiledTiers = HashMap<TieredKey, Material>()
    private var compilationFailure: String? = null

    /** Why the last [build] failed, if it did. */
    val lastError: String? get() = compilationFailure

    /**
     * TEST 2: instrumentación pasiva del camino de materiales. Solo contadores
     * y nanos acumulados; no cambia ninguna decisión (la caché sigue sin
     * tocarse). Responde dónde van los ~55 s: filamat, instancia o lógica.
     */
    var cacheHits = 0L
        private set
    var cacheMiss = 0L
        private set
    var buildAttempts = 0L
        private set
    var buildOk = 0L
        private set
    var filamatBuildNanos = 0L
        private set
    var materialBuildNanos = 0L
        private set
    var instanceCreates = 0L
        private set
    var instanceAllocNanos = 0L
        private set
    var paramApplies = 0L
        private set
    var paramApplyNanos = 0L
        private set
    var rebuilds = 0L
        private set
    var rebuildNanos = 0L
        private set
    /** Llamadas a materialFor por clave (alpha_ds/ss): qué se pide. */
    val keyCounts = HashMap<String, Long>()
    /**
     * Firmas por createInstance: alpha_ds/ss_tex_plano_fb_codeN_uv_tinta.
     * Solo booleanos (combinaciones finitas): dice si uvRotation, repeat,
     * tinta o BLEND generan instancias distintas, sin guardar ni un float.
     */
    val sigCounts = HashMap<String, Long>()
    /** Materiales compilados vivos en la caché (responde el punto 3). */
    val compiledCount: Int get() = compiledTiers.size + if (fallbackCompiled != null) 1 else 0

    /**
     * Compiles every material variant up front. Filament compiles the shader
     * variants lazily and that first use can stall the render thread for a
     * noticeable instant, so the scene layer calls this during start-up (and the
     * caller can show it as part of the loading screen).
     */
    fun warmUp() {
        for (alphaMode in AlphaMode.values()) {
            materialFor(MaterialDesc(baseColor = WHITE, alphaMode = alphaMode))
            materialFor(MaterialDesc(baseColor = WHITE, alphaMode = alphaMode, doubleSided = true))
        }
    }

    fun setTargetApi(api: MaterialBuilder.TargetApi) {
        if (api != targetApi) {
            destroy()
            targetApi = api
        }
    }

    /**
     * 2.27b (arranque robusto): material fallback minimo e independiente del
     * material completo de Second Life. UNLIT, opaco, una sola cara, solo
     * POSITION, sin uniforms, sin samplers, shader constante blanco. Existe
     * solo para que el arranque llegue a RENDERER_READY aunque el shader SL
     * completo no compile en el dispositivo; ningun cambio futuro en
     * TextureEntry, Mesh, fullbright, planar, materiales o alpha lo toca.
     * Si este minimo tampoco compila, el error real queda en
     * [fallbackLastError] y el llamador debe abortar el arranque (nunca
     * simular listo con null).
     */
    private var fallbackCompiled: Material? = null

    var fallbackLastError: String? = null
        private set

    fun createFallbackInstance(): MaterialInstance? {
        val existing = fallbackCompiled
        if (existing != null && engine.isValidMaterial(existing)) {
            return existing.createInstance()
        }
        val compiled = compileFallback() ?: return null
        fallbackCompiled = compiled
        return compiled.createInstance()
    }

    /**
     * DIAG-VIS (temporal, reversible): instancia del fallback magenta de
     * diagnostico. Mismo shader minimo que el fallback blanco pero en
     * magenta constante (1,0,1,1): cualquier cara que lo use se distingue a
     * simple vista de una cara con textura real. No toca el fallback blanco
     * ni el camino normal de materiales; se retira poniendo
     * [FilamentRenderer.setDiagFallbackEnabled](false) o borrando este
     * bloque y sus dos llamadas.
     */
    private var diagCompiled: Material? = null

    var diagFallbackLastError: String? = null
        private set
    fun createDiagFallbackInstance(): MaterialInstance? {
        val existing = diagCompiled
        if (existing != null && engine.isValidMaterial(existing)) {
            return existing.createInstance()
        }
        val compiled = compileDiagFallback() ?: return null
        diagCompiled = compiled
        return compiled.createInstance()
    }

    private fun compileDiagFallback(): Material? {
        return try {
            MaterialBuilder.init()
            val builder = MaterialBuilder()
                .name("ephora_diag_fallback_magenta")
                .platform(MaterialBuilder.Platform.MOBILE)
                .targetApi(targetApi)
                .optimization(MaterialBuilder.Optimization.NONE)
                .shading(MaterialBuilder.Shading.UNLIT)
                .materialDomain(MaterialBuilder.MaterialDomain.SURFACE)
                .vertexDomain(MaterialBuilder.VertexDomain.OBJECT)
                .doubleSided(false)
                .blending(MaterialBuilder.BlendingMode.OPAQUE)
                .require(MaterialBuilder.VertexAttribute.POSITION)
                .material(DIAG_FALLBACK_FRAGMENT_SOURCE)
            val package_ = builder.build()
            if (!package_.isValid()) {
                diagFallbackLastError = "filamat rechazo el material fallback magenta de diagnostico"
                Log.e(TAG, "Diag fallback material compilation failed")
                return null
            }
            val payload = package_.getBuffer()
            payload.rewind()
            Material.Builder().payload(payload, payload.remaining()).build(engine)
        } catch (error: Throwable) {
            diagFallbackLastError = error.message ?: error.javaClass.simpleName
            Log.e(TAG, "Diag fallback material compilation threw", error)
            null
        } finally {
            MaterialBuilder.shutdown()
        }
    }

    private fun compileFallback(): Material? {
        return try {
            MaterialBuilder.init()
            val builder = MaterialBuilder()
                .name("ephora_fallback_unlit")
                .platform(MaterialBuilder.Platform.MOBILE)
                .targetApi(targetApi)
                .optimization(MaterialBuilder.Optimization.NONE)
                .shading(MaterialBuilder.Shading.UNLIT)
                .materialDomain(MaterialBuilder.MaterialDomain.SURFACE)
                .vertexDomain(MaterialBuilder.VertexDomain.OBJECT)
                .doubleSided(false)
                .blending(MaterialBuilder.BlendingMode.OPAQUE)
                .require(MaterialBuilder.VertexAttribute.POSITION)
                .material(FALLBACK_FRAGMENT_SOURCE)
            val package_ = builder.build()
            if (!package_.isValid()) {
                fallbackLastError = "filamat rechazo el material fallback minimo"
                Log.e(TAG, "Fallback material compilation failed")
                return null
            }
            val payload = package_.getBuffer()
            payload.rewind()
            Material.Builder().payload(payload, payload.remaining()).build(engine)
        } catch (error: Throwable) {
            fallbackLastError = error.message ?: error.javaClass.simpleName
            Log.e(TAG, "Fallback material compilation threw", error)
            null
        } finally {
            MaterialBuilder.shutdown()
        }
    }

    /**
     * DIAG-VIS testigo (temporal, reversible): material de aislamiento para
     * el objeto testigo. UNLIT rojo solido (1,0,0,1), sin textura, culling
     * NONE, double-sided, colorWrite=true, opaco, sin iluminacion; con
     * depthCulling=false y depthWrite=false SOLO este material ignora y no
     * contamina la profundidad (aisla D). No toca el fallback blanco, el
     * magenta ni la escalera SL. APIs verificadas en Filament 1.75.1
     * (MaterialBuilder.culling/colorWrite/depthWrite/depthCulling/
     * doubleSided existen en esa version).
     */
    private var witnessCompiled: Material? = null

    var witnessLastError: String? = null
        private set

    fun createWitnessInstance(): MaterialInstance? {
        val existing = witnessCompiled
        if (existing != null && engine.isValidMaterial(existing)) {
            return existing.createInstance()
        }
        val compiled = compileWitness() ?: return null
        witnessCompiled = compiled
        return compiled.createInstance()
    }

    private fun compileWitness(): Material? {
        return try {
            MaterialBuilder.init()
            val builder = MaterialBuilder()
                .name("ephora_diag_witness_red")
                .platform(MaterialBuilder.Platform.MOBILE)
                .targetApi(targetApi)
                .optimization(MaterialBuilder.Optimization.NONE)
                .shading(MaterialBuilder.Shading.UNLIT)
                .materialDomain(MaterialBuilder.MaterialDomain.SURFACE)
                .vertexDomain(MaterialBuilder.VertexDomain.OBJECT)
                .doubleSided(true)
                .blending(MaterialBuilder.BlendingMode.OPAQUE)
                .culling(MaterialBuilder.CullingMode.NONE)
                .colorWrite(true)
                .depthWrite(false)
                .depthCulling(false)
                .require(MaterialBuilder.VertexAttribute.POSITION)
                .material(DIAG_WITNESS_FRAGMENT_SOURCE)
            val package_ = builder.build()
            if (!package_.isValid()) {
                witnessLastError = "filamat rechazo el material rojo de aislamiento del testigo"
                Log.e(TAG, "Witness material compilation failed")
                return null
            }
            val payload = package_.getBuffer()
            payload.rewind()
            Material.Builder().payload(payload, payload.remaining()).build(engine)
        } catch (error: Throwable) {
            witnessLastError = error.message ?: error.javaClass.simpleName
            Log.e(TAG, "Witness material compilation threw", error)
            null
        } finally {
            MaterialBuilder.shutdown()
        }
    }
    enum class ForensicFaceCull { NONE, BACK }
    enum class ForensicDepth { NORMAL, OFF }

    data class ForensicKey(
        val r: Float,
        val g: Float,
        val b: Float,
        val faceCull: ForensicFaceCull,
        val depth: ForensicDepth
    )

    private val forensicCompiled = HashMap<ForensicKey, Material>()

    var forensicLastError: String? = null
        private set

    fun createForensicInstance(key: ForensicKey): MaterialInstance? {
        val existing = forensicCompiled[key]
        if (existing != null && engine.isValidMaterial(existing)) {
            return existing.createInstance()
        }
        val compiled = compileForensic(key) ?: return null
        forensicCompiled[key] = compiled
        return compiled.createInstance()
    }

    fun forensicIsCompiled(key: ForensicKey): Boolean {
        val m = forensicCompiled[key] ?: return false
        return engine.isValidMaterial(m)
    }

    fun forensicRequestedLine(key: ForensicKey): String {
        val compiled = forensicIsCompiled(key)
        val c = if (key.r >= 1f && key.g < 1f && key.b < 1f) "rojo(1,0,0,1)"
            else if (key.g >= 1f && key.r < 1f && key.b < 1f) "verde(0,1,0,1)"
            else "rgb(" + key.r + "," + key.g + "," + key.b + ",1)"
        return "materialInstance=" + (if (compiled) "compilada-UNLIT" else "NO-compilada: " + (forensicLastError ?: "?")) + "\n" +
            "unlit=true (solicitado al compilar; Filament 1.75.1 no expone getter)\n" +
            "color=" + c + " solido sin-textura sin-luz\n" +
            "doubleSided=" + (key.faceCull == ForensicFaceCull.NONE) + " (solicitado)\n" +
            "faceCulling=" + (if (key.faceCull == ForensicFaceCull.NONE) "NONE" else "BACK") + " (solicitado)\n" +
            "depthTest=" + (if (key.depth == ForensicDepth.NORMAL) "normal (solicitado)" else "false (solicitado)") + "\n" +
            "depthWrite=" + (key.depth == ForensicDepth.NORMAL) + " (solicitado)\n" +
            "colorWrite=true (solicitado)\n" +
            "blending=OPAQUE (solicitado)"
    }

    private fun compileForensic(key: ForensicKey): Material? {
        return try {
            MaterialBuilder.init()
            val gc = fun(v: Float): String = if (v >= 1f) "1.0" else "0.0"
            val src = "void material(inout MaterialInputs material) {\n" +
                "prepareMaterial(material);\n" +
                "material.baseColor = vec4(" + gc(key.r) + ", " + gc(key.g) + ", " + gc(key.b) + ", 1.0);\n" +
                "}\n"
            val builder = MaterialBuilder()
                .name("ephora_forensic")
                .platform(MaterialBuilder.Platform.MOBILE)
                .targetApi(targetApi)
                .optimization(MaterialBuilder.Optimization.NONE)
                .shading(MaterialBuilder.Shading.UNLIT)
                .materialDomain(MaterialBuilder.MaterialDomain.SURFACE)
                .vertexDomain(MaterialBuilder.VertexDomain.OBJECT)
                .doubleSided(key.faceCull == ForensicFaceCull.NONE)
                .blending(MaterialBuilder.BlendingMode.OPAQUE)
                .culling(if (key.faceCull == ForensicFaceCull.NONE) MaterialBuilder.CullingMode.NONE else MaterialBuilder.CullingMode.BACK)
                .colorWrite(true)
                .depthWrite(key.depth == ForensicDepth.NORMAL)
                .depthCulling(key.depth == ForensicDepth.NORMAL)
                .require(MaterialBuilder.VertexAttribute.POSITION)
                .material(src)
            val package_ = builder.build()
            if (!package_.isValid()) {
                forensicLastError = "filamat rechazo el material forense " + key
                Log.e(TAG, "Forensic material compilation failed: " + key)
                return null
            }
            val payload = package_.getBuffer()
            payload.rewind()
            Material.Builder().payload(payload, payload.remaining()).build(engine)
        } catch (error: Throwable) {
            forensicLastError = error.message ?: error.javaClass.simpleName
            Log.e(TAG, "Forensic material compilation threw", error)
            null
        } finally {
            MaterialBuilder.shutdown()
        }
    }

    /**
     * material SL completo no compila en el dispositivo (824 builds, ok 0) y
     * `Package.isValid()` no dice por que, asi que el propio arranque
     * biseca en el dispositivo: cada clave prueba de arriba abajo y se queda
     * con la primera variante que compile. Nada termina blanco: el ultimo
     * peldano sigue siendo UNLIT texturizado con tint y UV reales.
     * Sin TANGENTS explicito en ningun peldano (la geometria no trae
     * tangentes y el sombreado usado no las necesita).
     */
    private enum class MaterialTier {
        LIT_FULL,
        UNLIT_UV,
        LIT_MIN,
        UNLIT_MIN
    }

    private data class TieredKey(
        val tier: MaterialTier,
        val alphaMode: AlphaMode,
        val doubleSided: Boolean
    )

    private val rejectedTiers = HashSet<TieredKey>()
    private val instanceTiers = HashMap<MaterialInstance, MaterialTier>()

    /** Ultimo rechazo con detalle (peldano, clave, api, cuenta de params). */
    var lastTierFailure: String = ""
        private set

    /** Intentosh OK/fallo por peldano, para el informe. */
    private val tierBuilds = HashMap<String, Long>()
    private val tierOk = HashMap<String, Long>()

    private fun materialFor(desc: MaterialDesc): Pair<Material, MaterialTier>? {
        val keyStr = desc.alphaMode.name + (if (desc.doubleSided) "_ds" else "_ss")
        keyCounts[keyStr] = (keyCounts[keyStr] ?: 0L) + 1L
        for (tier in MaterialTier.values()) {
            val tiered = TieredKey(tier, desc.alphaMode, desc.doubleSided)
            val existing = compiledTiers[tiered]
            if (existing != null && engine.isValidMaterial(existing)) {
                cacheHits += 1
                return existing to tier
            }
            if (rejectedTiers.contains(tiered)) {
                continue
            }
            cacheMiss += 1
            val material = compileTier(tier, tiered) ?: continue
            compiledTiers[tiered] = material
            return material to tier
        }
        return null
    }

    /** Resumen de la escalera para el informe (que peldano compila). */
    fun tierSummary(): String {
        val parts = ArrayList<String>(MaterialTier.values().size)
        for (tier in MaterialTier.values()) {
            val builds = tierBuilds[tier.name] ?: 0L
            val ok = tierOk[tier.name] ?: 0L
            parts.add(tier.name + "=" + ok + "/" + builds)
        }
        return parts.joinToString("  ·  ")
    }

    private fun compileTier(tier: MaterialTier, key: TieredKey): Material? {
        return try {
            MaterialBuilder.init()
            buildAttempts += 1
            tierBuilds[tier.name] = (tierBuilds[tier.name] ?: 0L) + 1L
            val builder = baseBuilder(tier, key)
            val filamatStart = System.nanoTime()
            val package_ = builder.build()
            filamatBuildNanos += System.nanoTime() - filamatStart
            if (!package_.isValid()) {
                rejectedTiers.add(key)
                val detail = "rechazado " + tier.name + " " + key.alphaMode +
                    (if (key.doubleSided) "_ds" else "_ss") +
                    " api=" + targetApi +
                    " params=" + uniformCountOf(tier) +
                    " samplers=1" +
                    " attrs=" + attributeCountOf(tier)
                lastTierFailure = detail
                compilationFailure = "material compiler rejected $key ($detail)"
                Log.e(TAG, "Filament material compilation failed for $key tier " + tier.name)
                Log.e(TAG, "shader rechazado (" + tier.name + "):\n" + shaderOf(tier))
                return null
            }
            val payload = package_.getBuffer()
            payload.rewind()
            val matStart = System.nanoTime()
            val built = Material.Builder().payload(payload, payload.remaining()).build(engine)
            materialBuildNanos += System.nanoTime() - matStart
            buildOk += 1
            tierOk[tier.name] = (tierOk[tier.name] ?: 0L) + 1L
            built
        } catch (error: Throwable) {
            rejectedTiers.add(key)
            lastTierFailure = tier.name + " excepcion: " +
                (error.message ?: error.javaClass.simpleName)
            compilationFailure = lastTierFailure
            Log.e(TAG, "Filament material compilation threw (" + tier.name + ")", error)
            null
        } finally {
            MaterialBuilder.shutdown()
        }
    }

    private fun baseBuilder(tier: MaterialTier, key: TieredKey): MaterialBuilder {
        var builder = MaterialBuilder()
            .name("sl_${tier.name}_${key.alphaMode.name}_${if (key.doubleSided) "ds" else "ss"}")
            .platform(MaterialBuilder.Platform.MOBILE)
            .targetApi(targetApi)
            .optimization(MaterialBuilder.Optimization.NONE)
            .shading(
                if (tier == MaterialTier.LIT_FULL || tier == MaterialTier.LIT_MIN) {
                    MaterialBuilder.Shading.LIT
                } else {
                    MaterialBuilder.Shading.UNLIT
                }
            )
            .materialDomain(MaterialBuilder.MaterialDomain.SURFACE)
            .vertexDomain(MaterialBuilder.VertexDomain.OBJECT)
            .doubleSided(key.doubleSided)
            .blending(
                when (key.alphaMode) {
                    AlphaMode.OPAQUE -> MaterialBuilder.BlendingMode.OPAQUE
                    AlphaMode.MASKED -> MaterialBuilder.BlendingMode.MASKED
                    AlphaMode.BLEND -> MaterialBuilder.BlendingMode.TRANSPARENT
                }
            )
            .require(MaterialBuilder.VertexAttribute.POSITION)
            .require(MaterialBuilder.VertexAttribute.UV0)
            .uniformParameter(MaterialBuilder.UniformType.FLOAT4, "baseColor")
        // Every textured tier must preserve TextureEntry repeat, offset and rotation.
        builder = builder
            .uniformParameter(MaterialBuilder.UniformType.FLOAT4, "uvTransform")
            .uniformParameter(MaterialBuilder.UniformType.FLOAT, "uvRotation")
        if (tier == MaterialTier.LIT_FULL || tier == MaterialTier.UNLIT_UV) {
            builder = builder
                .uniformParameter(MaterialBuilder.UniformType.FLOAT, "planarMode")
                .uniformParameter(MaterialBuilder.UniformType.FLOAT3, "planarBasisU")
                .uniformParameter(MaterialBuilder.UniformType.FLOAT3, "planarBasisV")
        }
        if (tier == MaterialTier.LIT_FULL || tier == MaterialTier.LIT_MIN) {
            builder = builder
                .uniformParameter(MaterialBuilder.UniformType.FLOAT, "metallic")
                .uniformParameter(MaterialBuilder.UniformType.FLOAT, "roughness")
        }
        if (tier == MaterialTier.LIT_FULL) {
            builder = builder
                .uniformParameter(MaterialBuilder.UniformType.FLOAT4, "emissive")
        }
        builder = builder.samplerParameter(
            MaterialBuilder.SamplerType.SAMPLER_2D,
            MaterialBuilder.SamplerFormat.FLOAT,
            MaterialBuilder.ParameterPrecision.MEDIUM,
            "baseColorMap"
        )
        return builder.material(shaderOf(tier))
    }

    private fun shaderOf(tier: MaterialTier): String = when (tier) {
        MaterialTier.LIT_FULL -> FRAGMENT_SOURCE
        MaterialTier.LIT_MIN -> FRAGMENT_LIT_MIN
        MaterialTier.UNLIT_UV -> FRAGMENT_UNLIT_UV
        MaterialTier.UNLIT_MIN -> FRAGMENT_UNLIT_MIN
    }

    private fun uniformCountOf(tier: MaterialTier): Int = when (tier) {
        MaterialTier.LIT_FULL -> 9
        MaterialTier.UNLIT_UV -> 6
        MaterialTier.LIT_MIN -> 5
        MaterialTier.UNLIT_MIN -> 3
    }

    private fun attributeCountOf(tier: MaterialTier): Int = 2

    /**
     * Creates a material instance for [desc].
     *
     * [textureOf] resolves the descriptor's texture handle (or null when the face
     * has no texture) to a GPU texture — it must never return null, the caller
     * substitutes a 1x1 white texture so that `baseColor * texture` stays valid.
     * [samplerOf] picks the matching sampler (mipmapped or not).
     */
    fun createInstance(
        desc: MaterialDesc,
        textureOf: (TextureHandle?) -> Texture,
        samplerOf: (TextureHandle?) -> TextureSampler
    ): MaterialInstance? {
        instanceCreates += 1
        val sig = sigOf(desc)
        sigCounts[sig] = (sigCounts[sig] ?: 0L) + 1L
        val (material, tier) = materialFor(desc) ?: return null
        val allocStart = System.nanoTime()
        val instance = material.createInstance()
        instanceAllocNanos += System.nanoTime() - allocStart
        instanceTiers[instance] = tier
        apply(instance, desc, textureOf, samplerOf)
        return instance
    }

    /**
     * 2.27c: olvida el peldano de una instancia destruida. Sin esto el mapa
     * retendria cada MaterialInstance nativo destruido por el GC de
     * materiales (fuga gestionada + nativa).
     */
    fun releaseInstance(instance: MaterialInstance) {
        instanceTiers.remove(instance)
    }

    /** Solo booleanos: distingue propiedades sin guardar ni un float (combinaciones finitas). */
    private fun sigOf(desc: MaterialDesc): String {
        val uv = desc.uvTransform
        val uvDef = uv.size >= 4 && uv[0] == 1f && uv[1] == 1f && uv[2] == 0f && uv[3] == 0f && desc.uvRotation == 0f
        val c = desc.baseColor
        val tintBlanca = c.size >= 4 && c[0] == 1f && c[1] == 1f && c[2] == 1f && c[3] == 1f
        return desc.alphaMode.name +
            (if (desc.doubleSided) "_ds" else "_ss") +
            (if (desc.texture != null) "_tex" else "_plano") +
            (if (desc.fullBright) "_fb" else "") +
            "_code" + desc.materialCode +
            (if (uvDef) "_uv" else "_UV") +
            (if (desc.planarMode > 0.5f) "_PLANAR" else "") +
            (if (tintBlanca) "_tinta" else "_TINTA")
    }

    /** Re-applies [desc] to an instance that already exists (texture loaded, tint changed...). */
    fun apply(
        instance: MaterialInstance,
        desc: MaterialDesc,
        textureOf: (TextureHandle?) -> Texture,
        samplerOf: (TextureHandle?) -> TextureSampler,
        countAsRebuild: Boolean = false
    ) {
        // 2.27c: solo se fijan los parametros que el peldano de esta
        // instancia declara. Fijar un parametro inexistente es inocuo en
        // Filament, pero no hacerlo deja el informe honesto sobre que
        // peldano dibuja cada cara.
        val tier = instanceTiers[instance] ?: MaterialTier.LIT_FULL
        val start = System.nanoTime()
        instance.setParameter(
            "baseColor",
            desc.baseColor[0], desc.baseColor[1], desc.baseColor[2], desc.baseColor[3]
        )
        instance.setParameter(
            "uvTransform",
            desc.uvTransform[0], desc.uvTransform[1], desc.uvTransform[2], desc.uvTransform[3]
        )
        instance.setParameter("uvRotation", desc.uvRotation)
        if (tier == MaterialTier.LIT_FULL || tier == MaterialTier.UNLIT_UV) {
            instance.setParameter("planarMode", desc.planarMode)
            instance.setParameter(
                "planarBasisU",
                desc.planarBasisU.getOrElse(0) { 0f },
                desc.planarBasisU.getOrElse(1) { 1f },
                desc.planarBasisU.getOrElse(2) { 0f }
            )
            instance.setParameter(
                "planarBasisV",
                desc.planarBasisV.getOrElse(0) { 1f },
                desc.planarBasisV.getOrElse(1) { 0f },
                desc.planarBasisV.getOrElse(2) { 0f }
            )
        }
        if (tier == MaterialTier.LIT_FULL || tier == MaterialTier.LIT_MIN) {
            val profile = SlMaterialProfile.of(desc.materialCode)
            instance.setParameter("metallic", profile.metallic)
            instance.setParameter("roughness", profile.roughness)
        }
        if (tier == MaterialTier.LIT_FULL) {
            if (desc.fullBright) {
                instance.setParameter("emissive", desc.baseColor[0], desc.baseColor[1], desc.baseColor[2], 1f)
            } else {
                instance.setParameter("emissive", 0f, 0f, 0f, 0f)
            }
        }
        instance.setParameter("baseColorMap", textureOf(desc.texture), samplerOf(desc.texture))
        val elapsed = System.nanoTime() - start
        paramApplies += 1
        paramApplyNanos += elapsed
        if (countAsRebuild) {
            rebuilds += 1
            rebuildNanos += elapsed
        }
    }

    fun destroy() {
        for (material in compiledTiers.values) {
            if (engine.isValidMaterial(material)) {
                engine.destroyMaterial(material)
            }
        }
        compiledTiers.clear()
        rejectedTiers.clear()
        instanceTiers.clear()
        // 2.27b: el fallback vive aparte del cache de variantes SL.
        fallbackCompiled?.let {
            if (engine.isValidMaterial(it)) {
                engine.destroyMaterial(it)
            }
        }
        fallbackCompiled = null
        diagCompiled?.let {
            if (engine.isValidMaterial(it)) {
                engine.destroyMaterial(it)
            }
        }
        diagCompiled = null
        witnessCompiled?.let {
            if (engine.isValidMaterial(it)) {
                engine.destroyMaterial(it)
            }
        }
        witnessCompiled = null
        for (material in forensicCompiled.values) {
            if (engine.isValidMaterial(material)) {
                engine.destroyMaterial(material)
            }
        }
        forensicCompiled.clear()
    }

    /** TEST 2: resumen de claves pedidas a materialFor (qué se pide compilar). */
    fun keySummary(): String {
        if (keyCounts.isEmpty()) return "sin llamadas"
        return keyCounts.entries.sortedByDescending { it.value }
            .joinToString("  ·  ") { it.key + " x" + it.value }
    }

    /** TEST 2: firmas de instancia (qué propiedades distinguen instancias). */
    fun sigSummary(limit: Int = 8): String {
        if (sigCounts.isEmpty()) return "sin instancias"
        val top = sigCounts.entries.sortedByDescending { it.value }.take(limit)
            .joinToString("  ·  ") { it.key + " x" + it.value }
        return sigCounts.size.toString() + " firmas (" + top + ")" +
            (if (sigCounts.size > limit) " + " + (sigCounts.size - limit) + " mas" else "")
    }

    companion object {
        private const val TAG = "FilamentMaterials"

        val WHITE = floatArrayOf(1f, 1f, 1f, 1f)

        /**
         * 2.27b: shader del fallback de arranque. UNLIT blanco constante, sin
         * leer atributos, uniforms ni texturas. A proposito no comparte nada
         * con FRAGMENT_SOURCE.
         */
        val FALLBACK_FRAGMENT_SOURCE = """
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                material.baseColor = vec4(1.0);
            }
        """

        /**
         * DIAG-VIS (temporal, reversible): shader del fallback magenta de
         * diagnostico. Identico al blanco pero en magenta constante.
         */
        val DIAG_FALLBACK_FRAGMENT_SOURCE = """
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                material.baseColor = vec4(1.0, 0.0, 1.0, 1.0);
            }
        """

        /**
         * DIAG-VIS testigo (temporal, reversible): rojo solido de
         * aislamiento, sin leer atributos, uniforms ni texturas.
         */
        val DIAG_WITNESS_FRAGMENT_SOURCE = """
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                material.baseColor = vec4(1.0, 0.0, 0.0, 1.0);
            }
        """

        /**
         * Fallback lit: conserva el xform TextureEntry (repeat/offset/rotation).
         * Sin planar ni emissive; el tier UNLIT_UV va antes para conservar planar.
         */
        val FRAGMENT_LIT_MIN = """
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                vec2 st = getUV0() - vec2(0.5);
                float c = cos(materialParams.uvRotation);
                float s = sin(materialParams.uvRotation);
                vec2 rs = vec2(st.x * c + st.y * s, -st.x * s + st.y * c);
                rs *= materialParams.uvTransform.xy;
                vec2 sl = rs + materialParams.uvTransform.zw + vec2(0.5);
                vec2 uv = vec2(sl.x, 1.0 - sl.y);
                material.baseColor = materialParams.baseColor * texture(materialParams_baseColorMap, uv);
                material.metallic = materialParams.metallic;
                material.roughness = materialParams.roughness;
            }
        """

        /**
         * 2.27c: peldano UNLIT_UV. El "fallback por material" con textura,
         * tint, repeat/offset/rotation y planar reales (paso 5): mientras el
         * LIT no compile, el mundo muestra sus texturas en vez de blanco.
         */
        val FRAGMENT_UNLIT_UV = """
            varying vec3 vObjPos;
            void materialVertex(inout MaterialVertexInputs material) {
                vObjPos = getPosition().xyz;
            }
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                vec2 st = getUV0();
                if (materialParams.planarMode > 0.5) {
                    st = vec2(2.0 * dot(materialParams.planarBasisU, vObjPos) + 0.5,
                              0.5 - 2.0 * dot(materialParams.planarBasisV, vObjPos));
                }
                st -= vec2(0.5);
                float c = cos(materialParams.uvRotation);
                float s = sin(materialParams.uvRotation);
                vec2 rs = vec2(st.x * c + st.y * s, -st.x * s + st.y * c);
                rs *= materialParams.uvTransform.xy;
                vec2 sl = rs + materialParams.uvTransform.zw + vec2(0.5);
                vec2 uv = vec2(sl.x, 1.0 - sl.y);
                material.baseColor = materialParams.baseColor * texture(materialParams_baseColorMap, uv);
            }
        """

        /**
         * Ultimo peldano: mantiene el xform TextureEntry incluso en el shader minimo.
         * La proyeccion planar solo esta disponible en los tiers superiores.
         */
        val FRAGMENT_UNLIT_MIN = """
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                vec2 st = getUV0() - vec2(0.5);
                float c = cos(materialParams.uvRotation);
                float s = sin(materialParams.uvRotation);
                vec2 rs = vec2(st.x * c + st.y * s, -st.x * s + st.y * c);
                rs *= materialParams.uvTransform.xy;
                vec2 sl = rs + materialParams.uvTransform.zw + vec2(0.5);
                vec2 uv = vec2(sl.x, 1.0 - sl.y);
                material.baseColor = materialParams.baseColor * texture(materialParams_baseColorMap, uv);
            }
        """

        /**
         * The fragment stage. Second Life's TextureEntry gives a per-face
         * texture, tint, alpha and a repeat/offset/rotation transform, so the
         * shader multiplies a tinted texture into Filament's PBR inputs.
         * Base UV selection mirrors the official viewer (llface.cpp):
         * default mapping uses the geometry UVs; planar mapping
         * (`planarProjection`: u = 2*(B.P)+0.5, v = 0.5-2*(T.P) from the
         * object-space position P and the per-face basis) replaces them.
         * Both then go through the official `xform`, same order: centre on
         * the face, rotate, scale (repeat, sign = flip), offset.
         * 2.26: `sl` es el UV oficial (origen V abajo, como la geometria del
         * visor oficial); `uv` lo convierte al convenio de Filament (origen V
         * abajo en el buffer: `Texture.h` documenta `yoffset` como "Bottom
         * offset", y el decoder entrega la fila superior primero). Sin esa
         * conversion cada textura salia volteada en V (offsetV invertido y
         * rotacion espejada respecto a SL). Se aplica al muestrear, despues
         * del xform, asi que repeat/offset/rotation/planar conservan la
         * semantica SL por cara, sin tocar geometria ni reconstruir nada.
         * Filament still does the lighting, shadows, tone mapping and so on.
         */
        val FRAGMENT_SOURCE = """
            varying vec3 vObjPos;
            void materialVertex(inout MaterialVertexInputs material) {
                vObjPos = getPosition().xyz;
            }
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                vec2 st = getUV0();
                if (materialParams.planarMode > 0.5) {
                    st = vec2(2.0 * dot(materialParams.planarBasisU, vObjPos) + 0.5,
                              0.5 - 2.0 * dot(materialParams.planarBasisV, vObjPos));
                }
                st -= vec2(0.5);
                float c = cos(materialParams.uvRotation);
                float s = sin(materialParams.uvRotation);
                vec2 rs = vec2(st.x * c + st.y * s, -st.x * s + st.y * c);
                rs *= materialParams.uvTransform.xy;
                vec2 sl = rs + materialParams.uvTransform.zw + vec2(0.5);
                vec2 uv = vec2(sl.x, 1.0 - sl.y);
                vec4 base = materialParams.baseColor * texture(materialParams_baseColorMap, uv);
                material.baseColor = base;
                material.metallic = materialParams.metallic;
                material.roughness = materialParams.roughness;
                material.emissive = materialParams.emissive;
            }
        """.trimIndent()
    }
}

/**
 * Second Life's per-prim material code (the `Material` byte of ObjectUpdate)
 * mapped onto the parameters Filament's standard lighting model wants. These are
 * shading approximations — the material table is not part of the protocol.
 */
class SlMaterialProfile private constructor(val metallic: Float, val roughness: Float) {

    companion object {
        /** The `Material` byte: 0 stone, 1 metal, 2 glass, 3 wood, 4 flesh, 5 plastic, 6 rubber, 7 light. */
        fun of(materialCode: Int): SlMaterialProfile = when (materialCode) {
            0 -> STONE
            1 -> METAL
            2 -> GLASS
            3 -> WOOD
            4 -> FLESH
            5 -> PLASTIC
            6 -> RUBBER
            7 -> LIGHT
            else -> WOOD
        }

        private val STONE = SlMaterialProfile(0.0f, 0.92f)
        private val METAL = SlMaterialProfile(0.85f, 0.32f)
        private val GLASS = SlMaterialProfile(0.10f, 0.12f)
        private val WOOD = SlMaterialProfile(0.0f, 0.72f)
        private val FLESH = SlMaterialProfile(0.0f, 0.55f)
        private val PLASTIC = SlMaterialProfile(0.0f, 0.38f)
        private val RUBBER = SlMaterialProfile(0.0f, 0.88f)
        private val LIGHT = SlMaterialProfile(0.0f, 0.45f)
    }
}
