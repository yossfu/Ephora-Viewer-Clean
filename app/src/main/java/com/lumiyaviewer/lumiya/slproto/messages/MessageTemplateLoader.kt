package com.lumiyaviewer.lumiya.slproto.messages

import android.content.Context

object MessageTemplateLoader {

    const val ASSET_NAME = "message_template.msg"

    fun load(context: Context): Int {
        val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        MessageTemplate.parse(text)
        return MessageTemplate.loadedCount
    }
}
