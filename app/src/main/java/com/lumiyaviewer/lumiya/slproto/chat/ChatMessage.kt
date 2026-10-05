package com.lumiyaviewer.lumiya.slproto.chat

enum class ChatSource { LOCAL, IM, GROUP, SYSTEM }

data class ChatMessage(
    val id: Long,
    val senderName: String,
    val text: String,
    val source: ChatSource,
    val timestampMillis: Long = System.currentTimeMillis(),
    /** Who sent an instant message: needed to reply to it. */
    val agentId: String = "",
    /** The IM session this message belongs to, when it is an instant message. */
    val sessionId: String = ""
)
