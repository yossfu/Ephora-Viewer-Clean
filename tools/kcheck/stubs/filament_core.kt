package com.google.android.filament

import java.nio.Buffer
import java.nio.ByteBuffer

object Filament {
    fun init() {}
}

class Engine {
    enum class Backend { DEFAULT, OPENGL, VULKAN, METAL, NOOP, WEBGPU }
    enum class FeatureLevel { FEATURE_LEVEL_0, FEATURE_LEVEL_1, FEATURE_LEVEL_2, FEATURE_LEVEL_3 }
    enum class FeatureState { UNSUPPORTED, SUPPORTED }
    enum class GpuContextPriority { DEFAULT, LOW, MEDIUM, HIGH, REALTIME }
    enum class StereoscopicType { INSTANCED, MULTIVIEW }
    class Config {
        var commandBufferSizeMB: Long = 0
    }
    class Builder {
        fun backend(backend: Backend): Builder = this
        fun sharedContext(context: Any?): Builder = this
        fun config(config: Config): Builder = this
        fun featureLevel(level: FeatureLevel): Builder = this
        fun paused(paused: Boolean): Builder = this
        fun feature(name: String, enabled: Boolean): Builder = this
        fun build(): Engine = TODO()
    }
    fun isValid(): Boolean = true
    fun destroy() {}
    fun getBackend(): Backend = Backend.OPENGL
    fun getSupportedFeatureLevel(): FeatureLevel = FeatureLevel.FEATURE_LEVEL_0
    fun setAutomaticInstancingEnabled(enabled: Boolean) {}
    fun hasUnrecoverableFailure(): Boolean = false
    fun createSwapChain(nativeWindow: Any): SwapChain = TODO()
    fun createSwapChain(nativeWindow: Any, flags: Long): SwapChain = TODO()
    fun createSwapChain(width: Int, height: Int, flags: Long): SwapChain = TODO()
    fun destroySwapChain(swapChain: SwapChain) {}
    fun isValidRenderer(renderer: Renderer): Boolean = true
    fun isValidView(view: View): Boolean = true
    fun isValidScene(scene: Scene): Boolean = true
    fun isValidIndexBuffer(indexBuffer: IndexBuffer): Boolean = true
    fun isValidVertexBuffer(vertexBuffer: VertexBuffer): Boolean = true
    fun isValidIndirectLight(light: IndirectLight): Boolean = true
    fun isValidMaterial(material: Material): Boolean = true
    fun isValidMaterialInstance(material: Material, instance: MaterialInstance): Boolean = true
    fun isValidSkybox(skybox: Skybox): Boolean = true
    fun isValidTexture(texture: Texture): Boolean = true
    fun isValidSwapChain(swapChain: SwapChain): Boolean = true
    fun createView(): View = TODO()
    fun destroyView(view: View) {}
    fun createRenderer(): Renderer = TODO()
    fun destroyRenderer(renderer: Renderer) {}
    fun createCamera(entity: Int): Camera = TODO()
    fun getCameraComponent(entity: Int): Camera = TODO()
    fun destroyCameraComponent(entity: Int) {}
    fun createScene(): Scene = TODO()
    fun destroyScene(scene: Scene) {}
    fun destroyIndexBuffer(indexBuffer: IndexBuffer) {}
    fun destroyVertexBuffer(vertexBuffer: VertexBuffer) {}
    fun destroyIndirectLight(indirectLight: IndirectLight) {}
    fun destroyMaterial(material: Material) {}
    fun destroyMaterialInstance(instance: MaterialInstance) {}
    fun destroySkybox(skybox: Skybox) {}
    fun destroyTexture(texture: Texture) {}
    fun destroyEntity(entity: Int) {}
    fun getTransformManager(): TransformManager = TODO()
    fun getLightManager(): LightManager = TODO()
    fun getRenderableManager(): RenderableManager = TODO()
    fun getEntityManager(): EntityManager = TODO()
    fun flushAndWait() {}
    fun isPaused(): Boolean = false
    fun setPaused(paused: Boolean) {}
    fun getSteadyClockTimeNano(): Long = 0L
    fun getNativeObject(): Long = 0L
}

