package com.lumiyaviewer.lumiya.slworld

import com.lumiyaviewer.lumiya.renderer.AlphaMode
import com.lumiyaviewer.lumiya.renderer.MaterialDesc
import com.lumiyaviewer.lumiya.renderer.MaterialHandle
import com.lumiyaviewer.lumiya.renderer.Renderer
import com.lumiyaviewer.lumiya.renderer.TextureHandle

/**
 * Turns Second Life faces into renderer materials, and remembers what it made.
 *
 * A material is identified by everything that can change its appearance: the
 * texture UUID, the tint and alpha, the UV mapping (repeat, offset, rotation),
 * the prim's material code and the fullbright flag. All of that comes from the
 * share one material instance and one GPU shader.
 *
 * ## Textures
 *
 * Since fase 2.13b the pixels can arrive: [TextureStreamer] uploads what the
 * pipeline downloaded and calls [textureDecoded], and every material already
 * built for that UUID is rebuilt with the image bound. Until then the tint comes
 * from `PrimShapeClassifier.fallbackColor`, which is derived from the texture
 * UUID purely so that different objects are visually
 * distinguishable while debugging — it is *not* the object's real colour and the
 * debug overlay says so. The per-face `TextureEntry` colour, alpha,
 * repeat/offset/rotation and fullbright already come from the protocol, so a
 * face with no pixels yet is drawn with its real tint whenever the wire carried
 * one, and with the debug tint only when it did not. Faces that share texture
 * and tint still get separate materials when their UV mapping differs.
 *
 * The images themselves are owned by the pipeline (`TexturePipeline.decoded`),
 * which is where the JPEG2000 layer, the memory budget and the eviction policy
 * of Phase 4 live. This class only remembers the GPU handle, so it stays the
 * place that answers "what does face N of this prim look like".
 */
class SLTextureCache(private val renderer: Renderer) {

    private class Key(
        val textureId: String,
        val colorKey: Long,
        val materialCode: Int,
        val fullBright: Boolean,
        val uvKey: Long,
        val doubleSided: Boolean = false
    ) {
        override fun equals(other: Any?): Boolean = other is Key &&
            other.textureId == textureId &&
            other.colorKey == colorKey &&
            other.materialCode == materialCode &&
            other.fullBright == fullBright &&
            other.uvKey == uvKey &&
            other.doubleSided == doubleSided

        override fun hashCode(): Int {
            var result = textureId.hashCode()
            result = 31 * result + colorKey.hashCode()
            result = 31 * result + materialCode
            result = 31 * result + if (fullBright) 1 else 0
            result = 31 * result + uvKey.hashCode()
            result = 31 * result + if (doubleSided) 1 else 0
            return result
        }
    }

    private class Entry(
        val handle: MaterialHandle,
        val face: SLTextureFace,
        val materialCode: Int,
        val fullBright: Boolean,
        val doubleSided: Boolean = false,
        /** Base planar con la que se construyo (null = mapeado default). */
        val planar: PlanarBasis? = null
    )

    private val entries = HashMap<Key, Entry>()
    private val decoded = HashMap<String, TextureHandle>()
    /**
     * Recovery 4 (método Lumiya: el render thread nunca hace trabajo
     * ilimitado): índice UUID → materiales que lo usan, para que `rebind`
     * toque solo los afectados en vez de barrer todos los materiales por cada
     * textura que llega. `entries` sigue siendo la fuente de verdad; este mapa
     * solo acelera la búsqueda.
     */
    private val byTexture = HashMap<String, MutableList<Entry>>()
    /**
     * Textures whose decoded pixels carry an alpha channel. A face with an
     * opaque wire tint but such a texture still needs blending (water, glass,
     * foliage cutout): without it the transparent parts draw as solid walls.
     * Filled when the pixels arrive, so `rebind` upgrades the materials then.
     */
    private val alphaTextures = HashSet<String>()

    /** Approximate GPU residency in bytes, including mip levels (~4/3 of RGBA base). */
    private val gpuBytesByTexture = HashMap<String, Long>()
    private val lastUsedNanos = HashMap<String, Long>()
    private var gpuBytesEstimate: Long = 0L

    /**
     * Soft/hard residency limits for viewer-owned diffuse textures. They are
     * intentionally separate from the CPU codestream cache: a J2K blob can be
     * cheap while its Filament texture (with mip levels) is not.
     */
    var created = 0
        private set
    var reused = 0
        private set
    var rebinds = 0
        private set
    /** 2.26: materiales destruidos por el GC (sin usuarios vivos). */
    var evictedMaterials = 0
        private set
    /** 2.26: texturas GPU destruidas por el GC (ninguna cara viva las usa). */
    var evictedTextures = 0
        private set

