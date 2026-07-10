package com.share.chatapp.ui.chat

import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
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
    private val messageListeners = mutableMapOf<String, ListenerRegistration>()
    private val phoneToNameMap = mutableStateMapOf<String, String>()
    private var currentActiveChatId: String? = null

    init {
        observeChats()
        updatePresence(true)
    }

    fun setActiveChat(phoneNumber: String?) {
        val myPhone = auth.currentUser?.phoneNumber ?: return
        currentActiveChatId = phoneNumber?.let { getChatId(myPhone, it) }
        // If we just entered a chat, mark existing messages as read
        currentActiveChatId?.let { chatId ->
            db.collection("chats").document(chatId).collection("messages")
                .whereNotEqualTo("status", MessageStatus.READ.name)
                .get().addOnSuccessListener { snapshot ->
                    snapshot.documents.forEach { doc ->
                        if (doc.getString("sender") != myPhone) {
                            doc.reference.update("status", MessageStatus.READ.name)
                        }
                    }
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
                        
                        // Calculate unread count (Only add listener once)
                        if (!messageListeners.containsKey(doc.id)) {
                            messageListeners[doc.id] = db.collection("chats").document(doc.id).collection("messages")
                                .whereNotEqualTo("status", MessageStatus.READ.name)
                                .addSnapshotListener { msgValue, _ ->
                                    val unreadCount = msgValue?.documents?.count { it.getString("sender") != currentUser } ?: 0
                                    updateChatSummary(doc, otherPhone, unreadCount)
                                }
                        }
                    } else {
                        messageListeners[doc.id]?.remove()
                        messageListeners.remove(doc.id)
                        chats.removeIf { it.id == doc.id }
                    }
                    startUserStatusListener(otherPhone, currentUser)
                }
            }
    }

    private fun updateChatSummary(doc: DocumentSnapshot, otherPhone: String, unread: Int) {
        val existingIndex = chats.indexOfFirst { it.phoneNumber == otherPhone }
        val timestamp = doc.getTimestamp("timestamp")?.toDate()
        val timeStr = timestamp?.let { 
            val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
            val now = Calendar.getInstance()
            val msgTime = Calendar.getInstance().apply { time = it }
            if (now.get(Calendar.DATE) == msgTime.get(Calendar.DATE)) sdf.format(it)
            else SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(it)
        } ?: "Now"

        val summary = ChatSummary(
            id = doc.id,
            name = phoneToNameMap[otherPhone] ?: otherPhone,
            phoneNumber = otherPhone,
            lastMessage = doc.getString("lastMessage") ?: "",
            time = timeStr,
            unreadCount = unread,
            status = chats.getOrNull(existingIndex)?.status ?: ""
        )
        if (existingIndex != -1) chats[existingIndex] = summary else chats.add(summary)
    }

    private fun startUserStatusListener(otherPhone: String, myPhone: String) {
        if (userListeners.containsKey(otherPhone)) {
            // Listener already exists, ensure current status is synced if this is the active contact
            if (otherPhone == currentObservingPhone) {
                chats.find { it.phoneNumber == otherPhone }?.status?.let { 
                    if (it.isNotEmpty()) currentContactStatus = it 
                }
            }
            return
        }
        Log.d(TAG, "Starting status listener for $otherPhone")
        userListeners[otherPhone] = db.collection("users").document(otherPhone)
            .addSnapshotListener { doc, error ->
                if (error != null) {
                    Log.e(TAG, "User status listener failed for $otherPhone: ${error.message}")
                    return@addSnapshotListener
                }
                if (doc != null && doc.exists()) {
                    val isOnline = doc.getBoolean("isOnline") == true
                    val lastSeen = doc.getTimestamp("lastSeen")?.toDate()
                    val typingTo = doc.getString("typingTo")
                    
                    val status = if (typingTo == myPhone) "typing..."
                                else if (isOnline) "Online"
                                else formatLastSeen(lastSeen)
                    
                    Log.d(TAG, "Status update for $otherPhone: $status (Online: $isOnline, LastSeen: $lastSeen)")
                    
                    val idx = chats.indexOfFirst { it.phoneNumber == otherPhone }
                    if (idx != -1) chats[idx] = chats[idx].copy(status = status)
                    if (otherPhone == currentObservingPhone) {
                        currentContactStatus = status
                    }
                } else {
                    Log.d(TAG, "No status document found for $otherPhone")
                    if (otherPhone == currentObservingPhone) currentContactStatus = "Offline"
                }
            }
    }

    private var currentObservingPhone: String? = null
    fun observeContactStatus(phoneNumber: String) {
        Log.d(TAG, "observeContactStatus called for: $phoneNumber")
        currentObservingPhone = phoneNumber
        val myPhone = auth.currentUser?.phoneNumber ?: return
        startUserStatusListener(phoneNumber, myPhone)
        currentContactStatus = chats.find { it.phoneNumber == phoneNumber }?.status ?: "Offline"
    }

    private val messageLists = mutableMapOf<String, SnapshotStateList<Message>>()
    private val messageSnapshotListeners = mutableMapOf<String, ListenerRegistration>()

    fun getMessages(phoneNumber: String): List<Message> {
        val myPhone = auth.currentUser?.phoneNumber ?: ""
        val chatId = getChatId(myPhone, phoneNumber)
        
        if (messageLists.containsKey(chatId)) return messageLists[chatId]!!

        val list = mutableStateListOf<Message>()
        messageLists[chatId] = list
        
        val listener = db.collection("chats").document(chatId).collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { value, _ ->
                value?.documents?.forEach { doc ->
                    val isFromMe = doc.getString("sender") == myPhone
                    val statusStr = doc.getString("status") ?: "SENT"
                    val status = try { MessageStatus.valueOf(statusStr) } catch(e: Exception) { MessageStatus.SENT }
                    
                    // Mark as READ ONLY IF this chat is currently the active one on screen
                    if (!isFromMe && status != MessageStatus.READ && chatId == currentActiveChatId) {
                        doc.reference.update("status", MessageStatus.READ.name)
                    } else if (!isFromMe && status == MessageStatus.SENT) {
                        // Mark as DELIVERED if we see it but aren't necessarily in the chat yet (though we are here)
                        doc.reference.update("status", MessageStatus.DELIVERED.name)
                    }
                    
                    val reactions = doc.get("reactions") as? Map<String, List<String>> ?: emptyMap()
                    
                    val timestamp = doc.getTimestamp("timestamp")?.toDate()
                    val timeStr = timestamp?.let { SimpleDateFormat("h:mm a", Locale.getDefault()).format(it) } ?: "Just now"

                    val msg = Message(doc.id, doc.getString("text") ?: "", isFromMe, timeStr, status, reactions)
                    val existingIndex = list.indexOfFirst { it.id == msg.id }
                    if (existingIndex == -1) {
                        list.add(msg)
                    } else {
                        list[existingIndex] = msg
                    }
                }
            }
        messageSnapshotListeners[chatId] = listener
        return list
    }

    fun toggleReaction(phoneNumber: String, messageId: String, emoji: String) {
        val myPhone = auth.currentUser?.phoneNumber ?: return
        val chatId = getChatId(myPhone, phoneNumber)
        val docRef = db.collection("chats").document(chatId).collection("messages").document(messageId)
        
        db.runTransaction { transaction ->
            val snapshot = transaction.get(docRef)
            val reactions = snapshot.get("reactions") as? MutableMap<String, MutableList<String>> ?: mutableMapOf()
            val users = reactions[emoji] ?: mutableListOf()
            
            if (users.contains(myPhone)) {
                users.remove(myPhone)
            } else {
                users.add(myPhone)
            }
            
            if (users.isEmpty()) reactions.remove(emoji)
            else reactions[emoji] = users
            
            transaction.update(docRef, "reactions", reactions)
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to toggle reaction: ${e.message}")
        }
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

    private fun formatLastSeen(date: Date?): String {
        if (date == null) return "Offline"
        val now = Calendar.getInstance()
        val seen = Calendar.getInstance().apply { time = date }
        
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        return when {
            now.get(Calendar.DATE) == seen.get(Calendar.DATE) -> "last seen today at ${timeFormat.format(date)}"
            now.get(Calendar.DATE) - seen.get(Calendar.DATE) == 1 -> "last seen yesterday at ${timeFormat.format(date)}"
            else -> "last seen on " + SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(date)
        }
    }
    private fun getChatId(u1: String, u2: String) = if (u1 < u2) "${u1}_${u2}" else "${u2}_${u1}"
    fun getDisplayName(phone: String) = phoneToNameMap[phone] ?: phone
    fun deleteChat(id: String) = db.collection("chats").document(id).delete()

    override fun onCleared() {
        super.onCleared()
        userListeners.values.forEach { it.remove() }
        messageListeners.values.forEach { it.remove() }
        messageSnapshotListeners.values.forEach { it.remove() }
        userListeners.clear()
        messageListeners.clear()
        messageSnapshotListeners.clear()
        messageLists.clear()
        updatePresence(false)
    }
}