class EntityManager {
    companion object {
        fun get(): EntityManager = TODO()
    }
    fun create(): Int = 0
    fun destroy(entity: Int) {}
    fun create(count: Int): IntArray = IntArray(0)
    fun destroy(entities: IntArray) {}
    fun isAlive(entity: Int): Boolean = true
    fun getEntityCount(): Int = 0
}

class Box {
    constructor()
    constructor(center: FloatArray, halfExtent: FloatArray)
    constructor(cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float)
    fun setCenter(x: Float, y: Float, z: Float) {}
    fun setHalfExtent(x: Float, y: Float, z: Float) {}
    fun getCenter(): FloatArray = FloatArray(3)
    fun getHalfExtent(): FloatArray = FloatArray(3)
}

class Viewport(val left: Int, val bottom: Int, val width: Int, val height: Int)

class Camera {
    enum class Projection { PERSPECTIVE, ORTHO }
    enum class Fov { VERTICAL, HORIZONTAL }
    fun setProjection(projection: Projection, left: Double, right: Double, bottom: Double, top: Double, near: Double, far: Double) {}
    fun setProjection(fovInDegrees: Double, aspect: Double, near: Double, far: Double, direction: Fov) {}
    fun setLensProjection(focalLength: Double, near: Double, far: Double, aspect: Double) {}
    fun setModelMatrix(inMatrix: FloatArray) {}
    fun setModelMatrix(inMatrix: DoubleArray) {}
    fun lookAt(ex: Double, ey: Double, ez: Double, tx: Double, ty: Double, tz: Double, ux: Double, uy: Double, uz: Double) {}
    fun getNear(): Float = 0f
    fun getCullingFar(): Float = 0f
    fun getPosition(out: FloatArray?): FloatArray = out ?: FloatArray(3)
    fun getLeftVector(out: FloatArray): FloatArray = out
    fun getUpVector(out: FloatArray): FloatArray = out
    fun getForwardVector(out: FloatArray): FloatArray = out
    fun getViewMatrix(out: FloatArray?): FloatArray = out ?: FloatArray(16)
    fun getProjectionMatrix(out: DoubleArray?): DoubleArray = out ?: DoubleArray(16)
    fun getModelMatrix(out: FloatArray?): FloatArray = out ?: FloatArray(16)
    fun setExposure(exposure: Float) {}
    fun getEntity(): Int = 0
}

class Scene {
    fun getSkybox(): Skybox? = null
    fun setSkybox(skybox: Skybox?) {}
    fun getIndirectLight(): IndirectLight? = null
    fun setIndirectLight(indirectLight: IndirectLight?) {}
    fun addEntity(entity: Int) {}
    fun addEntities(entities: IntArray) {}
    fun removeEntity(entity: Int) {}
    fun remove(entity: Int) {}
    fun getEntityCount(): Int = 0
    fun getRenderableCount(): Int = 0
    fun getLightCount(): Int = 0
    fun hasEntity(entity: Int): Boolean = false
    fun getEntities(): IntArray = IntArray(0)
}

class Skybox {
    class Builder {
        fun environment(environment: Texture): Builder = this
        fun showSun(show: Boolean): Builder = this
        fun intensity(intensity: Float): Builder = this
        fun color(r: Float, g: Float, b: Float, a: Float): Builder = this
        fun color(color: FloatArray): Builder = this
        fun priority(priority: Int): Builder = this
        fun build(engine: Engine): Skybox = TODO()
    }
    fun setColor(r: Float, g: Float, b: Float, a: Float) {}
    fun setColor(color: FloatArray) {}
    fun setLayerMask(select: Int, value: Int) {}
    fun getLayerMask(): Int = 0
    fun getIntensity(): Float = 0f
    fun getTexture(): Texture = TODO()
}

class IndirectLight {
    class Builder {
        fun reflections(texture: Texture): Builder = this
        fun irradiance(bands: Int, sh: FloatArray): Builder = this
        fun radiance(bands: Int, sh: FloatArray): Builder = this
        fun irradiance(texture: Texture): Builder = this
        fun intensity(intensity: Float): Builder = this
        fun rotation(matrix: FloatArray): Builder = this
        fun build(engine: Engine): IndirectLight = TODO()
    }
    fun setIntensity(intensity: Float) {}
    fun getIntensity(): Float = 0f
}