    /**
     * The materials for every face the mesh draws, in face-index order.
     *
     * A prim's faces are independent in `TextureEntry`: face 0 can be a photo
     * and face 3 a wood texture, with different tints and UV mappings. So the
     * lookup is per face, and the key (texture, tint, material code, fullbright)
     * is what gives the sharing back — a prim whose six faces are identical
     * creates one material and reuses it five times, and one texture used by two
     * hundred prims still costs one material each time its tint differs and none
     * extra when it does not.
     */
    fun materialsFor(faces: List<SLTextureFace>, materialCode: Int, fullBright: Boolean): IntArray {
        return materialsFor(faces, materialCode, fullBright, doubleSided = false)
    }

    /**
     * Variante para geometría sculpt (fase 5): doble cara, porque el byte de
     * tipo puede traer flags de espejo/inversión cuya semántica exacta de
     * bobinado no se reproduce; la doble cara evita que un bobinado invertido
     * vuelva invisible el objeto. Los prims normales siguen con la vía
     * anterior, intacta.
     */
    fun materialsFor(
        faces: List<SLTextureFace>,
        materialCode: Int,
        fullBright: Boolean,
        doubleSided: Boolean
    ): IntArray {
        return materialsFor(faces, materialCode, fullBright, doubleSided, planar = null)
    }

    /**
     * Vía 2.25 (texture mapping SL): [planar] trae la base planar por cara en
     * orden de renderizado (mismo orden que [faces]), o null cuando ninguna
     * cara la necesita. Las caras van en orden de grupo de renderizado, no de
     * índice TE: cada grupo dibuja con la cara TE que `faceIndexAt` indica.
     */
    fun materialsFor(
        faces: List<SLTextureFace>,
        materialCode: Int,
        fullBright: Boolean,
        doubleSided: Boolean,
        planar: List<PlanarBasis?>?
    ): IntArray {
        if (faces.isEmpty()) {
            return IntArray(1) { materialFor(SLTextureFace.DEFAULT_FACE, materialCode, fullBright, doubleSided).id }
        }
        return IntArray(faces.size) { index ->
            materialFor(faces[index], materialCode, fullBright, doubleSided, planar?.getOrNull(index)).id
        }
    }

    /** The material for one face, creating it the first time it is needed. */
    fun materialFor(face: SLTextureFace, materialCode: Int, fullBright: Boolean): MaterialHandle {
        return materialFor(face, materialCode, fullBright, doubleSided = false)
    }

    /** Variante con doble cara (sculpt); la común queda intacta. */
    fun materialFor(face: SLTextureFace, materialCode: Int, fullBright: Boolean, doubleSided: Boolean): MaterialHandle {
        return materialFor(face, materialCode, fullBright, doubleSided, planar = null)
    }

    /** Vía 2.25 con base planar opcional (null = mapeado default). */
    fun materialFor(
        face: SLTextureFace,
        materialCode: Int,
        fullBright: Boolean,
        doubleSided: Boolean,
        planar: PlanarBasis?
    ): MaterialHandle {
        val key = Key(face.textureId, colorKeyOf(face), materialCode, fullBright, uvKeyOf(face, planar), doubleSided)
        entries[key]?.let {
            reused += 1
            return it.handle
        }
        val handle = renderer.createMaterial(descriptorFor(face, materialCode, fullBright, doubleSided, planar))
        val entry = Entry(handle, face, materialCode, fullBright, doubleSided, planar)
        entries[key] = entry
        byTexture.getOrPut(face.textureId) { ArrayList() }.add(entry)
        created += 1
        return handle
    }

