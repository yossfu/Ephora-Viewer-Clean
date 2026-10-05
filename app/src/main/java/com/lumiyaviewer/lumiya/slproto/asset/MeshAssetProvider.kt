package com.lumiyaviewer.lumiya.slproto.asset

import com.lumiyaviewer.lumiya.slproto.caps.GetMeshTransport
import com.lumiyaviewer.lumiya.slproto.caps.RangeBody

class MeshAssetProvider(
    private val transport: GetMeshTransport
) {

    val isReady: Boolean get() = transport.isReady

    fun capName(): String = transport.capName()

    fun fetchHeader(meshId: String): RangeBody? = transport.fetchHeader(meshId)

    fun fetchRange(meshId: String, offset: Long, length: Long): RangeBody? =
        transport.fetchRange(meshId, offset, length)

    fun describe(): String {
        val base = transport.meshBase()
        return if (base.isNullOrEmpty()) {
            "mallas: sin capability (ni ViewerAsset ni GetMesh2 ni GetMesh)"
        } else {
            "mallas: capability " + transport.capName()
        }
    }
}