class VertexBuffer {
    enum class VertexAttribute {
        POSITION, TANGENTS, COLOR, UV0, UV1, BONE_INDICES, BONE_WEIGHTS, UNUSED,
        CUSTOM0, CUSTOM1, CUSTOM2, CUSTOM3, CUSTOM4, CUSTOM5, CUSTOM6, CUSTOM7
    }
    enum class AttributeType {
        BYTE, BYTE2, BYTE3, BYTE4, UBYTE, UBYTE2, UBYTE3, UBYTE4,
        SHORT, SHORT2, SHORT3, SHORT4, USHORT, USHORT2, USHORT3, USHORT4,
        INT, UINT, FLOAT, FLOAT2, FLOAT3, FLOAT4, HALF, HALF2, HALF3, HALF4
    }
    class Builder {
        fun vertexCount(vertexCount: Int): Builder = this
        fun enableBufferObjects(enabled: Boolean): Builder = this
        fun bufferCount(bufferCount: Int): Builder = this
        fun attribute(attribute: VertexAttribute, bufferIndex: Int, attributeType: AttributeType, byteOffset: Int, byteStride: Int): Builder = this
        fun attribute(attribute: VertexAttribute, bufferIndex: Int, attributeType: AttributeType): Builder = this
        fun normalized(attribute: VertexAttribute): Builder = this
        fun normalized(attribute: VertexAttribute, normalized: Boolean): Builder = this
        fun build(engine: Engine): VertexBuffer = TODO()
    }
    fun getVertexCount(): Int = 0
    fun getNativeObject(): Long = 0L
    fun setBufferAt(engine: Engine, bufferIndex: Int, buffer: Buffer) {}
    fun setBufferAt(engine: Engine, bufferIndex: Int, buffer: Buffer, destOffsetInBytes: Int, count: Int) {}
}

class IndexBuffer {
    class Builder {
        enum class IndexType { USHORT, UINT }
        fun indexCount(indexCount: Int): Builder = this
        fun bufferType(indexType: IndexType): Builder = this
        fun build(engine: Engine): IndexBuffer = TODO()
    }
    fun getIndexCount(): Int = 0
    fun getNativeObject(): Long = 0L
    fun setBuffer(engine: Engine, buffer: Buffer) {}
    fun setBuffer(engine: Engine, buffer: Buffer, destOffsetInBytes: Int, count: Int) {}
}

