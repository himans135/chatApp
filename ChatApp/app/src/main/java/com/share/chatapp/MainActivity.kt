package com.share.chatapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.share.chatapp.ui.auth.LoginScreen
import com.share.chatapp.ui.auth.OtpScreen
import com.share.chatapp.ui.chat.ChatListScreen
import com.share.chatapp.ui.chat.ChatScreen
import com.share.chatapp.ui.chat.ChatViewModel
import com.share.chatapp.ui.chat.ContactListScreen
import com.share.chatapp.ui.theme.ChatAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChatAppTheme {
                val chatViewModel: ChatViewModel = viewModel()
                ChatAppNavigation(chatViewModel)
            }
        }
    }
}

@Composable
fun ChatAppNavigation(chatViewModel: ChatViewModel) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "login") {
        composable("login") {
            LoginScreen(
                onSendOtp = { phoneNumber ->
                    navController.navigate("otp/$phoneNumber")
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
                    navController.navigate("chat_list") {
                        popUpTo("login") { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable("chat_list") {
            ChatListScreen(
                chats = chatViewModel.chats,
                onNewChat = { navController.navigate("contact_list") },
                onChatClick = { chatName ->
                    chatViewModel.markAsRead(chatName)
                    navController.navigate("chat/$chatName")
                },
                onDeleteChat = { chat -> chatViewModel.deleteChat(chat.id) }
            )
        }
        composable("contact_list") {
            ContactListScreen(
                onBack = { navController.popBackStack() },
                onContactClick = { contact ->
                    navController.navigate("chat/${contact.name}") {
                        popUpTo("contact_list") { inclusive = true }
                    }
                }
            )
        }
        composable(
            "chat/{contactName}",
            arguments = listOf(navArgument("contactName") { type = NavType.StringType })
        ) { backStackEntry ->
            val contactName = backStackEntry.arguments?.getString("contactName") ?: "Chat"
            ChatScreen(
                contactName = contactName,
                messages = chatViewModel.getMessages(contactName),
                onSendMessage = { text -> chatViewModel.sendMessage(contactName, text) },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
