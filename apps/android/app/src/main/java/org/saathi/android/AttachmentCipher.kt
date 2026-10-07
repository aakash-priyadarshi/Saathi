package org.saathi.android

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Private chat attachment encryption with a fresh 256-bit key per attachment.
 *
 * V1 (legacy, files up to 16 MB): 12-byte IV ‖ AES-GCM(whole file), AAD `SWARM_ATTACHMENT_V1/<id>`.
 * Ciphertext size = plain + 28. Android cannot decrypt GCM incrementally, so V1 needs the file in memory.
 *
 * V2 (streaming, up to 250 MB): 8-byte random nonce prefix ‖ sealed segments. Plaintext segment i
 * (64 KiB, the last may be shorter) is sealed with AES-GCM, nonce = prefix ‖ i (big-endian), AAD
 * `SWARM_ATTACHMENT_V2/<id>/<i>/<last>`. Binding the index and the final flag rejects reordered,
 * dropped or truncated segments (the STREAM construction). Ciphertext size = plain + 8 + 16 × segments.
 *
 * 16 × segments never equals 20, so the signed plain/cipher sizes alone identify the version.
 */
object AttachmentCipher {
    const val SEGMENT = 65536
    private const val V1_MAX_PLAIN = 16777188L
    fun segments(plain: Long) = maxOf(1L, (plain + SEGMENT - 1) / SEGMENT)
    fun v2Size(plain: Long) = plain + 8 + 16 * segments(plain)
    fun validSize(plain: Long, cipher: Long) =
        plain > 0 && (cipher == v2Size(plain) || (cipher == plain + 28 && plain <= V1_MAX_PLAIN))

    /** Reads until [buffer] is full or the stream ends; `readNBytes` needs Android 13. */
    fun readFully(input: InputStream, buffer: ByteArray, length: Int = buffer.size): Int {
        var n = 0
        while (n < length) { val read = input.read(buffer, n, length - n); if (read < 0) break; n += read }
        return n
    }
    private fun cipher(mode: Int, key: ByteArray, prefix: ByteArray, index: Long, id: String, last: Boolean): Cipher {
        require(index < 0x1_0000_0000L) { "Attachment is too large." }
        val nonce = ByteBuffer.allocate(12).put(prefix).putInt(index.toInt()).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD("SWARM_ATTACHMENT_V2/$id/$index/${if (last) 1 else 0}".toByteArray())
        }
    }
    private fun hex(digest: MessageDigest) = digest.digest().joinToString("") { "%02x".format(it) }

    /** Encrypts [input] as V2. Returns the plaintext size and SHA-256, which the signed message carries. */
    fun encrypt(input: InputStream, output: OutputStream, key: ByteArray, id: String, maxPlain: Long): Pair<Long, String> {
        val prefix = ByteArray(8).also { SecureRandom().nextBytes(it) }
        output.write(prefix)
        val digest = MessageDigest.getInstance("SHA-256")
        var current = ByteArray(SEGMENT); var next = ByteArray(SEGMENT)
        var length = readFully(input, current); var index = 0L; var total = 0L
        require(length > 0) { "Choose a file that is not empty." }
        while (true) {
            // Look one segment ahead so the final segment is known without the file size.
            val nextLength = if (length == SEGMENT) readFully(input, next) else 0
            total += length; require(total <= maxPlain) { "Choose a file of 250 MB or less." }
            digest.update(current, 0, length)
            output.write(cipher(Cipher.ENCRYPT_MODE, key, prefix, index, id, nextLength == 0).doFinal(current, 0, length))
            if (nextLength == 0) break
            val swap = current; current = next; next = swap; length = nextLength; index++
        }
        return total to hex(digest)
    }

    /** Decrypts V1 or V2 into [output]. Returns the plaintext SHA-256; callers compare it with the signed hash. */
    fun decrypt(input: InputStream, output: OutputStream, key: ByteArray, id: String, plain: Long, cipherSize: Long): String {
        require(validSize(plain, cipherSize)) { "Private attachment size does not match its description." }
        val digest = MessageDigest.getInstance("SHA-256")
        if (cipherSize == plain + 28) {
            val all = ByteArrayOutputStream(cipherSize.toInt()).also { input.copyTo(it) }.toByteArray()
            require(all.size.toLong() == cipherSize) { "Private attachment is incomplete." }
            val v1 = Cipher.getInstance("AES/GCM/NoPadding")
            v1.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, all, 0, 12))
            v1.updateAAD("SWARM_ATTACHMENT_V1/$id".toByteArray())
            val bytes = v1.doFinal(all, 12, all.size - 12); digest.update(bytes); output.write(bytes)
            return hex(digest)
        }
        val prefix = ByteArray(8); require(readFully(input, prefix) == 8) { "Private attachment is incomplete." }
        val count = segments(plain); val sealed = ByteArray(SEGMENT + 16)
        for (index in 0 until count) {
            val last = index == count - 1
            val length = (if (last) plain - index * SEGMENT else SEGMENT.toLong()).toInt() + 16
            require(readFully(input, sealed, length) == length) { "Private attachment is incomplete." }
            val bytes = cipher(Cipher.DECRYPT_MODE, key, prefix, index, id, last).doFinal(sealed, 0, length)
            digest.update(bytes); output.write(bytes)
        }
        require(input.read() < 0) { "Private attachment has unexpected extra data." }
        return hex(digest)
    }
}