class RenderableManager {
    enum class PrimitiveType { POINTS, LINES, LINE_STRIP, TRIANGLES, TRIANGLE_STRIP }
    class Builder(primitiveCount: Int) {
        enum class GeometryType { DYNAMIC, STATIC_BOUNDS, STATIC }
        fun geometryType(type: GeometryType): Builder = this
        fun geometry(index: Int, type: PrimitiveType, vertices: VertexBuffer, indices: IndexBuffer, offset: Int, count: Int): Builder = this
        fun geometry(index: Int, type: PrimitiveType, vertices: VertexBuffer, indices: IndexBuffer): Builder = this
        fun geometry(index: Int, type: PrimitiveType, vertices: VertexBuffer, offset: Int, count: Int): Builder = this
        fun material(index: Int, material: MaterialInstance): Builder = this
        fun blendOrder(index: Int, blendOrder: Int): Builder = this
        fun boundingBox(aabb: Box): Builder = this
        fun layerMask(select: Int, values: Int): Builder = this
        fun priority(priority: Int): Builder = this
        fun channel(channel: Int): Builder = this
        fun culling(enabled: Boolean): Builder = this
        fun lightChannel(channel: Int, enable: Boolean): Builder = this
        fun instances(count: Int): Builder = this
        fun castShadows(enabled: Boolean): Builder = this
        fun receiveShadows(enabled: Boolean): Builder = this
        fun screenSpaceContactShadows(enabled: Boolean): Builder = this
        fun fog(enabled: Boolean): Builder = this
        fun build(engine: Engine, entity: Int) {}
    }
    fun hasComponent(entity: Int): Boolean = false
    fun getInstance(entity: Int): Int = 0
    fun destroy(entity: Int) {}
    fun getPrimitiveCount(instance: Int): Int = 0
    fun getInstanceCount(instance: Int): Int = 0
    fun isCullingEnabled(instance: Int): Boolean = false
    fun getAxisAlignedBoundingBox(instance: Int, box: Box): Box = box
    fun setAxisAlignedBoundingBox(instance: Int, box: Box) {}
    fun getPriority(instance: Int): Int = 0
    fun getChannel(instance: Int): Int = 0
    fun getFogEnabled(instance: Int): Boolean = false
    fun getLightChannel(instance: Int, channel: Int): Boolean = false
    fun isShadowCaster(instance: Int): Boolean = false
    fun isShadowReceiver(instance: Int): Boolean = false
    fun isScreenSpaceContactShadowsEnabled(instance: Int): Boolean = false
    fun getBlendOrderAt(instance: Int, primitiveIndex: Int): Int = 0
    fun isGlobalBlendOrderEnabledAt(instance: Int, primitiveIndex: Int): Boolean = false
    fun setCulling(instance: Int, enabled: Boolean) {}
    fun setLayerMask(instance: Int, select: Int, values: Int) {}
    fun setGeometryAt(instance: Int, primitiveIndex: Int, type: PrimitiveType, vertices: VertexBuffer, indices: IndexBuffer, offset: Int, count: Int) {}
    fun setMaterialInstanceAt(instance: Int, primitiveIndex: Int, materialInstance: MaterialInstance?) {}
    fun hasComponent(entity: Int): Boolean = false
    fun getInstance(entity: Int): Int = 0
    fun destroy(entity: Int) {}
    fun setAxisAlignedBoundingBox(entity: Int, aabb: Box) {}
    fun setLayerMask(entity: Int, select: Int, value: Int) {}
    fun setPriority(entity: Int, priority: Int) {}
    fun setCulling(entity: Int, enabled: Boolean) {}
    fun setCastShadows(entity: Int, enabled: Boolean) {}
    fun setReceiveShadows(entity: Int, enabled: Boolean) {}
    fun setMaterialInstanceAt(entity: Int, primitiveIndex: Int, materialInstance: MaterialInstance?) {}
    fun clearMaterialInstanceAt(entity: Int, primitiveIndex: Int) {}
    fun getMaterialInstanceAt(entity: Int, primitiveIndex: Int): MaterialInstance = TODO()
    fun setGeometryAt(entity: Int, primitiveIndex: Int, type: PrimitiveType, vertices: VertexBuffer, indices: IndexBuffer, offset: Int, count: Int) {}
    fun getPrimitiveCount(entity: Int): Int = 0
}

class TransformManager {
    fun hasComponent(entity: Int): Boolean = false
    fun getInstance(entity: Int): Int = 0
    fun setAccurateTranslationsEnabled(enabled: Boolean) {}
    fun isAccurateTranslationsEnabled(): Boolean = false
    fun create(entity: Int): Int = 0
    fun create(entity: Int, parent: Int, localTransform: FloatArray): Int = 0
    fun destroy(entity: Int) {}
    fun setParent(instance: Int, parent: Int) {}
    fun getParent(instance: Int): Int = 0
    fun getChildCount(instance: Int): Int = 0
    fun getChildren(instance: Int, outEntities: IntArray?): IntArray = IntArray(0)
    fun setTransform(instance: Int, localTransform: FloatArray) {}
    fun getTransform(instance: Int, out: FloatArray): FloatArray = out
    fun getWorldTransform(instance: Int, out: FloatArray): FloatArray = out
    fun openLocalTransformTransaction() {}
    fun commitLocalTransformTransaction() {}
}

