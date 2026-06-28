package com.share.chatapp.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

data class Contact(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val isAppUser: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactListScreen(
    onBack: () -> Unit,
    onContactClick: (Contact) -> Unit,
    onContactsSynced: (Map<String, String>) -> Unit = {}
) {
    val context = LocalContext.current
    val db = remember { com.google.firebase.firestore.FirebaseFirestore.getInstance() }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val filteredContacts = remember(contacts, searchQuery) {
        if (searchQuery.isEmpty()) contacts
        else contacts.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.phoneNumber.contains(searchQuery)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            hasPermission = isGranted
        }
    )

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            val phoneContacts = fetchContacts(context)
            contacts = phoneContacts // Show local contacts first
            
            // Send mapping to ViewModel for name resolution
            onContactsSynced(phoneContacts.associate { it.phoneNumber to it.name })

            // Sync with Firestore to see who is an app user
            syncAppUsers(db, phoneContacts) { syncedList ->
                contacts = syncedList
            }
        } else {
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("Select Contact", style = MaterialTheme.typography.titleMedium)
                            if (hasPermission) {
                                Text("${contacts.size} contacts", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
                if (hasPermission) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Search name or number...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = if (searchQuery.isNotEmpty()) {
                            {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        } else null,
                        shape = RoundedCornerShape(24.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                }
            }
        }
    ) { padding ->
        if (!hasPermission) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Button(onClick = { permissionLauncher.launch(Manifest.permission.READ_CONTACTS) }) {
                    Text("Grant Contacts Permission")
                }
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                item {
                    Text(
                        "All Contacts",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                items(filteredContacts) { contact ->
                    ContactItem(contact, onContactClick)
                }
                
                if (filteredContacts.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("No contacts found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun fetchContacts(context: Context): List<Contact> {
    val contactList = mutableListOf<Contact>()
    val contentResolver = context.contentResolver
    val cursor = contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        null,
        null,
        null,
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
    )

    cursor?.use {
        val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)

        while (it.moveToNext()) {
            val name = it.getString(nameIndex) ?: "Unknown"
            val rawNumber = it.getString(numberIndex) ?: ""
            val id = it.getString(idIndex) ?: ""
            
            // Normalize the number: Remove spaces, dashes, parentheses
            var cleanedNumber = rawNumber.replace("[^0-9+]".toRegex(), "")
            
            // If it's 10 digits, add +91
            if (cleanedNumber.length == 10) {
                cleanedNumber = "+91$cleanedNumber"
            } else if (cleanedNumber.length == 11 && cleanedNumber.startsWith("0")) {
                cleanedNumber = "+91" + cleanedNumber.substring(1)
            } else if (cleanedNumber.startsWith("91") && cleanedNumber.length == 12) {
                cleanedNumber = "+$cleanedNumber"
            }
            
            // Basic deduplication
            if (cleanedNumber.isNotEmpty() && contactList.none { c -> c.phoneNumber == cleanedNumber }) {
                contactList.add(Contact(id, name, cleanedNumber, isAppUser = false))
            }
        }
    }
    return contactList
}

private fun syncAppUsers(
    db: com.google.firebase.firestore.FirebaseFirestore,
    localContacts: List<Contact>,
    onComplete: (List<Contact>) -> Unit
) {
    val phoneNumbers = localContacts.map { it.phoneNumber }
    if (phoneNumbers.isEmpty()) return

    // Firebase only allows 'in' query with 10 items. For simplicity here, 
    // we query all users and match. In production, use smaller batches.
    db.collection("users").get().addOnSuccessListener { querySnapshot ->
        val registeredNumbers = querySnapshot.documents.map { it.id }
        val updatedList = localContacts.map { contact ->
            if (registeredNumbers.contains(contact.phoneNumber)) {
                contact.copy(isAppUser = true)
            } else {
                contact
            }
        }.sortedByDescending { it.isAppUser } // Show app users at the top
        onComplete(updatedList)
    }
}

@Composable
fun ContactItem(contact: Contact, onClick: (Contact) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(contact) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = contact.name.take(1),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column {
            Text(
                text = contact.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = contact.phoneNumber,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        if (!contact.isAppUser) {
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { /* Invite logic */ }) {
                Text("INVITE")
            }
        }
    }
}
