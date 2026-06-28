package com.share.chatapp.ui.chat

import android.util.Log
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import java.text.SimpleDateFormat
import java.util.*

class ChatViewModel : ViewModel() {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val TAG = "ChatAppDebug"
    
    val chats = mutableStateListOf<ChatSummary>()
    var currentContactStatus by mutableStateOf("Offline")
    
    private val userListeners = mutableMapOf<String, ListenerRegistration>()
    private val phoneToNameMap = mutableStateMapOf<String, String>()
    private var currentActiveChatId: String? = null

    init {
        observeChats()
        updatePresence(true)
    }

    fun setActiveChat(phoneNumber: String?) {
        currentActiveChatId = phoneNumber?.let { getChatId(auth.currentUser?.phoneNumber ?: "", it) }
        // If we just entered a chat, mark existing messages as read
        currentActiveChatId?.let { chatId ->
            db.collection("chats").document(chatId).collection("messages")
                .whereNotEqualTo("sender", auth.currentUser?.phoneNumber)
                .whereNotEqualTo("status", MessageStatus.READ.name)
                .get().addOnSuccessListener { snapshot ->
                    snapshot.documents.forEach { it.reference.update("status", MessageStatus.READ.name) }
                }
        }
    }

    fun setContactNames(names: Map<String, String>) {
        phoneToNameMap.putAll(names)
        chats.forEachIndexed { index, chat ->
            phoneToNameMap[chat.phoneNumber]?.let { if (chat.name != it) chats[index] = chat.copy(name = it) }
        }
    }

    private fun observeChats() {
        val currentUser = auth.currentUser?.phoneNumber ?: return
        db.collection("chats").whereArrayContains("participants", currentUser)
            .addSnapshotListener { value, error ->
                if (error != null) return@addSnapshotListener
                
                value?.documentChanges?.forEach { change ->
                    val doc = change.document
                    val participants = doc.get("participants") as? List<String> ?: emptyList()
                    val otherPhone = participants.find { it != currentUser } ?: currentUser
                    
                    if (change.type != DocumentChange.Type.REMOVED) {
                        // Mark as DELIVERED if received while app is open
                        markMessagesAsDelivered(doc.id, currentUser)
                        
                        // Calculate unread count
                        db.collection("chats").document(doc.id).collection("messages")
                            .whereNotEqualTo("sender", currentUser)
                            .whereNotEqualTo("status", MessageStatus.READ.name)
                            .addSnapshotListener { msgValue, _ ->
                                val unreadCount = msgValue?.size() ?: 0
                                updateChatSummary(doc, otherPhone, unreadCount)
                            }
                    } else {
                        chats.removeIf { it.id == doc.id }
                    }
                    startUserStatusListener(otherPhone, currentUser)
                }
            }
    }

    private fun updateChatSummary(doc: DocumentSnapshot, otherPhone: String, unread: Int) {
        val existingIndex = chats.indexOfFirst { it.phoneNumber == otherPhone }
        val summary = ChatSummary(
            id = doc.id,
            name = phoneToNameMap[otherPhone] ?: otherPhone,
            phoneNumber = otherPhone,
            lastMessage = doc.getString("lastMessage") ?: "",
            time = "Now",
            unreadCount = unread,
            status = chats.getOrNull(existingIndex)?.status ?: ""
        )
        if (existingIndex != -1) chats[existingIndex] = summary else chats.add(summary)
    }

    private fun startUserStatusListener(otherPhone: String, myPhone: String) {
        if (userListeners.containsKey(otherPhone)) return
        userListeners[otherPhone] = db.collection("users").document(otherPhone)
            .addSnapshotListener { doc, _ ->
                if (doc != null && doc.exists()) {
                    val status = if (doc.getString("typingTo") == myPhone) "typing..."
                                else if (doc.getBoolean("isOnline") == true) "Online"
                                else formatLastSeen(doc.getTimestamp("lastSeen")?.toDate())
                    
                    val idx = chats.indexOfFirst { it.phoneNumber == otherPhone }
                    if (idx != -1) chats[idx] = chats[idx].copy(status = status)
                    if (otherPhone == currentObservingPhone) currentContactStatus = status
                }
            }
    }

