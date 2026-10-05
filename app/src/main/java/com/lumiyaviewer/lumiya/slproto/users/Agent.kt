package com.lumiyaviewer.lumiya.slproto.users

data class Agent(
    val uuid: String,
    val displayName: String,
    val gridName: String,
    var regionName: String = "Unknown",
    var online: Boolean = false
)
