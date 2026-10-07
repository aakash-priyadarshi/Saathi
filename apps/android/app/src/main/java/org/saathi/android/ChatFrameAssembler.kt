package org.saathi.android

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Bounded, connection-local assembly. Completed frames still pass normal chat authorization. */
internal class ChatFrameAssembler(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    companion object {
        const val MAX_BYTES = 524288
        const val MAX_PARTS = 4096
        const val EXPIRY_MS = 120000L
        fun parts(frame: JSONObject, frameBudget: Int): List<JSONObject> {
            val raw = frame.toString().toByteArray(Charsets.UTF_8)
            require(raw.size <= MAX_BYTES)
            val chunkBytes = minOf(12288, ((frameBudget - 512) * 3 / 4)).coerceAtLeast(1)
            val count = (raw.size + chunkBytes - 1) / chunkBytes
            require(count in 1..MAX_PARTS && frameBudget >= 768) { "This Bluetooth connection is too small for a channel roster. Reconnect or use local Wi-Fi." }
            val digest = Protocol.digest(raw)
            return (0 until count).map { part ->
                obj("id" to frame.getString("id"), "hash" to digest, "part" to part, "total" to count,
                    "bytes" to raw.size, "data" to Protocol.b64(raw.copyOfRange(part * chunkBytes, minOf(raw.size, (part + 1) * chunkBytes))))
            }
        }
    }
    private data class Assembly(val hash: String, val size: Int, val started: Long, val parts: Array<ByteArray?>, var received: Int = 0)
    private val pending = linkedMapOf<String, Assembly>()
    fun clear() = pending.clear()
    fun accept(value: JSONObject): JSONObject? {
        pending.entries.removeAll { clock() - it.value.started >= EXPIRY_MS }
        value.exact("id", "hash", "part", "total", "bytes", "data")
        val id = value.getString("id"); require(UUID.fromString(id).toString() == id)
        val hash = value.getString("hash"); require(hash.matches(Regex("[a-f0-9]{64}")))
        val total = value.getInt("total"); val part = value.getInt("part"); val size = value.getInt("bytes")
        require(total in 1..MAX_PARTS && part in 0 until total && size in 1..MAX_BYTES && value.getString("data").length <= 18000)
        val data = Protocol.decode(value.getString("data")); require(data.isNotEmpty() && data.size <= 12288)
        require(id in pending || pending.size < 2) { "Too many channel transfers. Reconnect to retry." }
        val assembly = pending.getOrPut(id) { Assembly(hash, size, clock(), arrayOfNulls(total)) }
        require(assembly.hash == hash && assembly.size == size && assembly.parts.size == total)
        val previous = assembly.parts[part]
        require(previous == null || previous.contentEquals(data)) { "Conflicting channel transfer." }
        if (previous == null) {
            if (assembly.received + data.size > size) { pending.remove(id); error("Channel transfer exceeds its budget.") }
            assembly.parts[part] = data; assembly.received += data.size
        }
        if (assembly.parts.any { it == null }) return null
        pending.remove(id)
        val output = ByteArrayOutputStream(size); assembly.parts.forEach { output.write(it!!) }
        val raw = output.toByteArray(); require(raw.size == size && Protocol.digest(raw) == hash)
        val frame = JSONObject(String(raw, Charsets.UTF_8)); frame.exact("v", "kind", "id", "value")
        require(frame.getInt("v") == 1 && frame.getString("id") == id && frame.getString("kind").startsWith("CHAT_") && frame.getString("kind") != "CHAT_CHUNK")
        return frame
    }
}
