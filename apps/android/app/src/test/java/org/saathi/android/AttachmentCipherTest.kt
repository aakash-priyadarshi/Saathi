package org.saathi.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AttachmentCipherTest {
    private val key = ByteArray(32) { it.toByte() }
    private val id = "6d1f3c2a-5b4e-4c3d-8a9b-0c1d2e3f4a5b"
    private fun plain(size: Int) = ByteArray(size).also { Random(size.toLong()).nextBytes(it) }
    private fun hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun seal(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val (size, hash) = AttachmentCipher.encrypt(ByteArrayInputStream(bytes), out, key, id, FieldMedia.MAX_BYTES)
        assertEquals(bytes.size.toLong(), size); assertEquals(hex(bytes), hash)
        return out.toByteArray()
    }
    private fun open(sealed: ByteArray, size: Int, cipherSize: Long = sealed.size.toLong()): ByteArray {
        val out = ByteArrayOutputStream()
        AttachmentCipher.decrypt(ByteArrayInputStream(sealed), out, key, id, size.toLong(), cipherSize)
        return out.toByteArray()
    }
    private fun rejects(block: () -> Unit) = assertTrue(runCatching(block).isFailure)

    @Test fun roundTripsAcrossSegmentBoundaries() {
        for (size in listOf(1, 65535, 65536, 65537, 200_000, 3 * 65536)) {
            val bytes = plain(size); val sealed = seal(bytes)
            assertEquals(AttachmentCipher.v2Size(size.toLong()), sealed.size.toLong())
            assertArrayEquals(bytes, open(sealed, size))
        }
    }
    @Test fun rejectsTamperingTruncationReorderingAndExtraData() {
        val bytes = plain(3 * 65536 + 100); val sealed = seal(bytes); val segment = 65536 + 16
        rejects { open(sealed.copyOf().also { it[100] = (it[100] + 1).toByte() }, bytes.size) }
        // Dropping the final segment fails: the new last segment was sealed as "not last".
        val truncated = sealed.copyOf(8 + 3 * segment)
        rejects { open(truncated, 3 * 65536, truncated.size.toLong()) }
        val swapped = sealed.copyOf().also {
            System.arraycopy(sealed, 8 + segment, it, 8, segment); System.arraycopy(sealed, 8, it, 8 + segment, segment)
        }
        rejects { open(swapped, bytes.size) }
        rejects { open(sealed + byteArrayOf(0), bytes.size) }
        // A fresh random nonce prefix: sealing the same file twice never repeats ciphertext.
        assertFalse(seal(bytes).contentEquals(sealed))
    }
    @Test fun stillOpensLegacySingleShotAttachments() {
        val bytes = plain(5000)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ByteArray(12) { 7 }))
        cipher.updateAAD("SWARM_ATTACHMENT_V1/$id".toByteArray())
        val legacy = ByteArray(12) { 7 } + cipher.doFinal(bytes)
        assertEquals(bytes.size + 28, legacy.size)
        assertArrayEquals(bytes, open(legacy, bytes.size))
    }
    @Test fun sizesIdentifyTheVersionAndEnforceTheLimit() {
        assertTrue(AttachmentCipher.validSize(100, 100 + 28))
        assertTrue(AttachmentCipher.validSize(100, AttachmentCipher.v2Size(100)))
        assertFalse(AttachmentCipher.validSize(100, 100 + 27))
        assertFalse(AttachmentCipher.validSize(20_000_000, 20_000_028))
        rejects { AttachmentCipher.encrypt(ByteArrayInputStream(plain(70_000)), ByteArrayOutputStream(), key, id, 65_536) }
    }
}