    private var currentObservingPhone: String? = null
    fun observeContactStatus(phoneNumber: String) {
        currentObservingPhone = phoneNumber
        currentContactStatus = chats.find { it.phoneNumber == phoneNumber }?.status ?: "Offline"
    }

    fun getMessages(phoneNumber: String): List<Message> {
        val myPhone = auth.currentUser?.phoneNumber ?: ""
        val chatId = getChatId(myPhone, phoneNumber)
        val list = mutableStateListOf<Message>()
        
        db.collection("chats").document(chatId).collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { value, _ ->
                value?.documents?.forEach { doc ->
                    val isFromMe = doc.getString("sender") == myPhone
                    val status = MessageStatus.valueOf(doc.getString("status") ?: "SENT")
                    
                    // Mark as READ ONLY IF this chat is currently the active one on screen
                    if (!isFromMe && status != MessageStatus.READ && chatId == currentActiveChatId) {
                        doc.reference.update("status", MessageStatus.READ.name)
                    }
                    
                    val msg = Message(doc.id, doc.getString("text") ?: "", isFromMe, "Just now", status)
                    if (list.none { it.id == msg.id }) list.add(msg)
                    else {
                        val idx = list.indexOfFirst { it.id == msg.id }
                        if (list[idx].status != msg.status) list[idx] = msg
                    }
                }
            }
        return list
    }

    fun sendMessage(phoneNumber: String, text: String) {
        val myPhone = auth.currentUser?.phoneNumber ?: return
        val chatId = getChatId(myPhone, phoneNumber)
        val msg = hashMapOf("text" to text, "sender" to myPhone, "timestamp" to FieldValue.serverTimestamp(), "status" to MessageStatus.SENT.name)
        db.collection("chats").document(chatId).set(hashMapOf(
            "lastMessage" to text, 
            "lastSender" to myPhone,
            "participants" to listOf(myPhone, phoneNumber), 
            "timestamp" to FieldValue.serverTimestamp()
        ))
        db.collection("chats").document(chatId).collection("messages").add(msg)
    }

    fun updateTypingStatus(targetPhone: String?, isTyping: Boolean) {
        val myPhone = auth.currentUser?.phoneNumber ?: return
        db.collection("users").document(myPhone).update("typingTo", if (isTyping) targetPhone else null)
    }

    fun updatePresence(isOnline: Boolean) {
        val myPhone = auth.currentUser?.phoneNumber ?: return
        db.collection("users").document(myPhone).set(mapOf("isOnline" to isOnline, "lastSeen" to FieldValue.serverTimestamp()), SetOptions.merge())
    }

    private fun markMessagesAsDelivered(chatId: String, myPhone: String) {
        db.collection("chats").document(chatId).collection("messages")
            .whereEqualTo("status", MessageStatus.SENT.name).get().addOnSuccessListener { 
                it.forEach { msg -> if (msg.getString("sender") != myPhone) msg.reference.update("status", MessageStatus.DELIVERED.name) }
            }
    }

    private var onNotificationRequest: ((String, String) -> Unit)? = null
    fun setNotificationHandler(handler: (String, String) -> Unit) {
        onNotificationRequest = handler
    }

    private fun showNotification(phone: String, text: String) {
        onNotificationRequest?.invoke(getDisplayName(phone), text)
    }

    private fun formatLastSeen(date: Date?) = date?.let { "last seen today at " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(it) } ?: "Offline"
    private fun getChatId(u1: String, u2: String) = if (u1 < u2) "${u1}_${u2}" else "${u2}_${u1}"
    fun getDisplayName(phone: String) = phoneToNameMap[phone] ?: phone
    fun deleteChat(id: String) = db.collection("chats").document(id).delete()
}
