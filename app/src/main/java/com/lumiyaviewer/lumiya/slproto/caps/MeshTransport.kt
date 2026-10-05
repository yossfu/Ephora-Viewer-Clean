package com.lumiyaviewer.lumiya.slproto.caps

import java.net.URL

class GetMeshTransport(
    private val capabilities: Capabilities
) {

    val isReady: Boolean get() = meshBase() != null

    fun meshBase(): String? =
        capabilities.url(CAP_VIEWER_ASSET)
            ?: capabilities.url(CAP_GET_MESH2)
            ?: capabilities.url(CAP_GET_MESH)

    fun capName(): String = when {
        capabilities.url(CAP_VIEWER_ASSET) != null -> CAP_VIEWER_ASSET
        capabilities.url(CAP_GET_MESH2) != null -> CAP_GET_MESH2
        else -> CAP_GET_MESH
    }

    fun meshUrlFor(meshId: String): String? {
        val base = meshBase() ?: return null
        return meshUrlForBase(base, meshId)
    }

    fun fetchHeader(meshId: String, length: Int = MESH_HEADER_BYTES): RangeBody? {
        val target = meshUrlFor(meshId) ?: return null
        return capabilities.requestRange(capName(), target, 0L, length.toLong())
    }

    fun fetchRange(meshId: String, offset: Long, length: Long): RangeBody? {
        val target = meshUrlFor(meshId) ?: return null
        return capabilities.requestRange(capName(), target, offset, length)
    }

    companion object {
        const val CAP_VIEWER_ASSET = "ViewerAsset"
        const val CAP_GET_MESH2 = "GetMesh2"
        const val CAP_GET_MESH = "GetMesh"

        const val MESH_HEADER_BYTES = 4096

        fun meshUrlForBase(base: String, meshId: String): String {
            val trimmed = base.trim()
            return when {
                trimmed.contains('?') -> trimmed + "&mesh_id=" + meshId
                trimmed.endsWith("/") -> trimmed + "?mesh_id=" + meshId
                else -> trimmed + "/?mesh_id=" + meshId
            }
        }

        fun isPlausibleBase(base: String): Boolean {
            return try {
                val parsed = URL(base.trim())
                (parsed.protocol == "http" || parsed.protocol == "https") && !parsed.host.isNullOrEmpty()
            } catch (t: Throwable) {
                false
            }
        }
    }
}