class LightManager {
    enum class Type { SUN, DIRECTIONAL, POINT, FOCUSED_SPOT, SPOT }
    class ShadowOptions {
        var mapSize: Int = 1024
        var shadowCascades: Int = 1
        var constantBias: Float = 0.001f
        var normalBias: Float = 1f
        var shadowFar: Float = 0f
        var stable: Boolean = false
    }
    class Builder(type: Type) {
        fun lightChannel(channel: Int, enable: Boolean): Builder = this
        fun castShadows(enabled: Boolean): Builder = this
        fun shadowOptions(options: ShadowOptions): Builder = this
        fun castLight(enabled: Boolean): Builder = this
        fun position(x: Float, y: Float, z: Float): Builder = this
        fun direction(x: Float, y: Float, z: Float): Builder = this
        fun color(r: Float, g: Float, b: Float): Builder = this
        fun intensity(intensity: Float): Builder = this
        fun intensity(watts: Float, efficiency: Float): Builder = this
        fun intensityCandela(candela: Float): Builder = this
        fun falloff(radius: Float): Builder = this
        fun spotLightCone(inner: Float, outer: Float): Builder = this
        fun sunAngularRadius(radius: Float): Builder = this
        fun sunHaloSize(size: Float): Builder = this
        fun sunHaloFalloff(falloff: Float): Builder = this
        fun build(engine: Engine, entity: Int) {}
    }
    fun hasComponent(entity: Int): Boolean = false
    fun getInstance(entity: Int): Int = 0
    fun destroy(entity: Int) {}
    fun getType(entity: Int): Type = Type.SUN
    fun setPosition(entity: Int, x: Float, y: Float, z: Float) {}
    fun setDirection(entity: Int, x: Float, y: Float, z: Float) {}
    fun setColor(entity: Int, r: Float, g: Float, b: Float) {}
    fun setIntensity(entity: Int, intensity: Float) {}
    fun getIntensity(entity: Int): Float = 0f
    fun setSunAngularRadius(entity: Int, radius: Float) {}
    fun setShadowCaster(entity: Int, enabled: Boolean) {}
    fun isShadowCaster(entity: Int): Boolean = false
}

class Material {
    enum class Shading { UNLIT, LIT, SUBSURFACE, CLOTH, SPECULAR_GLOSSINESS }
    enum class BlendingMode { OPAQUE, TRANSPARENT, ADD, MASKED, FADE, MULTIPLY, SCREEN }
    enum class CullingMode { NONE, FRONT, BACK, FRONT_AND_BACK }
    enum class VertexDomain { OBJECT, WORLD, VIEW, DEVICE }
    enum class TransparencyMode { DEFAULT, TWO_PASSES_ONE_SIDE, TWO_PASSES_TWO_SIDES }
    enum class Interpolation { SMOOTH, FLAT }
    enum class CompilerPriorityQueue { CRITICAL, HIGH, LOW }
    class Builder {
        fun payload(payload: Buffer, size: Int): Builder = this
        fun sphericalHarmonicsBandCount(bands: Int): Builder = this
        fun build(engine: Engine): Material = TODO()
    }
    fun createInstance(): MaterialInstance = TODO()
    fun createInstance(name: String): MaterialInstance = TODO()
    fun getDefaultInstance(): MaterialInstance = TODO()
    fun getName(): String = ""
    fun getShading(): Shading = Shading.LIT
    fun getBlendingMode(): BlendingMode = BlendingMode.OPAQUE
    fun getRequiredAttributes(): MutableSet<VertexBuffer.VertexAttribute> = HashSet()
    fun hasParameter(name: String): Boolean = false
    fun isDoubleSided(): Boolean = false
    fun setDefaultParameter(name: String, x: Float) {}
    fun setDefaultParameter(name: String, x: Float, y: Float) {}
    fun setDefaultParameter(name: String, x: Float, y: Float, z: Float) {}
    fun setDefaultParameter(name: String, x: Float, y: Float, z: Float, w: Float) {}
    fun setDefaultParameter(name: String, texture: Texture, sampler: TextureSampler) {}
}