    /**
     * Registers a decoded texture. Every material already using that UUID is
     * rebuilt so the pixels are actually bound; materials created afterwards
     * pick it up automatically. [hasAlpha] is remembered because it changes
     * the blend mode even when the wire tint is opaque.
     *
     * 2.26: si ya habia otra textura GPU para este UUID (re-upload tras un
     * clear de escena), la anterior se destruye aqui: su propietario es este
     * cache y nadie mas la referencia. Sin esto cada cambio de contenido
     * fugaba todas las texturas GPU anteriores.
     */
    fun textureDecoded(
        textureId: String,
        texture: TextureHandle,
        hasAlpha: Boolean,
        gpuResidencyBytes: Long
    ) {
        val previous = decoded[textureId]
        val previousBytes = gpuBytesByTexture.remove(textureId) ?: 0L
        val estimatedBytes = gpuResidencyBytes.coerceAtLeast(0L)
        this.gpuBytesEstimate = (this.gpuBytesEstimate - previousBytes).coerceAtLeast(0L)
        decoded[textureId] = texture
        gpuBytesByTexture[textureId] = estimatedBytes
        this.gpuBytesEstimate += estimatedBytes
        lastUsedNanos[textureId] = System.nanoTime()
        if (previous != null && previous.id != texture.id) {
            renderer.destroyTexture(previous)
        }
        if (hasAlpha) {
            alphaTextures.add(textureId)
        } else {
            alphaTextures.remove(textureId)
        }
        rebind(textureId)
    }

    /** Rebuilds every material that uses this UUID; returns how many. */
    private fun rebind(textureId: String): Int {
        val affected = byTexture[textureId] ?: return 0
        var updated = 0
        for (entry in affected) {
            renderer.updateMaterial(entry.handle, descriptorFor(entry.face, entry.materialCode, entry.fullBright, entry.doubleSided, entry.planar))
            rebinds += 1
            updated += 1
        }
        return updated
    }

    /** Drops a decoded texture (LRU eviction in Phase 4). */
    fun forgetTexture(textureId: String) {
        val texture = decoded.remove(textureId) ?: return
        val bytes = gpuBytesByTexture.remove(textureId) ?: 0L
        gpuBytesEstimate = (gpuBytesEstimate - bytes).coerceAtLeast(0L)
        alphaTextures.remove(textureId)
        lastUsedNanos.remove(textureId)
        renderer.destroyTexture(texture)
        rebind(textureId)
    }

    /** Mark a texture as recently used by the current camera/visible set. */
    fun markUsed(textureId: String, nowNanos: Long = System.nanoTime()) {
        if (textureId.isEmpty() || textureId == SLTextureFace.DEFAULT_UUID) {
            return
        }
        lastUsedNanos[textureId] = nowNanos
    }

    /**
     * Before creating a new GPU texture, evict the oldest unprotected textures
     * when the projected residency would exceed the hard limit. A texture used
     * recently by the camera stays protected for a short window, so moving the
     * camera does not immediately destroy what is being looked at.
     */
    fun prepareForUpload(textureId: String, incomingBytes: Long, nowNanos: Long = System.nanoTime()): Int {
        val incoming = incomingBytes.coerceAtLeast(0L)
        val replacingBytes = gpuBytesByTexture[textureId] ?: 0L
        val projected = (gpuBytesEstimate - replacingBytes).coerceAtLeast(0L) + incoming
        if (gpuBytesEstimate <= GPU_TEXTURE_HARD_LIMIT_BYTES && projected <= GPU_TEXTURE_HARD_LIMIT_BYTES) {
            return 0
        }
        return trimGpuTextures(nowNanos, GPU_TEXTURE_SOFT_LIMIT_BYTES)
    }

    /**
     * LRU trim for GPU textures. Materials remain valid: [forgetTexture]
     * destroys only the texture and rebinds the already-existing material to its
     * fallback. The next near/visible texture scan can request the UUID again.
     */
    fun trimGpuTextures(nowNanos: Long = System.nanoTime(), targetBytes: Long = GPU_TEXTURE_SOFT_LIMIT_BYTES): Int {
        if (decoded.isEmpty() || gpuBytesEstimate <= targetBytes) {
            pruneUsageHistory(nowNanos)
            return 0
        }
        val candidates = decoded.keys
            .asSequence()
            .filter { id ->
                val last = lastUsedNanos[id]
                last == null || nowNanos - last > GPU_TEXTURE_PROTECTION_NANOS
            }
            .sortedBy { id -> lastUsedNanos[id] ?: Long.MIN_VALUE }
            .take(GPU_TEXTURE_MAX_EVICTIONS_PER_TRIM)
            .toList()
        var freed = 0
        for (id in candidates) {
            if (gpuBytesEstimate <= targetBytes) {
                break
            }
            if (!decoded.containsKey(id)) {
                continue
            }
            forgetTexture(id)
            evictedTextures += 1
            freed += 1
        }
        pruneUsageHistory(nowNanos)
        return freed
    }

