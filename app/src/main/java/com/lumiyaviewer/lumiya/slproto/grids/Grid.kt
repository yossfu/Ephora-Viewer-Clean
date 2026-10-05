package com.lumiyaviewer.lumiya.slproto.grids

data class Grid(
    val id: String,
    val name: String,
    val loginUri: String,
    val isDefault: Boolean = false,
    val openSim: Boolean = false
)
