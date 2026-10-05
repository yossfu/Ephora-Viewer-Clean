package com.lumiyaviewer.lumiya.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lumiyaviewer.lumiya.databinding.ItemChatMessageBinding
import com.lumiyaviewer.lumiya.slproto.chat.ChatMessage
import com.lumiyaviewer.lumiya.slproto.chat.ChatSource

class ChatMessageAdapter : RecyclerView.Adapter<ChatMessageAdapter.MessageHolder>() {

    private val items = ArrayList<ChatMessage>()

    /** Tapping an instant message picks its sender as the reply target. */
    var onReply: ((ChatMessage) -> Unit)? = null

    class MessageHolder(val binding: ItemChatMessageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageHolder {
        val binding = ItemChatMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MessageHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageHolder, position: Int) {
        val message = items[position]
        holder.binding.senderEl.text = if (message.source == ChatSource.IM) {
            "[IM] " + message.senderName
        } else {
            message.senderName
        }
        holder.binding.messageEl.text = message.text
        if (message.source == ChatSource.IM && message.agentId.isNotEmpty()) {
            holder.itemView.setOnClickListener { onReply?.invoke(message) }
        } else {
            holder.itemView.setOnClickListener(null)
        }
    }

    override fun getItemCount(): Int = items.size

    fun submit(messages: List<ChatMessage>) {
        items.clear()
        items.addAll(messages)
        notifyDataSetChanged()
    }
}