class MaterialInstance {
    fun getMaterial(): Material = TODO()
    fun getName(): String = ""
    fun getNativeObject(): Long = 0L
    fun setParameter(name: String, x: Boolean) {}
    fun setParameter(name: String, x: Float) {}
    fun setParameter(name: String, x: Int) {}
    fun setParameter(name: String, x: Float, y: Float) {}
    fun setParameter(name: String, x: Float, y: Float, z: Float) {}
    fun setParameter(name: String, x: Float, y: Float, z: Float, w: Float) {}
    fun setParameter(name: String, texture: Texture, sampler: TextureSampler) {}
    fun setParameter(name: String, x: Int, y: Int, z: Int, w: Int) {}
    fun setConstant(name: String, x: Float) {}
    fun setScissor(left: Int, bottom: Int, width: Int, height: Int) {}
    fun unsetScissor() {}
    fun setPolygonOffset(scale: Float, distance: Float) {}
    fun setMaskThreshold(threshold: Float) {}
    fun setDoubleSided(doubleSided: Boolean) {}
    fun setCullingMode(mode: Material.CullingMode) {}
    fun setDepthWrite(enabled: Boolean) {}
    fun setDepthCulling(enabled: Boolean) {}
    fun setColorWrite(enabled: Boolean) {}
}

class Texture {
    enum class Sampler { SAMPLER_2D, SAMPLER_2D_ARRAY, SAMPLER_CUBEMAP, SAMPLER_EXTERNAL, SAMPLER_3D }
    enum class Format {
        R, R_INTEGER, RG, RG_INTEGER, RGB, RGB_INTEGER, RGBA, RGBA_INTEGER, UNUSED,
        DEPTH_COMPONENT, DEPTH_STENCIL, STENCIL_INDEX, ALPHA
    }
    enum class Type { UBYTE, BYTE, USHORT, SHORT, UINT, INT, HALF, FLOAT, COMPRESSED, UINT_10F_11F_11F_REV, USHORT_565 }
    enum class CubemapFace { POSITIVE_X, NEGATIVE_X, POSITIVE_Y, NEGATIVE_Y, POSITIVE_Z, NEGATIVE_Z }
    enum class InternalFormat {
        R8, R8_SNORM, R8UI, R8I, STENCIL8, R16F, R16UI, R16I, RG8, RG8_SNORM, RG8UI, RG8I,
        RGB565, RGB9_E5, RGB5_A1, RGBA4, DEPTH16, RGB8, SRGB8, RGB8_SNORM, RGB8UI, RGB8I,
        DEPTH24, R32F, R32UI, R32I, RG16F, RG16UI, RG16I, R11F_G11F_B10F, RGBA8,
        SRGB8_A8, RGBA8_SNORM, UNUSED, RGB10_A2, RGBA8UI, RGBA8I, DEPTH32F,
        DEPTH24_STENCIL8, DEPTH32F_STENCIL8, RGB16F, RGB16UI, RGB16I, RG32F, RG32UI, RG32I,
        RGBA16F, RGBA16UI, RGBA16I, RGB32F, RGB32UI, RGB32I, RGBA32F, RGBA32UI, RGBA32I
    }
    class Builder {
        fun width(width: Int): Builder = this
        fun height(height: Int): Builder = this
        fun depth(depth: Int): Builder = this
        fun levels(levels: Int): Builder = this
        fun sampler(target: Sampler): Builder = this
        fun samples(samples: Int): Builder = this
        fun format(format: InternalFormat): Builder = this
        fun usage(usage: Int): Builder = this
        fun build(engine: Engine): Texture = TODO()
    }
    class PixelBufferDescriptor {
        constructor(buffer: Buffer, format: Format, type: Type)
        constructor(buffer: Buffer, format: Format, type: Type, alignment: Int)
        constructor(buffer: Buffer, format: Format, type: Type, left: Int, top: Int, stride: Int)
    }
    class Usage {
        companion object {
            const val COLOR_ATTACHMENT = 0x1
            const val DEPTH_ATTACHMENT = 0x2
            const val STENCIL_ATTACHMENT = 0x4
            const val UPLOADABLE = 0x8
            const val SAMPLEABLE = 0x10
            const val SUBPASS_INPUT = 0x20
            const val BLIT_SRC = 0x40
            const val BLIT_DST = 0x80
            const val PROTECTED = 0x0100
            const val GEN_MIPMAPPABLE = 0x0200
            const val DEFAULT = UPLOADABLE or SAMPLEABLE
        }
    }
    companion object {
        const val BASE_LEVEL = 0
        fun isTextureFormatMipmappable(engine: Engine, format: InternalFormat): Boolean = true
        fun isTextureFormatSupported(engine: Engine, format: InternalFormat): Boolean = true
        fun getMaxTextureSize(engine: Engine, sampler: Sampler): Int = 4096
    }
    fun getWidth(level: Int): Int = 0
    fun getHeight(level: Int): Int = 0
    fun getDepth(level: Int): Int = 0
    fun getLevels(): Int = 1
    fun getTarget(): Sampler = Sampler.SAMPLER_2D
    fun getFormat(): InternalFormat = InternalFormat.RGBA8
    fun setImage(engine: Engine, level: Int, descriptor: PixelBufferDescriptor) {}
    fun setImage(engine: Engine, level: Int, xoffset: Int, yoffset: Int, width: Int, height: Int, descriptor: PixelBufferDescriptor) {}
    fun generateMipmaps(engine: Engine) {}
}

