package com.lumiyaviewer.lumiya.ui.chat

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lumiyaviewer.lumiya.databinding.ActivityChatBinding
import com.lumiyaviewer.lumiya.slproto.SLClient
import com.lumiyaviewer.lumiya.slproto.modules.ConnectionState
import com.lumiyaviewer.lumiya.ui.common.BaseActivity
import kotlinx.coroutines.launch

class ChatNewActivity : BaseActivity() {

    private lateinit var binding: ActivityChatBinding
    private val adapter = ChatMessageAdapter()

    /** Set when the next message should go to one person instead of the region. */
    private var imTargetId = ""
    private var imTargetName = ""
    private var typingAgent = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.chatList.layoutManager = LinearLayoutManager(this)
        binding.chatList.adapter = adapter
        binding.sendBtn.setOnClickListener { sendMessage() }
        binding.imTargetEl.setOnClickListener { setImTarget("", "") }
        adapter.onReply = { message -> setImTarget(message.agentId, message.senderName) }

        lifecycleScope.launch {
            SLClient.connection.messages.collect { messages ->
                adapter.submit(messages)
                if (messages.isNotEmpty()) {
                    binding.chatList.scrollToPosition(messages.size - 1)
                }
            }
        }
        lifecycleScope.launch {
            SLClient.connection.session.collect { session ->
                typingAgent = session.typingAgent
                updateImRow()
            }
        }
    }

    private fun setImTarget(agentId: String, name: String) {
        imTargetId = agentId
        imTargetName = name
        updateImRow()
    }

    private fun updateImRow() {
        val text = when {
            imTargetId.isNotEmpty() -> "IM a " + imTargetName + " \u00B7 toca aqui para volver al chat local"
            typingAgent.isNotEmpty() -> typingAgent + " esta escribiendo..."
            else -> ""
        }
        binding.imTargetEl.text = text
        binding.imTargetEl.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun sendMessage() {
        val text = binding.messageInput.text.toString().trim()
        if (text.isEmpty()) {
            return
        }
        if (SLClient.connection.state.value != ConnectionState.CONNECTED) {
            return
        }
        binding.messageInput.setText("")
        if (imTargetId.isNotEmpty()) {
            SLClient.connection.sendInstantMessage(imTargetId, imTargetName, text)
        } else {
            SLClient.connection.sendChat(text)
        }
    }
}
