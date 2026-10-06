package org.saathi.android

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream

class Repository(context: Context, storageScope: String = BuildConfig.ENVIRONMENT) {
    private class ApiFailure(val status: Int, message: String) : IllegalStateException(message)
    val store = SecureStore(context, storageScope)
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val launchClock = Instant.now(); private val launchElapsed = SystemClock.elapsedRealtime()
    var configuration: JSONObject? = null; private set
    var needsEnabled: Boolean = store.get("public", "runtime-config")?.optJSONObject("features")?.optBoolean("needs", true) ?: true; private set
    var reachable = false; private set
    var lastChecked: String? = null; private set
    private val root = JSONObject(BuildConfig.CONFIG_ROOT)
    private val writeLock = Mutex()
    val environment get() = BuildConfig.ENVIRONMENT
    val preparation get() = store.get("account", "preparation")
    val featureFlags get() = configuration?.getJSONObject("body")?.getJSONObject("features")
    fun events() = store.all("events")
    fun snapshot(bucket: String): List<JSONObject> {
        val ids = store.get("public", "$bucket-order")?.optJSONArray("ids")?.strings() ?: emptyList()
        return store.all(bucket).sortedBy { ids.indexOf(it.optString("publicId", it.optString("id"))).let { index -> if (index < 0) Int.MAX_VALUE else index } }
    }
    fun requests(completed: Boolean = false) = snapshot(if (completed) "completed" else "requests")
    fun clock(): Instant {
        val wall = Instant.now(); val monotonic = launchClock.plusMillis(SystemClock.elapsedRealtime() - launchElapsed)
        val high = store.get("trust", "clock")?.optString("time")?.let { Instant.parse(it) }
        require(wall >= monotonic.minusSeconds(300) && (high == null || wall >= high.minusSeconds(300))) { "Check the phone’s date and time. Saved work is safe." }
        if (high == null || wall > high) store.put("trust", "clock", obj("time" to wall.toString()))
        return wall
    }
    suspend fun refreshConfiguration() = withContext(Dispatchers.IO) {
        val cached = store.get("public", "configuration")
        val version = store.get("trust", "version")?.optLong("version", 1) ?: 1
        val now = clock()
        val sources = (BuildConfig.BOOTSTRAP.split(',').filter { it.isNotBlank() } + (cached?.getJSONObject("body")?.getJSONArray("apiEndpoints")?.strings() ?: emptyList()).map { it.trimEnd('/') + "/api/v1/sync/service-config" }).distinct().take(6)
        var failure = "Swarm service information is not provisioned for this build."
        for (source in sources) {
            try {
                require(source.startsWith("https://") || (environment == "development" && (source.startsWith("http://127.0.0.1:") || source.startsWith("http://localhost:"))))
                val value = JSONObject(fetch(source, null, false, 32768))
                configuration = ServiceConfiguration.verify(value, root, environment, BuildConfig.VERSION_CODE, cached, version, now)
                store.put("public", "configuration", value, false)
                store.put("trust", "version", obj("version" to value.getJSONObject("body").getLong("version")))
                return@withContext
            } catch (e: Exception) { failure = if (e is java.io.IOException) "Swarm service information could not be reached. Saved work is safe. Reconnect and try again." else e.message ?: "Service information is unavailable." }
        }
        if (cached != null) {
            configuration = ServiceConfiguration.verify(cached, root, environment, BuildConfig.VERSION_CODE, cached, version, now)
            return@withContext
        }
        error(failure)
    }
    private fun fetch(url: String, body: JSONObject?, authenticated: Boolean, limit: Int = 2 * 1024 * 1024, method: String = if (body == null) "GET" else "POST", idempotencyKey: String? = null): String {
        val request = Request.Builder().url(url).header("Accept", "application/json").header("X-Swarm-App-Version", BuildConfig.VERSION_NAME)
        if (body != null) {
            request.method(method, body.toString().toRequestBody("application/json".toMediaType()))
            configuration?.getJSONObject("body")?.getString("webOrigin")?.let { request.header("Origin", it) }
            request.header("Idempotency-Key", idempotencyKey ?: UUID.randomUUID().toString())
        }
        val saved = store.get("credentials", "session")
        if (authenticated && saved?.optString("origin") == okhttp3.HttpUrl.Builder().scheme(Request.Builder().url(url).build().url.scheme).host(Request.Builder().url(url).build().url.host).port(Request.Builder().url(url).build().url.port).build().toString()) {
            request.header("Cookie", saved.getString("cookies")); request.header("X-CSRF-Token", saved.getString("csrf"))
        }
        client.newCall(request.build()).execute().use { response ->
            val responseBody = response.body ?: error("Swarm returned an empty response.")
            require(responseBody.contentLength() <= limit) { "Swarm returned too much information." }
            val buffer = ByteArrayOutputStream().apply {
                responseBody.byteStream().use { input ->
                    val chunk = ByteArray(8192)
                    while (true) { val count = input.read(chunk); if (count < 0) break; require(size() + count <= limit); write(chunk, 0, count) }
                }
            }.toByteArray()
            require(buffer.size <= limit)
            if (!response.isSuccessful) {
                if (authenticated && response.code in listOf(401, 403)) store.remove("credentials", "session")
                val message = runCatching { JSONObject(String(buffer)).get("message").toString().take(300) }.getOrDefault("Swarm could not complete this action (${response.code}).")
                throw ApiFailure(response.code, message)
            }
            if (url.endsWith("/auth/login")) {
                val cookies = response.headers.values("Set-Cookie").map { it.substringBefore(';') }
                require(cookies.any { it.startsWith("saathi_session=") }) { "Sign-in did not return a session." }
                val parsedUrl = response.request.url
                val origin = parsedUrl.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
                store.put("credentials", "session", obj("origin" to origin, "cookies" to cookies.joinToString("; "), "csrf" to cookies.first { it.startsWith("saathi_csrf=") }.substringAfter('=')))
            }
            return String(buffer)
        }
    }
    suspend fun api(path: String, body: JSONObject? = null, authenticated: Boolean = false, method: String = if (body == null) "GET" else "POST", idempotencyKey: String? = null, responseLimit:Int=2*1024*1024): String = withContext(Dispatchers.IO) {
        val config = configuration ?: error("Verified service information is unavailable. Saved work is safe.")
        try { ServiceConfiguration.verify(config, root, environment, BuildConfig.VERSION_CODE, config, config.getJSONObject("body").getLong("version"), clock()) } catch (e: Exception) { reachable = false; throw e }
        val endpoints = config.getJSONObject("body").getJSONArray("apiEndpoints").strings()
        var failure: Exception? = null
        for (endpoint in endpoints) {
            try { return@withContext fetch(endpoint.trimEnd('/') + "/api/v1" + path, body, authenticated, limit=responseLimit, method = method, idempotencyKey = idempotencyKey).also { reachable = true; lastChecked = Instant.now().toString() } }
            catch (e: Exception) {
                if (e is ApiFailure) { reachable = true; lastChecked = Instant.now().toString(); throw e }
                failure = if (e is java.io.IOException) IllegalStateException("Swarm could not be reached. Saved work is safe. Reconnect and retry the original action.", e) else e; if (body != null) break /* Signed event retries are explicit; ordinary writes never guess across endpoints. */
            }
        }
        reachable = false; throw failure ?: IllegalStateException("Swarm is unavailable.")
    }
    private suspend fun applyRuntimeConfig(value: JSONObject): Boolean = withContext(Dispatchers.IO) {
        val enabled = value.optJSONObject("features")?.optBoolean("needs", true) ?: true
        val changed = enabled != needsEnabled
        needsEnabled = enabled
        store.put("public", "runtime-config", value, false)
        if (!enabled) {
            store.writableDatabase.beginTransaction()
            try {
                for (bucket in listOf("requests", "completed")) {
                    store.all(bucket).forEach { row -> store.remove(bucket, row.optString("publicId", row.optString("id"))) }
                    store.remove("public", "$bucket-order")
                }
                store.writableDatabase.setTransactionSuccessful()
            } finally { store.writableDatabase.endTransaction() }
        }
        changed
    }
    suspend fun refresh() {
        refreshConfiguration()
        applyRuntimeConfig(JSONObject(api("/public/config")))
        for ((bucket, path) in listOf("requests" to "/public/requests", "completed" to "/public/requests?completed=true", "feed" to "/public/feed")) {
            val list = JSONArray(api(path))
            withContext(Dispatchers.IO) {
                // Snapshot replacement is atomic so withdrawn entries are not retained as active requests.
                store.writableDatabase.beginTransaction()
                try {
                    list.objects().forEach { store.put(bucket, it.optString("publicId", it.optString("id")), it, false) }
                    store.put("public", "$bucket-order", obj("ids" to JSONArray(list.objects().map { it.optString("publicId", it.optString("id")) })), false)
                    store.all(bucket).filter { old -> list.objects().none { it.optString("publicId", it.optString("id")) == old.optString("publicId", old.optString("id")) } }.forEach { store.remove(bucket, it.optString("publicId", it.optString("id"))) }
                    store.put("public", "freshness", obj("savedAt" to Instant.now().toString()), false); store.writableDatabase.setTransactionSuccessful()
                } finally { store.writableDatabase.endTransaction() }
            }
        }
    }
    /** Persist the original request and retry key before a write. An unknown response is never a new intent. */
    suspend fun write(intent: String, path: String, body: JSONObject, authenticated: Boolean = false): JSONObject = writeLock.withLock {
        withContext(Dispatchers.IO) {
            val old = store.get("operations", intent)
            require(old == null || (old.getString("path") == path && old.getBoolean("authenticated") == authenticated && Protocol.hash(old.getJSONObject("body")) == Protocol.hash(body))) { "An earlier attempt has not been resolved. Retry its original details from Saved before changing them." }
            require(old != null || store.all("operations").size < 128) { "Saved actions are full. Review completed actions before continuing." }
            val operation = old ?: obj("id" to intent, "path" to path, "body" to JSONObject(body.toString()), "authenticated" to authenticated, "key" to UUID.randomUUID().toString(), "createdAt" to Instant.now().toString()).also { store.put("operations", intent, it) }
            val result = operation.optJSONObject("response") ?: JSONObject(api(path, operation.getJSONObject("body"), authenticated, idempotencyKey = operation.getString("key"))).also {
                operation.put("response", it); store.put("operations", intent, operation)
            }
            if (path == "/donations") store.put("donations", result.getString("trackingToken"), result)
            result
        }
    }
    suspend fun retryWrite(operation: JSONObject) = write(operation.getString("id"), operation.getString("path"), operation.getJSONObject("body"), operation.getBoolean("authenticated"))
    suspend fun checkReachability() {
        runCatching {
            // If the app started without internet, there is no verified endpoint yet and api()
            // cannot recover on its own. Re-fetch signed configuration when reconnecting.
            if (configuration == null || !reachable) refreshConfiguration()
            val changed = applyRuntimeConfig(JSONObject(api("/public/config")))
            if (changed) refresh()
        }
    }
    suspend fun login(email: String, password: String, totp: String) {
        val previous = store.get("account", "user")
        require(previous == null || previous.optString("email").equals(email, ignoreCase = true)) { "Sign out and clear the previous person’s private work before switching accounts." }
        api("/auth/login", obj("email" to email, "password" to password).apply { if (totp.isNotBlank()) put("totp", totp) })
        val user = JSONObject(api("/auth/me", authenticated = true))
        if (previous != null && previous.getString("id") != user.getString("id")) {
            store.remove("credentials", "session")
            error("This is a different account identity. Sign out and clear the previous person’s private work before switching accounts.")
        }
        store.put("account", "user", user)
        prepare()
    }
    suspend fun prepare() {
        val value = JSONObject(api("/sync/preparation", authenticated = true))
        val account = value.getJSONObject("user").getString("id")
        val previous = preparation
        require(previous == null || previous.getJSONObject("user").getString("id") == account) { "Sign out and clear the previous person’s saved work before switching accounts." }
        withContext(Dispatchers.IO) { store.ensureIdentity(account) }
        val existing = store.get("account", "device")
        if (existing != null) {
            val devices = JSONArray(api("/sync/devices", authenticated = true)).objects()
            if (devices.none { it.getString("id") == existing.getString("id") && it.isNull("revokedAt") }) {
                store.remove("account", "device"); store.deleteIdentity(account); store.ensureIdentity(account)
            }
        }
        if (store.get("account", "device") == null) store.put("account", "device", JSONObject(api("/sync/devices", Protocol.publicJwk(store.publicKey(account)), true)))
        store.put("account", "preparation", value)
    }
    suspend fun logout(revoke: Boolean) {
        if (revoke) { val device = store.get("account", "device") ?: error("No prepared device."); api("/sync/devices/${device.getString("id")}/revoke", obj(), true) }
        runCatching { api("/auth/logout", obj(), true) }
        withContext(Dispatchers.IO) { store.clearPrivate() }
    }
    suspend fun author(type: String, payload: JSONObject, organization: String): JSONObject = withContext(Dispatchers.IO) {
        clock()
        require(events().size < 500) { "Saved update storage is full. Review and clear synchronized work before creating another update." }
        val account = preparation?.getJSONObject("user")?.getString("id") ?: error("Sign in and prepare offline publishing first. You can still save a draft.")
        val device = store.get("account", "device")?.getString("id") ?: error("Prepare this phone before publishing.")
        val envelope = Protocol.envelope(type, payload, account, organization, device, store.privateKey(account), store.publicKey(account))
        obj("id" to envelope.getJSONObject("body").getString("id"), "envelope" to envelope, "hops" to 0, "own" to true, "savedAt" to Instant.now().toString()).also { store.put("events", it.getString("id"), it) }
    }
    fun receiveEvent(envelope: JSONObject, hops: Int): JSONObject {
        require(Protocol.validEnvelope(envelope, hops)) { "This shared update is expired or could not be checked." }
        val id = envelope.getJSONObject("body").getString("id")
        val old = store.get("events", id)
        require(old == null || Protocol.hash(old.getJSONObject("envelope")) == Protocol.hash(envelope)) { "This update has conflicting contents." }
        if (old != null) return old
        require(events().size < 500) { "Saved update storage is full. Synchronize or clear reviewed work first." }
        return obj("id" to id, "envelope" to envelope, "hops" to hops, "own" to false, "savedAt" to Instant.now().toString()).also { store.put("events", id, it) }
    }
    fun acceptReceipt(receipt: JSONObject) {
        val id = receipt.getJSONObject("body").getString("eventId"); val event = store.get("events", id) ?: return
        val keys = configuration?.getJSONObject("body")?.getJSONArray("receiptKeys") ?: error("Reconnect to verify Swarm’s confirmation.")
        require(Protocol.validReceipt(receipt, event.getJSONObject("envelope"), keys)) { "Swarm’s confirmation could not be checked." }
        val old = event.optJSONObject("receipt")
        if (old != null && Instant.parse(old.getJSONObject("body").getString("recordedAt")) > Instant.parse(receipt.getJSONObject("body").getString("recordedAt"))) return
        event.put("receipt", receipt); event.remove("error"); store.put("events", id, event)
    }
    suspend fun sync(includeCarried: Boolean) {
        refreshConfiguration()
        val carrier = store.get("relay", "identity") ?: obj("id" to UUID.randomUUID().toString()).also { store.put("relay", "identity", it) }
        for (event in events().filter { (it.optBoolean("own") || includeCarried) && !it.has("receipt") }.sortedByDescending { it.getJSONObject("envelope").getJSONObject("body").getJSONObject("payload").optString("priority") == "URGENT" }) {
            try { acceptReceipt(JSONObject(api("/sync/events", obj("envelope" to event.getJSONObject("envelope"), "carrierId" to carrier.getString("id"))))) }
            catch (e: Exception) { event.put("error", e.message); store.put("events", event.getString("id"), event); throw e }
        }
        for (batch in events().chunked(50)) JSONArray(api("/sync/receipts", obj("ids" to JSONArray(batch.map { it.getString("id") })))).objects().forEach { acceptReceipt(it) }
    }
}
