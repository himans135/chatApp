package com.share.chatapp.ui.chat

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel

class ChatViewModel : ViewModel() {
    // This is where we store our chats dynamically
    val chats = mutableStateListOf<ChatSummary>()

    // This stores messages for each chat (key is chatId or contactName)
    private val _messages = mutableMapOf<String, MutableList<Message>>()

    init {
        // We can still add some initial "dummy" chats for testing, 
        // but now they are part of a manageable system.
        chats.addAll(listOf(
            ChatSummary("1", "John Doe", "Hey, how are you?", "10:30 AM", 2, status = "Online"),
            ChatSummary("2", "Jane Smith", "See you tomorrow!", "Yesterday", 0, isTyping = true, status = "typing...")
        ))
    }

    fun getMessages(contactName: String): List<Message> {
        return _messages.getOrPut(contactName) {
            mutableStateListOf(
                Message("1", "Hello! This is the start of your chat with $contactName", false, "10:00 AM")
            )
        }
    }

    fun sendMessage(contactName: String, text: String) {
        val chatMessages = _messages.getOrPut(contactName) { mutableStateListOf() }
        val newMessage = Message(
            id = System.currentTimeMillis().toString(),
            text = text,
            isFromMe = true,
            time = "12:00 PM", // In a real app, use actual time
            status = MessageStatus.SENT
        )
        chatMessages.add(newMessage)
        
        // Update the last message in the chat list
        val index = chats.indexOfFirst { it.name == contactName }
        if (index != -1) {
            chats[index] = chats[index].copy(
                lastMessage = text,
                time = "Now",
                unreadCount = 0
            )
        } else {
            // If it's a new chat from contact list, add it to the top
            chats.add(0, ChatSummary(
                id = System.currentTimeMillis().toString(),
                name = contactName,
                lastMessage = text,
                time = "Now",
                unreadCount = 0
            ))
        }
    }

    fun deleteChat(chatId: String) {
        chats.removeIf { it.id == chatId }
    }

    fun markAsRead(contactName: String) {
        val index = chats.indexOfFirst { it.name == contactName }
        if (index != -1) {
            chats[index] = chats[index].copy(unreadCount = 0)
        }
    }
}
