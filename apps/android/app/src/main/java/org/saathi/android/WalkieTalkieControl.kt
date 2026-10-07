package org.saathi.android

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable internal fun WalkieTalkieControl(vm: SaathiViewModel, state: AppState, id: String, name: String, callReady: Boolean) {
    val active = state.walkieConversation == id
    val status by rememberUpdatedState(if (active) state.walkieStatus else "OFF")
    val canHold = active && status in setOf("READY", "REQUESTING", "TALKING")
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.enableWalkie(id) else vm.notice("Microphone permission is needed to speak. You can still send messages.")
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Walkie-talkie", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Switch(active, { enabled -> if (enabled) permission.launch(Manifest.permission.RECORD_AUDIO) else vm.stopWalkie() },
                enabled = active || (callReady && state.walkieAvailable && !state.calling && !state.recording && state.incomingCall == null),
                modifier = Modifier.semantics { contentDescription = "Enable walkie-talkie with $name" })
        }
        Text(when (status) {
            "WAITING" -> "Ask $name to open your chat and enable walkie-talkie. Microphone off."
            "REQUESTING" -> "Waiting for your turn. Microphone off."
            "TALKING" -> "Speaking to $name · Release to stop · 30-second limit"
            "LISTENING" -> "$name is speaking. Microphone off."
            "READY" -> "Ready with $name · Hold to speak, release to listen"
            else -> if (callReady && state.walkieAvailable) "Live audio for this chat. Both people enable it; audio is not saved."
                else "Connect to $name with local Wi-Fi for live audio. Bluetooth supports saved messages."
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (active) Button(
            onClick = { if (status in setOf("TALKING", "REQUESTING")) vm.releaseWalkie() else vm.pressWalkie(id) },
            enabled = canHold,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .pointerInput(id, active) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (status == "READY") {
                            down.consume(); vm.pressWalkie(id)
                            try { waitForUpOrCancellation()?.consume() } finally { vm.releaseWalkie() }
                        }
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.key in listOf(Key.Spacebar, Key.Enter, Key.NumPadEnter)) {
                        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 && status == "READY") vm.pressWalkie(id)
                        if (event.type == KeyEventType.KeyUp) vm.releaseWalkie()
                        true
                    } else false
                }
                .semantics {
                    stateDescription = status.lowercase()
                    onClick(label = if (status == "TALKING") "Stop speaking" else "Speak for up to 30 seconds") {
                        if (canHold) { if (status in setOf("TALKING", "REQUESTING")) vm.releaseWalkie() else vm.pressWalkie(id); true } else false
                    }
                },
            colors = if (status == "TALKING") ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) else ButtonDefaults.buttonColors(),
        ) { Icon(Icons.Outlined.Mic, null); Spacer(Modifier.width(8.dp)); Text(if (status == "TALKING") "Release to stop" else if (status == "REQUESTING") "Waiting for turn…" else "Hold to talk") }
    }
}
