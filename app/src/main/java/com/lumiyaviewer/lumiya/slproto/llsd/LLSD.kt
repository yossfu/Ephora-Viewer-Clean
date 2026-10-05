package com.lumiyaviewer.lumiya.slproto.llsd

sealed class LLSD {

    object Undef : LLSD()

    data class Bool(val value: Boolean) : LLSD()

    data class Int32(val value: Int) : LLSD()

    data class Real(val value: Double) : LLSD()

    data class Str(val value: String) : LLSD()

    data class Bin(val value: ByteArray) : LLSD()

    data class Arr(val value: List<LLSD>) : LLSD()

    data class Map(val value: kotlin.collections.Map<String, LLSD>) : LLSD()
}
