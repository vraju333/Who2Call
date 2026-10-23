package com.safecard.app

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

private val circles = listOf("Family", "Work", "Friends", "Playgroup")

class MainActivity : ComponentActivity() {
    private val receivedUri = mutableStateOf<Uri?>(null)

    private fun acceptAttachment(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) receivedUri.value = uri
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptAttachment(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReminderWorker.schedule(this)
        acceptAttachment(intent)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF235CA7))) { SafeCardScreen(receivedUri.value) } }
    }
}

@Composable
fun SafeCardScreen(incomingUri: Uri? = null) {
    val context = LocalContext.current
    val store = remember { LocalStore(context) }
    var mode by remember { mutableStateOf("Owner") }
    var circle by remember { mutableStateOf("Family") }
    var contacts by remember { mutableStateOf(emptyList<ContactItem>()) }
    var recent by remember { mutableStateOf(emptySet<String>()) }
    var selected by remember(circle) { mutableStateOf(store.selected(circle)) }
    var includeRecent by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Select a circle and review contacts.") }
    var imported by remember { mutableStateOf(emptyList<ContactItem>()) }
    var loadedFile by remember { mutableStateOf<ByteArray?>(null) }
    val refresh = {
        try { contacts = ContactStore.read(context); recent = store.observe(contacts) }
        catch (e: Exception) { status = "Cannot read contacts: ${e.message}" }
    }
    val contactPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) refresh() else status = "Contacts permission denied."
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            loadedFile = context.contentResolver.openInputStream(uri)?.use { stream -> stream.readBytes().also { require(it.size <= 1_000_000) } }
            imported = emptyList(); status = "Encrypted file loaded. Enter its shared passphrase."
        } catch (e: Exception) { status = "File import failed: ${e.message}" }
    }
    LaunchedEffect(incomingUri) {
        if (incomingUri != null) {
            mode = "Trusted person"
            passphrase = ""
            imported = emptyList()
            try {
                val data = context.contentResolver.openInputStream(incomingUri)?.use { input ->
                    input.readNBytes(1_000_001)
                } ?: error("Attachment is unavailable")
                require(data.size <= 1_000_000) { "Card is too large" }
                loadedFile = data
                status = "Card received. Enter the shared passphrase to view contacts."
            } catch (e: Exception) {
                loadedFile = null
                status = "Could not read attachment: ${e.message}. Try Choose .scard file."
            }
        }
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) refresh()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Who2Call 👻", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Private emergency contacts • stored on your devices", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Owner", "Trusted person").forEachIndexed { index, item ->
                SegmentedButton(selected = mode == item, onClick = { mode = item; passphrase = ""; status = "" }, shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(item) }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (mode == "Owner") {
            Text("Share only what each trusted person needs", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                circles.forEach { item ->
                    FilterChip(selected = circle == item, onClick = { circle = item; selected = store.selected(item); includeRecent = false }, label = { Text(item, style = MaterialTheme.typography.labelSmall) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = includeRecent, onCheckedChange = { includeRecent = it })
                Text("Include contacts first seen in last 5 days (${recent.size})", style = MaterialTheme.typography.bodySmall)
            }
            Text("Newly detected contacts are not automatically shared. Review before sending.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Search contacts") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (contacts.isEmpty()) Button(onClick = { contactPermission.launch(Manifest.permission.READ_CONTACTS) }) { Text("Allow contacts access") }
            val display = remember(contacts, search) { contacts.filter { it.name.contains(search, true) || it.number.contains(search) }.take(300) }
            LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(display, key = { it.id + it.number }) { contact ->
                    Row(Modifier.fillMaxWidth().clickable {
                        selected = if (contact.id in selected) selected - contact.id else selected + contact.id
                        store.setSelected(circle, selected)
                    }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = contact.id in selected, onCheckedChange = { checked ->
                            selected = if (checked) selected + contact.id else selected - contact.id
                            store.setSelected(circle, selected)
                        })
                        Column { Text(contact.name); Text(contact.number, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            val chosen = contacts.filter { it.id in selected || (includeRecent && it.id in recent) }.distinctBy { it.id + it.number }.sortedWith(compareBy({ it.name }, { it.number }))
            val payload = CardCodec.vcf(chosen)
            val hash = CardCodec.fingerprint(payload)
            Text("${chosen.size} entries • ${if (store.lastSharedHash(circle) == hash) "No change since last share" else "Changed / not yet shared"}", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = passphrase, onValueChange = { passphrase = it }, label = { Text("6-digit PIN or passphrase (12+ characters)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("A 6-digit PIN is easier to guess if someone obtains the file. Use a longer passphrase for stronger protection.", style = MaterialTheme.typography.bodySmall)
            Button(modifier = Modifier.fillMaxWidth(), enabled = chosen.isNotEmpty() && CardCodec.isValidCode(passphrase), onClick = {
                try {
                    val bytes = CardCodec.encrypt(payload, passphrase)
                    val dir = File(context.cacheDir, "shares").apply { mkdirs() }
                    val file = File(dir, "SafeCard_${circle}.scard").apply { writeBytes(bytes) }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/octet-stream"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newUri(context.contentResolver, "Encrypted Who2Call card", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Send encrypted Who2Call card to trusted person"))
                    // The share sheet does not confirm actual delivery. Keep eligible for re-share.
                    status = "Share sheet opened. After recipient confirms receipt, tap 'Mark delivered'."
                } catch (e: Exception) { status = "Unable to prepare share: ${e.message}" }
            }) { Text(if (store.lastSharedHash(circle) == hash) "Resend $circle card" else "Encrypt and share $circle card") }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = chosen.isNotEmpty() && store.lastSharedHash(circle) != hash, onClick = {
                store.markShared(circle, hash); status = "Marked delivered locally."
            }) { Text("Mark delivered (after confirmation)") }
        } else {
            Text("Open an encrypted card from a trusted person", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { openFile.launch(arrayOf("*/*")) }) { Text("Choose .scard file") }
            OutlinedTextField(value = passphrase, onValueChange = { passphrase = it }, label = { Text("Shared passphrase") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(enabled = loadedFile != null && passphrase.isNotEmpty(), onClick = {
                try { imported = CardCodec.preview(CardCodec.decrypt(loadedFile!!, passphrase)); status = "Unlocked ${imported.size} contacts." }
                catch (_: Exception) { imported = emptyList(); status = "Could not unlock: wrong passphrase or damaged file." }
            }) { Text("Unlock contacts") }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(imported) { contact ->
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(contact.name, fontWeight = FontWeight.SemiBold)
                        Text(contact.number)
                    }
                    HorizontalDivider()
                }
            }
        }
        if (mode == "Owner") Spacer(Modifier.height(4.dp))
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}
