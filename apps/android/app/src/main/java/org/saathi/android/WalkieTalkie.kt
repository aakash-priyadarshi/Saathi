package org.saathi.android

import org.json.JSONObject
import java.util.UUID

/** One foreground DM, one speaker. No recording, persistence, relay or microphone while waiting. */
internal class WalkieTalkie(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val send: (String, JSONObject) -> Unit,
    private val changed: (Snapshot) -> Unit,
) {
    data class Snapshot(val conversationId: String? = null, val status: String = "OFF", val deadline: Long? = null) {
        val transmitting get() = status == "TALKING"
        val receiving get() = status == "LISTENING"
    }
    private var me = ""; private var peer = ""; private var conversation: String? = null
    private var localSession = ""; private var remoteSession: String? = null
    private var localTurn: String? = null; private var remoteTurn: String? = null
    private var localDeadline: Long? = null; private var remoteDeadline: Long? = null
    private var microphone = false
    val snapshot get() = Snapshot(conversation, when {
        conversation == null -> "OFF"
        microphone -> "TALKING"
        remoteTurn != null -> "LISTENING"
        localTurn != null -> "REQUESTING"
        remoteSession == null -> "WAITING"
        else -> "READY"
    }, listOfNotNull(localDeadline, remoteDeadline).minOrNull())
    private fun update() = changed(snapshot)
    private fun packet(turn: String? = null) = obj("conversationId" to conversation, "senderId" to me,
        "recipientId" to peer, "sessionId" to localSession).apply {
        if (turn != null) put("turnId", turn).put("forSession", remoteSession)
    }
    fun enable(self: String, recipient: String, id: String) {
        require(id == ChatProtocol.dm(self, recipient))
        stop(); me = self; peer = recipient; conversation = id; localSession = UUID.randomUUID().toString()
        update(); send("PTT_READY", packet())
    }
    fun press() {
        require(conversation != null && remoteSession != null && remoteTurn == null && localTurn == null) { "Wait until the other person is ready and has finished speaking." }
        localTurn = UUID.randomUUID().toString(); localDeadline = clock() + 4000
        update(); send("PTT_REQUEST", packet(localTurn))
    }
    fun release() {
        val turn = localTurn
        localTurn = null; localDeadline = null; microphone = false
        update() // Mute before any signaling can wait on the network.
        if (turn != null) send("PTT_STOP", packet(turn))
    }
    fun stop(signal: Boolean = true) {
        val ending = if (conversation != null) packet() else null
        conversation = null; localTurn = null; remoteTurn = null; localDeadline = null; remoteDeadline = null
        remoteSession = null; microphone = false; update()
        if (signal && ending != null) send("PTT_END", ending)
    }
    fun receive(kind: String, value: JSONObject, verifiedPeer: String) {
        if (conversation == null) return
        val turnKind = kind !in setOf("PTT_READY", "PTT_END")
        value.exact(*(listOf("conversationId", "senderId", "recipientId", "sessionId") + if (turnKind) listOf("turnId", "forSession") else emptyList()).toTypedArray())
        val senderSession = value.getString("sessionId"); require(UUID.fromString(senderSession).toString() == senderSession)
        if (verifiedPeer != peer || value.getString("senderId") != peer || value.getString("recipientId") != me || value.getString("conversationId") != conversation) return
        if (kind == "PTT_READY") {
            if (remoteSession != senderSession) {
                release(); remoteTurn = null; remoteDeadline = null; remoteSession = senderSession
                update(); send("PTT_READY", packet())
            }
            return
        }
        if (senderSession != remoteSession) return
        if (kind == "PTT_END") {
            release(); remoteSession = null; remoteTurn = null; remoteDeadline = null; update(); return
        }
        if (value.getString("forSession") != localSession) return
        val turn = value.getString("turnId"); require(UUID.fromString(turn).toString() == turn)
        when (kind) {
            "PTT_REQUEST" -> {
                if (microphone || (localTurn != null && me < peer) || (remoteTurn != null && remoteTurn != turn)) {
                    send("PTT_BUSY", packet(turn)); return
                }
                if (localTurn != null) release() // Stable identity breaks simultaneous requests.
                if (remoteTurn != turn) { remoteTurn = turn; remoteDeadline = clock() + 30000; update() }
                send("PTT_GRANT", packet(turn))
            }
            "PTT_GRANT" -> if (turn == localTurn && !microphone && remoteTurn == null && clock() < (localDeadline ?: 0)) {
                microphone = true; localDeadline = clock() + 30000; update()
            }
            "PTT_BUSY", "PTT_STOP" -> {
                if (turn == localTurn) { localTurn = null; localDeadline = null; microphone = false }
                if (turn == remoteTurn) { remoteTurn = null; remoteDeadline = null }
                update()
            }
            else -> error("Unknown walkie-talkie control.")
        }
    }
    fun expire() {
        if (localDeadline?.let { clock() >= it } == true) release()
        if (remoteDeadline?.let { clock() >= it } == true) {
            val turn = remoteTurn; remoteTurn = null; remoteDeadline = null; update()
            if (turn != null) send("PTT_STOP", packet(turn))
        }
    }
}