class TextureSampler {
    enum class MinFilter { NEAREST, LINEAR, NEAREST_MIPMAP_NEAREST, LINEAR_MIPMAP_NEAREST, NEAREST_MIPMAP_LINEAR, LINEAR_MIPMAP_LINEAR }
    enum class MagFilter { NEAREST, LINEAR }
    enum class WrapMode { CLAMP_TO_EDGE, REPEAT, MIRRORED_REPEAT }
    enum class CompareMode { NONE, COMPARE_TO_TEXTURE }
    enum class CompareFunction { LESS_EQUAL, GREATER_EQUAL, LESS, GREATER, EQUAL, NOT_EQUAL, ALWAYS, NEVER }
    constructor()
    constructor(minFilter: MinFilter, magFilter: MagFilter, wrapMode: WrapMode)
    constructor(minFilter: MinFilter, magFilter: MagFilter, wrapS: WrapMode, wrapT: WrapMode, wrapR: WrapMode)
    fun setMinFilter(filter: MinFilter) {}
    fun setMagFilter(filter: MagFilter) {}
    fun setWrapModeS(mode: WrapMode) {}
    fun setWrapModeT(mode: WrapMode) {}
    fun setWrapModeR(mode: WrapMode) {}
    fun setAnisotropy(anisotropy: Float) {}
}

class SwapChain {
    enum class FrameRateCompatibility { DEFAULT, EXACT, BOOST }
    enum class ChangeFrameRateStrategy { DEFAULT, ONLY_IF_SEAMLESS }
    companion object {
        fun isProtectedContentSupported(engine: Engine): Boolean = false
        fun isSRGBSwapChainSupported(engine: Engine): Boolean = true
        fun isMSAASwapChainSupported(engine: Engine, samples: Int): Boolean = true
    }
    fun isFrameRateChangeSupported(): Boolean = false
    fun setFrameRate(frameRate: Float) {}
    fun setFrameRate(frameRate: Float, compatibility: FrameRateCompatibility, strategy: ChangeFrameRateStrategy) {}
    fun getNativeWindow(): Any = TODO()
    fun getNativeObject(): Long = 0L
}

class Renderer {
    class FrameInfo {
        var frameId: Int = 0
        var gpuFrameDuration: Long = 0
        var denoisedGpuFrameDuration: Long = 0
        var beginFrame: Long = 0
        var endFrame: Long = 0
        companion object {
            const val INVALID = 0x7FFFFFFFFFFFFFFFL
            const val PENDING = 0x7FFFFFFFFFFFFFFEL
        }
    }
    class ClearOptions {
        var clearColor: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        var clear: Boolean = false
        var discard: Boolean = true
    }
    class DisplayInfo {
        var refreshRate: Float = 60f
        var presentationDeadlineNanos: Long = 0
        var vsyncOffsetNanos: Long = 0
    }
    fun setDisplayInfo(info: DisplayInfo) {}
    fun getDisplayInfo(): DisplayInfo = TODO()
    fun getFrameInfoHistory(frameInfoHistory: Array<FrameInfo>): Int = 0
    fun getMaxFrameHistorySize(): Int = 0
    fun setClearOptions(options: ClearOptions) {}
    fun getClearOptions(): ClearOptions = TODO()
    fun getEngine(): Engine = TODO()
    fun shouldRenderFrame(): Boolean = true
    fun beginFrame(swapChain: SwapChain, frameTimeNanos: Long): Boolean = true
    fun endFrame() {}
    fun render(view: View) {}
    fun renderStandaloneView(view: View) {}
    fun readPixels(xoffset: Int, yoffset: Int, width: Int, height: Int, descriptor: Texture.PixelBufferDescriptor) {}
    fun getFrameToSkipCount(): Int = 0
}