    private fun pruneUsageHistory(nowNanos: Long) {
        if (lastUsedNanos.size <= decoded.size + 256) {
            return
        }
        val cutoff = nowNanos - (GPU_TEXTURE_PROTECTION_NANOS * 2L)
        val it = lastUsedNanos.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (!decoded.containsKey(entry.key) && entry.value < cutoff) {
                it.remove()
            }
        }
    }

    val gpuBytes: Long get() = gpuBytesEstimate

    fun textureHandle(textureId: String): TextureHandle? = decoded[textureId]

    /** Whether pixels are bound for this UUID. */
    fun hasDecoded(textureId: String): Boolean = decoded.containsKey(textureId)

    /** Textures with pixels bound to the GPU. */
    val decodedCount: Int get() = decoded.size

    /** Distinct materials (one per texture/tint/material/fullbright combination). */
    val materialCount: Int get() = entries.size

    /**
     * Materials that currently have an image bound. This is the "materiales con
     * textura" figure of the report: it counts what the renderer was actually
     * told to draw with, not what was downloaded.
     */
    val materialsWithTexture: Int
        get() {
            var total = 0
            for (entry in entries.values) {
                if (decoded.containsKey(entry.face.textureId)) {
                    total += 1
                }
            }
            return total
        }

    /**
     * Materials currently drawn blended. This is the "materiales BLEND"
     * figure of the report: faces whose wire tint is translucent, plus faces
     * whose texture pixels carry alpha.
     */
    val blendMaterials: Int
        get() {
            var total = 0
            for (entry in entries.values) {
                if (entry.face.color[3] < AlphaMode.OPAQUE_ALPHA ||
                    alphaTextures.contains(entry.face.textureId)
                ) {
                    total += 1
                }
            }
            return total
        }

    fun clear() {
        for (entry in entries.values) {
            renderer.destroyMaterial(entry.handle)
        }
        entries.clear()
        byTexture.clear()
        for (texture in decoded.values) {
            renderer.destroyTexture(texture)
        }
        decoded.clear()
        alphaTextures.clear()
        gpuBytesByTexture.clear()
        lastUsedNanos.clear()
        gpuBytesEstimate = 0L
    }

    /**
     * 2.26: destruye los materiales que ningun slot vivo referencia.
     * [liveHandles] son los ids de MaterialHandle en uso (los recoge la escena
     * de sus slots). Un material compartido con usuarios vivos jamas se toca.
     * Solo hilo de render. Devuelve cuantos destruyo.
     */
    fun gc(liveHandles: Set<Int>): Int {
        if (entries.isEmpty()) {
            return 0
        }
        var freed = 0
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next().value
            if (liveHandles.contains(entry.handle.id)) {
                continue
            }
            it.remove()
            byTexture[entry.face.textureId]?.remove(entry)
            if (byTexture[entry.face.textureId]?.isEmpty() == true) {
                byTexture.remove(entry.face.textureId)
            }
            renderer.destroyMaterial(entry.handle)
            evictedMaterials += 1
            freed += 1
        }
        return freed
    }

    /**
     * 2.26: destruye las texturas GPU que ninguna cara viva usa ([forgetTexture]
     * ya re-enlaza in-place los materiales afectados al fallback, sin
     * reconstruir renderables). Solo hilo de render. Devuelve cuantas destruyo.
     */
    fun gcTextures(liveIds: Set<String>): Int {
        if (decoded.isEmpty()) {
            return 0
        }
        var freed = 0
        for (id in decoded.keys.toList()) {
            if (liveIds.contains(id)) {
                continue
            }
            forgetTexture(id)
            evictedTextures += 1
            freed += 1
        }
        return freed
    }

    fun stats(): String = created.toString() + " materiales (" + reused + " reutilizados, " +
        rebinds + " reconexiones, " + decoded.size + " texturas, " +
        (gpuBytesEstimate / (1024 * 1024)) + " MiB GPU est., " +
        evictedMaterials + " mat expulsados, " + evictedTextures + " tex expulsadas)"

    private fun descriptorFor(face: SLTextureFace, materialCode: Int, fullBright: Boolean): MaterialDesc {
        return descriptorFor(face, materialCode, fullBright, doubleSided = false, planar = null)
    }

    private fun descriptorFor(
        face: SLTextureFace,
        materialCode: Int,
        fullBright: Boolean,
        doubleSided: Boolean
    ): MaterialDesc {
        return descriptorFor(face, materialCode, fullBright, doubleSided, planar = null)
    }

    private fun descriptorFor(
        face: SLTextureFace,
        materialCode: Int,
        fullBright: Boolean,
        doubleSided: Boolean,
        planar: PlanarBasis?
    ): MaterialDesc {
        val texture = decoded[face.textureId]
        // The wire alpha decides first (unchanged rule). When the tint is
        // opaque but the decoded pixels carry alpha, the face still blends:
        // BLEND is the safe approximation because the wire carries no alpha
        // cutoff for MASKED, and the official viewer's default for an alpha
        // texture without an explicit alpha mode is blending.
        val wireMode = AlphaMode.forAlpha(face.color[3])
        val alphaMode = if (wireMode != AlphaMode.OPAQUE) {
            wireMode
        } else if (texture != null && alphaTextures.contains(face.textureId)) {
            AlphaMode.BLEND
        } else {
            AlphaMode.OPAQUE
        }
        return MaterialDesc(
            baseColor = if (texture != null || face.hasTint) {
                face.color
            } else {
                // No decoded pixels and no tint from the wire yet: use the
                // per-texture debug tint so shapes are distinguishable.
                debugTintFor(face.textureId)
            },
            texture = texture,
            uvTransform = floatArrayOf(face.repeatU, face.repeatV, face.offsetU, face.offsetV),
            uvRotation = face.rotation,
            planarMode = if (face.isPlanar && planar != null) 1f else 0f,
            planarBasisU = planar?.u ?: MaterialDesc.PLANAR_BASIS_U,
            planarBasisV = planar?.v ?: MaterialDesc.PLANAR_BASIS_V,
            fullBright = fullBright || face.fullBright,
            materialCode = materialCode,
            // The face's own alpha decides the blend mode, because the alpha a
            // prim declares on the wire *is* its transparency. Opaque stays the
            // common, cheap path; a face that says it is translucent is drawn
            // translucent instead of being drawn solid.
            alphaMode = alphaMode,
            doubleSided = doubleSided
        )
    }

    private fun colorKeyOf(face: SLTextureFace): Long {
        var value = 0L
        for (channel in face.color) {
            value = value * 31L + (channel * 1000f).toLong()
        }
        return value
    }

    /**
     * The UV mapping is part of the key: two faces with the same texture and
     * tint but a different repeat, offset, rotation, mapping mode or planar
     * basis need different materials, otherwise the second face would draw
     * with the first one's mapping.
     */
    private fun uvKeyOf(face: SLTextureFace, planar: PlanarBasis?): Long {
        var value = face.repeatU.toBits().toLong()
        value = value * 31L + face.repeatV.toBits()
        value = value * 31L + face.offsetU.toBits()
        value = value * 31L + face.offsetV.toBits()
        value = value * 31L + face.rotation.toBits()
        // Only PLANAR changes the current shader path. Unsupported wire modes
        // 2/3 intentionally share DEFAULT until their projection is implemented.
        value = value * 31L + (if (face.isPlanar) 1L else 0L)
        if (planar != null) {
            for (f in planar.u) value = value * 31L + f.toBits()
            for (f in planar.v) value = value * 31L + f.toBits()
        }
        return value
    }

    private companion object {
        private const val GPU_TEXTURE_SOFT_LIMIT_BYTES = 96L * 1024L * 1024L
        private const val GPU_TEXTURE_HARD_LIMIT_BYTES = 128L * 1024L * 1024L
        private const val GPU_TEXTURE_PROTECTION_NANOS = 10_000_000_000L
        private const val GPU_TEXTURE_MAX_EVICTIONS_PER_TRIM = 48

        /**
         * Debug-only tint, stable per texture UUID. Not Second Life data: it
         * exists so that region objects are distinguishable before the real
         * per-face TextureEntry colour and the JPEG2000 pixels are available.
         * `0xFFFFFFFF` is how both "no tint" and a genuinely white tint are
         * encoded on the wire, so a face without a decoded texture always gets
         * this tint until Phase 3/4 land.
         */
        fun debugTintFor(textureId: String): FloatArray {
            val rgb = com.lumiyaviewer.lumiya.slproto.world.PrimShapeClassifier.fallbackColor(textureId, false)
            return floatArrayOf(
                SLTextureFace.srgbToLinear(rgb[0]),
                SLTextureFace.srgbToLinear(rgb[1]),
                SLTextureFace.srgbToLinear(rgb[2]),
                1f
            )
        }
    }
}
