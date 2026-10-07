package org.saathi.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/** Private records are independently authenticated with a Keystore key and record-specific AAD. */
class SecureStore(context: Context, private val storageScope: String = BuildConfig.ENVIRONMENT) : SQLiteOpenHelper(context, "saathi-$storageScope.db", null, 1) {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val encryptionAlias = "saathi.$storageScope.storage"
    init {
        val existingPrivate = context.getDatabasePath("saathi-$storageScope.db").exists() && readableDatabase.rawQuery("SELECT COUNT(*) FROM records WHERE private=1", null).use { it.moveToFirst(); it.getInt(0) > 0 }
        if (!keyStore.containsAlias(encryptionAlias) && !existingPrivate) KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(encryptionAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generateKey()
        }
    }
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE records (bucket TEXT NOT NULL, id TEXT NOT NULL, data BLOB NOT NULL, private INTEGER NOT NULL, PRIMARY KEY(bucket,id))") }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Unsupported local storage version; no silent data reset.") }
    private fun encryptionKey() = keyStore.getKey(encryptionAlias, null) as? SecretKey ?: error("Android storage key is unavailable. Saved private work was not reset.")
    fun encrypt(bytes: ByteArray, aad: String): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, encryptionKey()); updateAAD(aad.toByteArray()); iv + doFinal(bytes)
    }
    fun decrypt(bytes: ByteArray, aad: String): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        require(bytes.size >= 28); init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))); updateAAD(aad.toByteArray()); doFinal(bytes.copyOfRange(12, bytes.size))
    }
    @Synchronized fun put(bucket: String, id: String, value: JSONObject, private: Boolean = true) {
        val raw = value.toString().toByteArray(); require(raw.size <= if (bucket in setOf("chat-policies", "chat-policy-history", "chat-joins", "chat-join-inbox")) 524288 else 150000)
        val data = if (private) encrypt(raw, "$bucket/$id") else raw
        writableDatabase.insertWithOnConflict("records", null, ContentValues().apply { put("bucket", bucket); put("id", id); put("data", data); put("private", if (private) 1 else 0) }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }
    @Synchronized fun get(bucket: String, id: String): JSONObject? = readableDatabase.query("records", arrayOf("data", "private"), "bucket=? AND id=?", arrayOf(bucket, id), null, null, null).use {
        if (!it.moveToFirst()) null else JSONObject(String(if (it.getInt(1) == 1) decrypt(it.getBlob(0), "$bucket/$id") else it.getBlob(0)))
    }
    @Synchronized fun count(bucket: String): Long = android.database.DatabaseUtils.longForQuery(readableDatabase, "SELECT COUNT(*) FROM records WHERE bucket=?", arrayOf(bucket))
    @Synchronized fun all(bucket: String): List<JSONObject> = readableDatabase.query("records", arrayOf("id", "data", "private"), "bucket=?", arrayOf(bucket), null, null, "rowid DESC", "500").use {
        buildList { while (it.moveToNext()) add(JSONObject(String(if (it.getInt(2) == 1) decrypt(it.getBlob(1), "$bucket/${it.getString(0)}") else it.getBlob(1)))) }
    }
    @Synchronized fun remove(bucket: String, id: String) { writableDatabase.delete("records", "bucket=? AND id=?", arrayOf(bucket, id)) }
    /** Acquire the store monitor before the SQLite transaction so all callers use one lock order. */
    @Synchronized fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }
    fun signingAlias(account: String) = "saathi.$storageScope.author.$account"
    fun hasIdentity(account: String) = keyStore.containsAlias(signingAlias(account))
    fun ensureIdentity(account: String) {
        val alias = signingAlias(account)
        if (keyStore.containsAlias(alias)) return
        fun generate(strongBox: Boolean) {
            val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256)
            if (Build.VERSION.SDK_INT >= 28) builder.setIsStrongBoxBacked(strongBox)
            KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply { initialize(builder.build()); generateKeyPair() }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try { generate(true) } catch (_: StrongBoxUnavailableException) { generate(false) }
        } else generate(false)
    }
    fun privateKey(account: String) = keyStore.getKey(signingAlias(account), null) as PrivateKey
    fun publicKey(account: String) = keyStore.getCertificate(signingAlias(account)).publicKey as ECPublicKey
    fun securityDescription(): String {
        if (!keyStore.containsAlias(encryptionAlias)) return "Android storage key is unavailable. Saved private work was not reset"
        val info = SecretKeyFactory.getInstance("AES", "AndroidKeyStore").getKeySpec(encryptionKey(), KeyInfo::class.java) as KeyInfo
        return if (Build.VERSION.SDK_INT >= 31 && info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX) "Protected by Android StrongBox" else if (info.isInsideSecureHardware) "Protected by Android secure hardware" else "Protected by Android Keystore"
    }
    @Synchronized fun clearPrivate() {
        // Removing rows and deleting all account signing keys prevents reuse after account switching.
        writableDatabase.delete("records", "private=1 AND bucket != 'trust'", null)
        keyStore.aliases().toList().filter { it.startsWith("saathi.$storageScope.author.") }.forEach { keyStore.deleteEntry(it) }
    }
    fun deleteIdentity(account: String) { keyStore.deleteEntry(signingAlias(account)) }
}
