package com.share.chatapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.core.app.NotificationCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.share.chatapp.ui.auth.LoginScreen
import com.share.chatapp.ui.auth.OtpScreen
import com.share.chatapp.ui.chat.ChatListScreen
import com.share.chatapp.ui.chat.ChatScreen
import com.share.chatapp.ui.chat.ChatViewModel
import com.share.chatapp.ui.chat.ContactListScreen
import com.share.chatapp.ui.theme.ChatAppTheme
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var auth: FirebaseAuth
    private var verificationId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = FirebaseAuth.getInstance()
        createNotificationChannel()
        enableEdgeToEdge()
        setContent {
            ChatAppTheme {
                val chatViewModel: ChatViewModel = viewModel()
                
                LaunchedEffect(Unit) {
                    chatViewModel.setNotificationHandler { name, message ->
                        showNotification(name, message)
                    }
                }

                val startDestination = if (auth.currentUser != null) "chat_list" else "login"
                ChatAppNavigation(chatViewModel, startDestination)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Chat Messages"
            val descriptionText = "Notifications for new chat messages"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel("CHAT_CHANNEL", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(name: String, message: String) {
        val builder = NotificationCompat.Builder(this, "CHAT_CHANNEL")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(name)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    @Composable
    fun ChatAppNavigation(chatViewModel: ChatViewModel, startDestination: String) {
        val navController = rememberNavController()

        NavHost(navController = navController, startDestination = startDestination) {
            composable("login") {
                LoginScreen(
                    onSendOtp = { phoneNumber ->
                        sendOtp("+91$phoneNumber") { success ->
                            if (success) navController.navigate("otp/$phoneNumber")
                        }
                    }
                )
            }
            composable(
                "otp/{phoneNumber}",
                arguments = listOf(navArgument("phoneNumber") { type = NavType.StringType })
            ) { backStackEntry ->
                val phoneNumber = backStackEntry.arguments?.getString("phoneNumber") ?: ""
                OtpScreen(
                    phoneNumber = phoneNumber,
                    onVerifyOtp = { otp ->
                        verifyOtp(otp) { success ->
                            if (success) {
                                navController.navigate("chat_list") {
                                    popUpTo("login") { inclusive = true }
                                }
                            }
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            composable("chat_list") {
                ChatListScreen(
                    chats = chatViewModel.chats,
                    onNewChat = { navController.navigate("contact_list") },
                    onChatClick = { phoneNumber ->
                        navController.navigate("chat/$phoneNumber")
                    },
                    onDeleteChat = { chat -> chatViewModel.deleteChat(chat.id) }
                )
            }
            composable("contact_list") {
                ContactListScreen(
                    onBack = { navController.popBackStack() },
                    onContactClick = { contact ->
                        navController.navigate("chat/${contact.phoneNumber}") {
                            popUpTo("contact_list") { inclusive = true }
                        }
                    },
                    onContactsSynced = { names ->
                        chatViewModel.setContactNames(names)
                    }
                )
            }
            composable(
                "chat/{phoneNumber}",
                arguments = listOf(navArgument("phoneNumber") { type = NavType.StringType })
            ) { backStackEntry ->
                val phoneNumber = backStackEntry.arguments?.getString("phoneNumber") ?: "Chat"
                
                DisposableEffect(phoneNumber) {
                    chatViewModel.setActiveChat(phoneNumber)
                    chatViewModel.observeContactStatus(phoneNumber)
                    onDispose {
                        chatViewModel.setActiveChat(null)
                    }
                }

                ChatScreen(
                    contactName = chatViewModel.getDisplayName(phoneNumber),
                    userStatus = chatViewModel.currentContactStatus,
                    messages = chatViewModel.getMessages(phoneNumber),
                    onSendMessage = { text -> chatViewModel.sendMessage(phoneNumber, text) },
                    onTyping = { isTyping -> chatViewModel.updateTypingStatus(phoneNumber, isTyping) },
                    onBack = { 
                        chatViewModel.updateTypingStatus(null, false)
                        navController.popBackStack() 
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::auth.isInitialized && auth.currentUser != null) {
            val currentUser = auth.currentUser?.phoneNumber ?: return
            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            db.collection("users").document(currentUser)
                .set(mapOf(
                    "isOnline" to true,
                    "lastSeen" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                ), com.google.firebase.firestore.SetOptions.merge())
        }
    }

    override fun onPause() {
        super.onPause()
        if (::auth.isInitialized && auth.currentUser != null) {
            val currentUser = auth.currentUser?.phoneNumber ?: return
            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            db.collection("users").document(currentUser)
                .set(mapOf(
                    "isOnline" to false,
                    "lastSeen" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                ), com.google.firebase.firestore.SetOptions.merge())
        }
    }

    private fun sendOtp(phoneNumber: String, onResult: (Boolean) -> Unit) {
        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(this)
            .setCallbacks(object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    signInWithCredential(credential, onResult)
                }

                override fun onVerificationFailed(e: FirebaseException) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    onResult(false)
                }

                override fun onCodeSent(id: String, token: PhoneAuthProvider.ForceResendingToken) {
                    verificationId = id
                    onResult(true)
                }
            })
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    private fun verifyOtp(code: String, onResult: (Boolean) -> Unit) {
        val credential = PhoneAuthProvider.getCredential(verificationId, code)
        signInWithCredential(credential, onResult)
    }

    private fun signInWithCredential(credential: PhoneAuthCredential, onResult: (Boolean) -> Unit) {
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    if (user != null) {
                        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                        val userData = mapOf(
                            "phoneNumber" to user.phoneNumber,
                            "isOnline" to true,
                            "lastSeen" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                        )
                        db.collection("users").document(user.phoneNumber!!).set(userData, com.google.firebase.firestore.SetOptions.merge())
                    }
                    onResult(true)
                } else {
                    Toast.makeText(this, "Verification Failed", Toast.LENGTH_SHORT).show()
                    onResult(false)
                }
            }
    }
}