class View {
    enum class ShadowType { PCF, VSM, DPCF, PCSS, PCFd }
    enum class ToneMapping { LINEAR, ACES }
    enum class AntiAliasing { NONE, FXAA }
    enum class BlendMode { OPAQUE, TRANSLUCENT }
    enum class Dithering { NONE, TEMPORAL }
    enum class AmbientOcclusion { NONE, SSAO }
    class DynamicResolutionOptions {
        var minScale: Float = 0.5f
        var maxScale: Float = 1f
        var sharpness: Float = 0.9f
        var enabled: Boolean = false
        var homogeneousScaling: Boolean = false
    }
    fun setName(name: String) {}
    fun getName(): String = ""
    fun setScene(scene: Scene) {}
    fun getScene(): Scene? = null
    fun setCamera(camera: Camera) {}
    fun hasCamera(): Boolean = false
    fun getCamera(): Camera = TODO()
    fun setViewport(viewport: Viewport) {}
    fun getViewport(): Viewport = Viewport(0, 0, 0, 0)
    fun setBlendMode(mode: BlendMode) {}
    fun setVisibleLayers(select: Int, value: Int) {}
    fun getVisibleLayers(): Int = 0
    fun setShadowingEnabled(enabled: Boolean) {}
    fun setFrustumCullingEnabled(enabled: Boolean) {}
    fun isFrustumCullingEnabled(): Boolean = false
    fun setSampleCount(count: Int) {}
    fun getSampleCount(): Int = 0
    fun setAntiAliasing(antiAliasing: AntiAliasing) {}
    fun getAntiAliasing(): AntiAliasing = AntiAliasing.NONE
    fun setToneMapping(mapping: ToneMapping) {}
    fun getToneMapping(): ToneMapping = ToneMapping.ACES
    fun setDithering(dithering: Dithering) {}
    fun setDynamicResolutionOptions(options: DynamicResolutionOptions) {}
    fun getDynamicResolutionOptions(): DynamicResolutionOptions = DynamicResolutionOptions()
    fun setPostProcessingEnabled(enabled: Boolean) {}
    fun isPostProcessingEnabled(): Boolean = false
    fun setFrontFaceWindingInverted(inverted: Boolean) {}
    fun setShadowType(type: ShadowType) {}
    fun setAmbientOcclusion(ao: AmbientOcclusion) {}
    fun setFogOptions(options: Any) {}
    fun getVisibleRenderableCount(): Int = 0
    fun getNativeObject(): Long = 0L
}

/**
 * Headless helper used to derive per-vertex tangent frames (the TANGENTS
 * quaternion Filament expects) from positions/normals/uvs/indices.
 */
class SurfaceOrientation {
    class Builder {
        fun vertexCount(count: Int): Builder = this
        fun positions(buffer: Buffer): Builder = this
        fun normals(buffer: Buffer): Builder = this
        fun uvs(buffer: Buffer): Builder = this
        fun tangents(buffer: Buffer): Builder = this
        fun triangleCount(count: Int): Builder = this
        fun triangles_uint16(buffer: Buffer): Builder = this
        fun triangles_uint32(buffer: Buffer): Builder = this
        fun build(): SurfaceOrientation = TODO()
    }
    fun getVertexCount(): Int = 0
    fun getQuatsAsFloat(buffer: Buffer) {}
    fun getQuatsAsHalf(buffer: Buffer) {}
    fun getQuatsAsShort(buffer: Buffer) {}
    fun destroy() {}
}
