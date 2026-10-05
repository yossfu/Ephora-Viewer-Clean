package com.google.android.filament.filamat

import java.nio.ByteBuffer

class MaterialPackage {
    fun getBuffer(): ByteBuffer = ByteBuffer.allocate(0)
    fun isValid(): Boolean = true
}

class MaterialBuilder {
    enum class Platform { DESKTOP, MOBILE, ALL }
    enum class TargetApi { OPENGL, VULKAN, METAL, WEBGPU, ALL }
    enum class Optimization { NONE, PREPROCESSOR, SIZE, PERFORMANCE }
    enum class Shading { UNLIT, LIT, SUBSURFACE, CLOTH, SPECULAR_GLOSSINESS }
    enum class MaterialDomain { SURFACE, POST_PROCESS }
    enum class BlendingMode { OPAQUE, TRANSPARENT, ADD, MASKED, FADE, MULTIPLY, SCREEN }
    enum class CullingMode { NONE, FRONT, BACK, FRONT_AND_BACK }
    enum class VertexDomain { OBJECT, WORLD, VIEW, DEVICE }
    enum class Interpolation { SMOOTH, FLAT }
    enum class RefractionMode { NONE, CUBEMAP, SCREEN_SPACE }
    enum class RefractionType { SOLID, THIN }
    enum class ReflectionMode { DEFAULT, SCREEN_SPACE }
    enum class TransparencyMode { DEFAULT, TWO_PASSES_ONE_SIDE, TWO_PASSES_TWO_SIDES }
    enum class SpecularAmbientOcclusion { NONE, SIMPLE, BENT_NORMALS }
    enum class UniformType {
        BOOL, BOOL2, BOOL3, BOOL4, FLOAT, FLOAT2, FLOAT3, FLOAT4,
        INT, INT2, INT3, INT4, UINT, UINT2, UINT3, UINT4, MAT3, MAT4
    }
    enum class SamplerType { SAMPLER_2D, SAMPLER_2D_ARRAY, SAMPLER_CUBEMAP, SAMPLER_EXTERNAL, SAMPLER_3D }
    enum class SamplerFormat { INT, UINT, FLOAT, SHADOW }
    enum class ParameterPrecision { LOW, MEDIUM, HIGH, DEFAULT }
    enum class VertexAttribute {
        POSITION, TANGENTS, COLOR, UV0, UV1, BONE_INDICES, BONE_WEIGHTS, UNUSED,
        CUSTOM0, CUSTOM1, CUSTOM2, CUSTOM3, CUSTOM4, CUSTOM5, CUSTOM6, CUSTOM7
    }
    enum class Variable { CUSTOM0, CUSTOM1, CUSTOM2, CUSTOM3 }

    companion object {
        fun init() {}
        fun shutdown() {}
    }

    fun name(name: String): MaterialBuilder = this
    fun materialDomain(domain: MaterialDomain): MaterialBuilder = this
    fun shading(shading: Shading): MaterialBuilder = this
    fun interpolation(interpolation: Interpolation): MaterialBuilder = this
    fun uniformParameter(type: UniformType, name: String): MaterialBuilder = this
    fun uniformParameter(type: UniformType, precision: ParameterPrecision, name: String): MaterialBuilder = this
    fun uniformParameterArray(type: UniformType, size: Int, name: String): MaterialBuilder = this
    fun samplerParameter(type: SamplerType, format: SamplerFormat, precision: ParameterPrecision, name: String): MaterialBuilder = this
    fun variable(variable: Variable, name: String): MaterialBuilder = this
    fun require(attribute: VertexAttribute): MaterialBuilder = this
    fun material(code: String): MaterialBuilder = this
    fun materialVertex(code: String): MaterialBuilder = this
    fun blending(mode: BlendingMode): MaterialBuilder = this
    fun postLightingBlending(mode: BlendingMode): MaterialBuilder = this
    fun vertexDomain(domain: VertexDomain): MaterialBuilder = this
    fun culling(mode: CullingMode): MaterialBuilder = this
    fun colorWrite(enabled: Boolean): MaterialBuilder = this
    fun depthWrite(enabled: Boolean): MaterialBuilder = this
    fun depthCulling(enabled: Boolean): MaterialBuilder = this
    fun doubleSided(doubleSided: Boolean): MaterialBuilder = this
    fun maskThreshold(threshold: Float): MaterialBuilder = this
    fun alphaToCoverage(enabled: Boolean): MaterialBuilder = this
    fun shadowMultiplier(enabled: Boolean): MaterialBuilder = this
    fun transparentShadow(enabled: Boolean): MaterialBuilder = this
    fun coloredPenumbra(enabled: Boolean): MaterialBuilder = this
    fun specularAntiAliasing(enabled: Boolean): MaterialBuilder = this
    fun specularAntiAliasingVariance(variance: Float): MaterialBuilder = this
    fun specularAntiAliasingThreshold(threshold: Float): MaterialBuilder = this
    fun refractionMode(mode: RefractionMode): MaterialBuilder = this
    fun reflectionMode(mode: ReflectionMode): MaterialBuilder = this
    fun refractionType(type: RefractionType): MaterialBuilder = this
    fun clearCoatIorChange(enabled: Boolean): MaterialBuilder = this
    fun flipUV(enabled: Boolean): MaterialBuilder = this
    fun customSurfaceShading(enabled: Boolean): MaterialBuilder = this
    fun multiBounceAmbientOcclusion(enabled: Boolean): MaterialBuilder = this
    fun specularAmbientOcclusion(mode: SpecularAmbientOcclusion): MaterialBuilder = this
    fun transparencyMode(mode: TransparencyMode): MaterialBuilder = this
    fun platform(platform: Platform): MaterialBuilder = this
    fun targetApi(api: TargetApi): MaterialBuilder = this
    fun optimization(optimization: Optimization): MaterialBuilder = this
    fun variantFilter(variantFilter: Int): MaterialBuilder = this
    fun build(): MaterialPackage = MaterialPackage()
}
