package org.saathi.android

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.SurfaceViewRenderer

@Composable fun NearbyScreen(vm: SaathiViewModel, state: AppState, discover: (Boolean) -> Unit, discoverBle: (Boolean) -> Unit, call: (Boolean, Boolean) -> Unit, modifier: Modifier) {
    var pairing by rememberSaveable { mutableStateOf("") }; var message by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.offerFile(uri) }
    LazyColumn(modifier, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Heading("Here, even without internet", "Connect with another person using Swarm nearby. Your saved work stays on this phone.") }
        item { Notice(if (state.confirmed) "Connected nearby" else "Nearby Swarm", state.nearbyStatus, Icons.Outlined.WifiTethering) }
        // Hidden for the Oct 2026 build: internet relay of public community updates (Updates is not shown).
        // item { Notice("Internet relay", state.gatewayStatus, Icons.Outlined.CloudSync) }
        if (!state.connected) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Find another Android", style = MaterialTheme.typography.titleMedium)
                    Text("Turn on Wi-Fi and Bluetooth. Android Nearby connects directly for messages and saved media; you do not need internet, a router or a manual hotspot. Nearby devices see your chosen display name while you search.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { discover(false) }, enabled = vm.nearby.available && !state.busy) { Text("Find Swarm") }; OutlinedButton(onClick = { discover(true) }, enabled = vm.nearby.available && !state.busy) { Text("Make visible") } }
                    Text("Bluetooth-only fallback works with Wi-Fi off. One person finds while the other makes their phone visible.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { discoverBle(false) }, enabled = vm.ble.available && !state.busy) { Text("Find by Bluetooth") }; OutlinedButton(onClick = { discoverBle(true) }, enabled = vm.ble.available && !state.busy) { Text("Make Bluetooth visible") } }
                    if (!vm.nearby.available) Text("Nearby discovery is unavailable. Use local Wi-Fi pairing below.", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(state.peers.entries.toList(), key = { it.key }) { peer -> OutlinedButton(onClick = { vm.connect(peer.key) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.PersonOutline, null); Spacer(Modifier.width(8.dp)); Text("Connect to ${peer.value}") } }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider(); Text("Pair on local Wi-Fi", style = MaterialTheme.typography.titleMedium)
                    Text("Use this connection for live calls, walkie-talkie or the Swarm browser app. Join the same Wi-Fi network or one person’s hotspot. Internet is not required. Copy the invitation and reply between devices.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (state.localWifiAddress) "This phone has a local Wi-Fi address. Check that the other device is on the same network; some networks block devices from talking to each other."
                        else "No local Wi-Fi address yet. For live audio, ask one person to enable a hotspot and have the others join it. Direct Android Nearby messaging can still work without that hotspot.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }) { Text("Open Wi-Fi settings") }
                        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }) { Text("Hotspot & network settings") }
                    }
                    OutlinedButton(onClick = { vm.offer() }, enabled = !state.busy) { Text("Make an invitation") }
                    if (state.invitation.isNotEmpty()) { Notice("Invitation ready", "Share it with the person beside you. It expires after two minutes.", Icons.Outlined.QrCode2); OutlinedButton(onClick = { clipboard.setText(AnnotatedString(state.invitation)); vm.notice("Pairing invitation copied. It contains temporary local connection information.") }) { Icon(Icons.Outlined.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("Copy invitation or reply") } }
                    OutlinedTextField(pairing, { pairing = it.take(20000) }, label = { Text("Paste invitation or reply") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                    Button(onClick = { vm.accept(pairing.trim()); pairing = "" }, enabled = pairing.isNotBlank() && !state.busy) { Text("Use invitation or reply") }
                }
            }
        } else if (state.confirmed) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("What works right now", style = MaterialTheme.typography.titleMedium)
                    // Hidden for the Oct 2026 build: signed requests and updates belong to Needs/Updates.
                    // Capability("Messages", true); Capability("Share signed requests and updates", true); Capability("Files", vm.session.transport?.supportsFiles == true)
                    Capability("Messages", true); Capability("Files", vm.session.transport?.supportsFiles == true)
                    Capability("Voice and video calls", state.media)
                    if (!state.media) Text("Live calls and walkie-talkie need local Wi-Fi pairing on a shared network or hotspot. Nearby messaging can continue on this connection.", style = MaterialTheme.typography.bodySmall)
                    if (vm.session.transport?.supportsFiles != true) Text("Photos, files, and calls stay saved or waiting until you reconnect using local Wi-Fi. This Bluetooth link carries messages and small updates.", style = MaterialTheme.typography.bodySmall)
                    // Hidden for the Oct 2026 build: sharing signed updates and nearby files that only appear in the hidden Saved page.
                    // Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { vm.share() }, enabled = !state.busy) { Text("Share saved updates") }; OutlinedButton(onClick = { picker.launch(arrayOf("image/jpeg", "image/png", "image/webp", "text/plain", "audio/*", "video/mp4", "video/webm")) }, enabled = !state.busy) { Text(if (vm.session.transport?.supportsFiles == true) "Share a file" else "Save file for Wi-Fi") } }
                    if (state.media && !state.calling) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { call(false, false) }) { Icon(Icons.Outlined.Call, null); Spacer(Modifier.width(6.dp)); Text("Voice call") }; OutlinedButton(onClick = { call(true, false) }) { Icon(Icons.Outlined.Videocam, null); Spacer(Modifier.width(6.dp)); Text("Video call") } }
                }
            }
            if (state.calling) item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (state.callActive) "Nearby call in progress" else "Calling nearby…", style = MaterialTheme.typography.titleMedium)
                    Text(state.quality.ifBlank { "Stay within local connection range. Calls end when Swarm leaves the foreground." }, style = MaterialTheme.typography.bodySmall)
                    if (state.video && state.callActive && state.remoteVideo != null) {
                        val track = state.remoteVideo
                        var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
                        AndroidView(factory = { context -> SurfaceViewRenderer(context).apply { init(vm.wifi.egl.eglBaseContext, null); setEnableHardwareScaler(true); track.addSink(this); renderer = this } }, modifier = Modifier.fillMaxWidth().height(240.dp))
                        DisposableEffect(track) { onDispose { renderer?.let { track.removeSink(it); it.release() }; renderer = null } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { vm.hangup() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("End call") }; if (state.video) TextButton(onClick = { vm.wifi.resumeVideo() }) { Text("Resume video") } }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HorizontalDivider(); Text("Nearby messages", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(message, { message = it.take(4000) }, modifier = Modifier.fillMaxWidth(), label = { Text(if (state.confirmed) "Message the connected person" else "Save a message for later") }, maxLines = 4)
                Button(onClick = { vm.sendMessage(message.trim()); message = "" }, enabled = message.isNotBlank() && !state.busy) { Icon(if (state.confirmed) Icons.Outlined.Send else Icons.Outlined.Save, null); Spacer(Modifier.width(8.dp)); Text(if (state.confirmed) "Send message" else "Save message on this phone") }
            }
        }
        items(state.messages.sortedByDescending { it.getString("createdAt") }.take(50), key = { it.getString("id") }) { messageRecord -> Surface(shape = MaterialTheme.shapes.medium, color = if (messageRecord.optString("direction") == "OUT") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(messageRecord.getString("text")); Text(if (messageRecord.optString("direction") == "IN") "Received nearby · ${timeLabel(messageRecord.getString("createdAt"))}" else if (messageRecord.has("deliveredAt")) "Received by the connected device" else "Saved on this phone · Delivery not confirmed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); if (state.confirmed && messageRecord.optString("direction") == "OUT" && !messageRecord.has("deliveredAt")) TextButton(onClick = { vm.action { vm.session.retryMessage(messageRecord.getString("id")) } }, enabled = !state.busy) { Text("Send to connected person") } } } }
        item { Text("Nearby communication works only while devices remain within local connection range. If the connection disappears, move closer or reconnect. Saved messages and requests remain safe.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); if (state.connected || state.peers.isNotEmpty() || state.invitation.isNotEmpty()) TextButton(onClick = { vm.disconnect() }) { Text("Disconnect nearby") } }
    }
}
@Composable private fun Capability(label: String, works: Boolean) { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Icon(if (works) Icons.Outlined.CheckCircle else Icons.Outlined.RemoveCircleOutline, null, Modifier.size(20.dp), tint = if (works) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant); Text(if (works) label else "$label · Unavailable", style = MaterialTheme.typography.bodyMedium) } }
@Composable fun SavedScreen(vm: SaathiViewModel, state: AppState, openDraft: (String) -> Unit, modifier: Modifier) {
    var carried by rememberSaveable { mutableStateOf(false) }
    var removal by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var exportId by rememberSaveable { mutableStateOf("") }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> if (uri != null) vm.action { vm.session.exportFile(exportId, uri); vm.notice("Verified file exported. The selected copy is outside Swarm’s private storage.") } }
    LazyColumn(modifier, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Heading("Saved on this phone", "Drafts and signed updates survive restarts. Nearby sharing does not mean an update has reached Swarm."); Freshness(state.savedAt) }
        item { Notice("Private work stays protected", vm.repository.store.securityDescription() + ". Signing keys cannot be exported by Swarm. An unlocked or compromised phone can still expose data while the app is running.", Icons.Outlined.Lock) }
        item { Row { Checkbox(carried, { carried = it }); Text("Also carry other people’s signed updates to Swarm", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium) }; Button(onClick = { vm.sync(carried) }, enabled = !state.busy && state.events.isNotEmpty()) { Icon(Icons.Outlined.Sync, null); Spacer(Modifier.width(8.dp)); Text("Synchronize saved updates") } }
        item { Text("Drafts", style = MaterialTheme.typography.titleLarge) }
        items(state.drafts, key = { it.getString("id") }) { draft -> OutlinedCard(onClick = { if (state.needsEnabled || draft.optString("draftType") == "field") openDraft("draft:" + draft.getString("id")) else vm.notice("Public relief needs are temporarily paused.") }) { Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(if (draft.optString("draftType") == "field") draft.optString("description").take(80).ifBlank { "Field update draft" } else draft.optString("title").ifBlank { "Untitled request draft" }, style = MaterialTheme.typography.titleMedium); Text("Saved on this phone · Tap to continue", style = MaterialTheme.typography.bodySmall); TextButton(onClick = { removal = obj("bucket" to "drafts", "id" to draft.getString("id")) }) { Text("Remove draft") } } } }
        if (state.drafts.isEmpty()) item { Text("No unfinished drafts", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("Signed updates", style = MaterialTheme.typography.titleLarge) }
        items(state.events, key = { it.getString("id") }) { event ->
            val body = event.getJSONObject("envelope").getJSONObject("body"); val receipt = event.optJSONObject("receipt")?.optJSONObject("body")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(body.getJSONObject("payload").optString("title", body.getJSONObject("payload").optString("caption", "Request change")), style = MaterialTheme.typography.titleMedium); Text(when (receipt?.optString("status")) { "PUBLISHED" -> "Published · Verified Swarm confirmation"; "ACCEPTED" -> "Reached Swarm · Awaiting publication"; "REJECTED", "CONFLICT", "INVALIDATED" -> "Needs attention · ${receipt.optString("message")}"; else -> if (event.has("sharedAt")) "Shared nearby · Waiting to reach Swarm" else "Saved on this phone · Waiting to be sent" }, style = MaterialTheme.typography.bodyMedium, color = if (receipt?.optString("status") in listOf("REJECTED", "CONFLICT", "INVALIDATED")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface); Text(if (event.optBoolean("own")) "Your original signed update" else "Carried update · Original author and signature preserved", style = MaterialTheme.typography.bodySmall); event.optString("error").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }; TextButton(onClick = { removal = obj("bucket" to "events", "id" to event.getString("id")) }) { Text("Remove saved copy") }; HorizontalDivider() }
        }
        if (state.events.isEmpty()) item { EmptyState("No signed updates yet", "Prepare this phone from My team, then create a request or field update.", Icons.Outlined.EditNote) }
        if (state.operations.isNotEmpty()) item { Text("Actions awaiting a response", style = MaterialTheme.typography.titleLarge); Text("Retries keep the original details and action identity. A missing response does not prove that Swarm rejected the action.", style = MaterialTheme.typography.bodySmall) }
        items(state.operations, key = { "operation/" + it.getString("id") }) { operation ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val label = when (operation.getString("id").substringBefore('/')) { "reserve" -> "Relief reservation"; "order" -> "Delivery details"; "receive" -> "Received quantity"; "confirm" -> "Delivery confirmation"; "cancel" -> "Reservation cancellation"; else -> "Saved action" }
                Text(label, style = MaterialTheme.typography.titleMedium)
                if (!operation.has("response")) { Text("Saved on this phone · Swarm’s response not confirmed", style = MaterialTheme.typography.bodySmall); TextButton(onClick = { vm.action { vm.repository.retryWrite(operation); if (vm.repository.preparation != null) vm.loadDashboard(); vm.notice("Original action confirmed by Swarm. Check its current status before further changes.") } }, enabled = !state.busy) { Text("Retry original action") } }
                else { Text("Swarm’s response saved · ${timeLabel(operation.getString("createdAt"))}", style = MaterialTheme.typography.bodySmall); TextButton(onClick = { removal = obj("bucket" to "operations", "id" to operation.getString("id")) }) { Text("Clear completed action history") } }
                HorizontalDivider()
            }
        }
        item { Text("My contributions", style = MaterialTheme.typography.titleLarge) }
        items(state.donations, key = { it.getString("id") }) { donation -> DonationFollowUp(vm, donation); HorizontalDivider() }
        if (state.donations.isEmpty()) item { Text("No saved contributions", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("Files", style = MaterialTheme.typography.titleLarge) }
        items(state.files.filter{it.getString("mime")!="application/octet-stream"}, key = { it.getString("id") }) { file -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(file.getString("name"), style = MaterialTheme.typography.titleMedium); Text("${fileSize(file.getLong("size"))} · " + if (file.optBoolean("waitingForStrongerTransport")) "Saved · waiting for Wi-Fi transfer" else if (file.optBoolean("complete")) "Saved and hash checked" else "Transfer paused or incomplete", style = MaterialTheme.typography.bodySmall); if (file.optString("direction") == "OUT" && state.confirmed) TextButton(onClick = { vm.action { vm.session.offerSaved(file) } }) { Text(if(vm.session.transport?.supportsFiles==true) "Offer again to resume" else "Keep waiting for Wi-Fi") }; if (!file.optBoolean("complete") && state.confirmed && vm.session.transport?.supportsFiles == true) TextButton(onClick = { vm.action { vm.session.cancelFile(file.getString("id")) } }) { Text("Pause transfer") }; if (file.optBoolean("complete")) TextButton(onClick = { exportId = file.getString("id"); exporter.launch(file.getString("name")) }, enabled = !state.busy) { Text("Export verified file") }; TextButton(onClick = { removal = obj("bucket" to "attachments", "id" to file.getString("id")) }) { Text("Remove file from this phone") }; HorizontalDivider() } }
        item { Text("Saved messages", style = MaterialTheme.typography.titleLarge) }
        items(state.messages.sortedByDescending { it.getString("createdAt") }, key = { "message/" + it.getString("id") }) { message -> Column { Text(message.getString("text")); TextButton(onClick = { removal = obj("bucket" to "messages", "id" to message.getString("id")) }) { Text("Remove saved message") } } }
    }
    removal?.let { item -> AlertDialog(onDismissRequest = { removal = null }, title = { Text("Remove this saved copy?") }, text = { Text("This permanently removes the copy on this phone. It does not withdraw anything already shared nearby or recorded by Swarm. Clearing a completed action’s history also forgets its retry identity.") }, confirmButton = { TextButton(onClick = { vm.action { if (item.getString("bucket") == "attachments") vm.session.removeFile(item.getString("id")) else vm.repository.store.remove(item.getString("bucket"), item.getString("id")); removal = null; vm.notice("Saved copy removed from this phone.") } }, enabled = !state.busy) { Text("Remove saved copy") } }, dismissButton = { TextButton(onClick = { removal = null }) { Text("Keep saved") } }) }
}
