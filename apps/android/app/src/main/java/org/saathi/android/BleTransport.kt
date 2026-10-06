package org.saathi.android

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Direct Android BLE/GATT adapter. It never enables Wi-Fi or asks Google Nearby to pick a medium. */
@SuppressLint("MissingPermission") // start/connect/send validate runtime grants; teardown is best effort.
class BleTransport(context: Context, private val scope: CoroutineScope) : PeerTransport {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private val lifecycleMutex = Mutex()
    private val frameMutex = Mutex()
    private val gattOperationMutex = Mutex()
    private val reassembler = BleReassembler()
    private val peers = BlePeerRegistry()
    private val receivedAcks = ConcurrentHashMap<UUID, CompletableDeferred<Unit>>()
    private val expectedAcks = ConcurrentHashMap<UUID, ByteArray>()
    private val pendingWrite = java.util.concurrent.atomic.AtomicReference<CompletableDeferred<Unit>?>(null)
    private val pendingNotification = java.util.concurrent.atomic.AtomicReference<CompletableDeferred<Unit>?>(null)
    private val pendingDescriptor = java.util.concurrent.atomic.AtomicReference<CompletableDeferred<Unit>?>(null)
    private val pendingService = java.util.concurrent.atomic.AtomicReference<CompletableDeferred<Unit>?>(null)
    private val bondReceiverRegistered = AtomicBoolean(false)

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var gatt: BluetoothGatt? = null
    private var serverDevice: BluetoothDevice? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var cccd: BluetoothGattDescriptor? = null
    private var role = Role.NONE
    private var automatic = false
    private var localAdvertisementId = ByteArray(8)
    private var automaticConnectPending = false
    private var mtu = 23
    private var linkReady = false
    private var serverNotificationsEnabled = false
    private var localApproved = false
    private var remoteApproved = false
    private var pairCode: String? = null
    private var generation = 0L
    private var visibleTimer: Job? = null
    private var peerExpiryJob: Job? = null
    private var pairingTimer: Job? = null
    private var cleanupJob: Job? = null
    private var bondRetry: (() -> Unit)? = null
    @Volatile override var connected = false
        private set
    @Volatile override var session = UUID.randomUUID().toString()
        private set
    override val maximumFrameBytes: Int
        get() = if (mtu < BleFrameCodec.MIN_MTU) 0 else minOf(BleFrameCodec.MAX_FRAME_BYTES, (mtu - 3 - BleFrameCodec.HEADER_AND_CRC_BYTES) * BleFrameCodec.MAX_CHUNKS)
    override val mediaAvailable = false
    override val supportsFiles = false
    override val capabilities = setOf(TransportCapability.TEXT, TransportCapability.SMALL_CONTROL, TransportCapability.SMALL_STRUCTURED_EVENT)
    val available: Boolean
        get() = adapter?.isEnabled == true && adapter?.bluetoothLeScanner != null && adapter?.bluetoothLeAdvertiser != null

    var onState: (String) -> Unit = {}
    var onPeers: (Map<String, String>) -> Unit = {}
    var onPair: (String?) -> Unit = {}
    var onFrame: (JSONObject) -> Unit = {}
    var onError: (String) -> Unit = {}

    private enum class Role { NONE, AUTO, CENTRAL, PERIPHERAL }
    private fun scanFilter(auto: Boolean) = if (auto) ScanFilter.Builder().setServiceData(ParcelUuid(SERVICE_UUID), null).build()
        else ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()

    private fun log(event: String, detail: String = "") {
        if (BuildConfig.DEBUG) Log.d(TAG, "$event role=$role mtu=$mtu connected=$connected generation=$generation $detail")
    }

    private fun status(text: String) {
        log("state", text)
        onState(text)
    }

    private fun hasPermission(permission: String) =
        appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            check(hasPermission(Manifest.permission.BLUETOOTH_SCAN) && hasPermission(Manifest.permission.BLUETOOTH_CONNECT) && hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
                "Allow Bluetooth access in Android settings to connect nearby."
            }
        } else {
            check(hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) { "Allow nearby access in Android settings to find Bluetooth devices." }
        }
        check(adapter?.isEnabled == true) { "Turn on Bluetooth to connect by Bluetooth." }
    }

    suspend fun start(advertise: Boolean, auto: Boolean = false) {
        while (true) {
            val cleanup = cleanupJob
            cleanup?.join()
            var started = false
            lifecycleMutex.withLock {
                if (cleanupJob !== cleanup) return@withLock
                ensurePermissions()
                stopScanningAndAdvertising()
                closeGatt()
                generation++
                val token = generation
                automatic = auto
                automaticConnectPending = false
                localAdvertisementId = ByteArray(8).also(SecureRandom()::nextBytes)
                role = if (auto) Role.AUTO else if (advertise) Role.PERIPHERAL else Role.CENTRAL
                connected = false; linkReady = false; localApproved = false; remoteApproved = false; pairCode = null; mtu = 23
                peers.clear(); onPeers(emptyMap()); onPair(null); reassembler.clear()
                if (advertise || auto) {
                    startGattServer()
                    val radio = adapter?.bluetoothLeAdvertiser ?: error("Bluetooth Low Energy advertising is unavailable on this phone.")
                    advertiser = radio
                    activeAdvertiseCallback = advertiseCallback(token)
                    val advertisement = AdvertiseData.Builder().setIncludeDeviceName(false)
                    val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(false)
                    if (auto) advertisement.addServiceData(ParcelUuid(SERVICE_UUID), localAdvertisementId)
                    else {
                        advertisement.addServiceUuid(ParcelUuid(SERVICE_UUID))
                        scanResponse.addServiceData(ParcelUuid(SERVICE_UUID), localAdvertisementId)
                    }
                    radio.startAdvertising(
                        AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW).setConnectable(true).build(),
                        advertisement.build(),
                        scanResponse.build(),
                        activeAdvertiseCallback,
                    )
                }
                if (!advertise || auto) {
                    val radio = adapter?.bluetoothLeScanner ?: error("Bluetooth Low Energy scanning is unavailable on this phone.")
                    scanner = radio
                    activeScanCallback = scanCallback(token)
                    radio.startScan(listOf(scanFilter(auto)), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), activeScanCallback)
                    peerExpiryJob?.cancel()
                    peerExpiryJob = scope.launch {
                        while (token == generation) {
                            delay(5_000)
                            if (token == generation) onPeers(peers.prune(android.os.SystemClock.elapsedRealtime()))
                        }
                    }
                }
                status(if (auto) "Nearby connection changed. Looking for the same person by Bluetooth…" else if (advertise) "Visible to nearby Swarm by Bluetooth for one minute" else "Looking for Swarm by Bluetooth for one minute")
                visibleTimer = scope.launch {
                    delay(60_000)
                    if (token == generation) {
                        stopScanningAndAdvertising()
                        peers.clear(); onPeers(emptyMap())
                        if (!connected) status("Bluetooth search finished. Search again when both people are ready.")
                    }
                }
                started = true
            }
            if (started) return
        }
    }

    private suspend fun startGattServer() {
        val server = manager?.openGattServer(appContext, serverCallback) ?: error("This phone could not open a Bluetooth connection.")
        gattServer = server
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val receive = BluetoothGattCharacteristic(
            RX_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM,
        )
        val send = BluetoothGattCharacteristic(
            TX_UUID, BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM,
        )
        val descriptor = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED_MITM or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED_MITM,
        )
        send.addDescriptor(descriptor)
        service.addCharacteristic(receive); service.addCharacteristic(send)
        rx = receive; tx = send; cccd = descriptor
        val added = CompletableDeferred<Unit>()
        pendingService.set(added)
        check(server.addService(service)) { "This phone could not start a Bluetooth connection." }
        try { withTimeout(8000) { added.await() } }
        catch (error: TimeoutCancellationException) { error("Bluetooth service setup timed out. Try again nearby.") }
        finally { pendingService.compareAndSet(added, null) }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (status == BluetoothGatt.GATT_SUCCESS) pendingService.get()?.complete(Unit)
            else pendingService.get()?.completeExceptionally(IllegalStateException("Bluetooth service could not start ($status)"))
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            log("server-connection-state", "status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (serverDevice != null && serverDevice?.address != device.address) {
                    gattServer?.cancelConnection(device)
                    log("server-connection-rejected", "reason=single-peer-limit")
                    return
                }
                serverDevice = device
                if (role == Role.AUTO) role = Role.PERIPHERAL
                visibleTimer?.cancel(); visibleTimer = null
                stopScanningAndAdvertising()
                status("Bluetooth link found. Completing secure pairing…")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED && serverDevice?.address == device.address) {
                resetLink("Bluetooth connection lost. Your saved messages are safe.")
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtuValue: Int) {
            if (serverDevice?.address != device.address) return
            mtu = mtuValue.coerceAtLeast(23)
            log("server-mtu", "value=$mtu")
            if (linkReady && maximumFrameBytes == 0) rejectSmallMtu()
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            log("server-cccd-write-request", "descriptor=${descriptor.uuid} characteristic=${descriptor.characteristic.uuid} offset=$offset prepared=$preparedWrite bytes=${value.size}")
            if (descriptor.uuid != CCCD_UUID || descriptor.characteristic.uuid != TX_UUID || offset != 0 || preparedWrite) {
                if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                return
            }
            val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            val code = if (enabled) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            if (responseNeeded) gattServer?.sendResponse(device, requestId, code, 0, null)
            if (enabled && code == BluetoothGatt.GATT_SUCCESS) {
                serverNotificationsEnabled = true
                linkReady = true
                log("server-indications-ready")
            }
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            log("server-write-request", "characteristic=${characteristic.uuid} offset=$offset prepared=$preparedWrite bytes=${value.size}")
            if (characteristic.uuid != RX_UUID || preparedWrite || offset != 0 || value.size > mtu - 3 || serverDevice?.address != device.address) {
                if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH, 0, null)
                return
            }
            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            receivePacket(value, mtu)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            log("server-indication-sent", "status=$status")
            val pending = pendingNotification.getAndSet(null) ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) pending.complete(Unit)
            else pending.completeExceptionally(IllegalStateException("Bluetooth indication failed ($status)"))
        }
    }

    private fun advertiseCallback(token: Long) = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            if (token == generation) log("advertising-started", "mode=${settingsInEffect.mode}")
        }
        override fun onStartFailure(errorCode: Int) {
            if (token != generation) return
            log("advertising-failed", "status=$errorCode")
            onError("Bluetooth visibility could not start. Your saved work is safe; try again.")
            status("Bluetooth visibility could not start. Try again when ready.")
        }
    }

    private fun scanCallback(token: Long) = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (token != generation || connected) return
            val address = result.device.address
            val advertisementId = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID))
            val updatedPeers = peers.observe(address, advertisementId, android.os.SystemClock.elapsedRealtime(), BuildConfig.BRAND_DISPLAY)
            onPeers(updatedPeers)
            if (automatic && role == Role.AUTO && !automaticConnectPending) {
                val peerId = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
                val order = compareUnsigned(localAdvertisementId, peerId)
                if (order < 0) {
                    role = Role.CENTRAL
                    automaticConnectPending = true
                    scope.launch {
                        runCatching { connect(address) }.onFailure {
                            automaticConnectPending = false
                            if (token == generation) {
                                log("automatic-connect-failed", it.javaClass.simpleName)
                                onError("Nearby reconnection did not finish. Your messages remain saved; tap Find by Bluetooth to retry.")
                            }
                        }
                    }
                } else if (order > 0) {
                    role = Role.PERIPHERAL
                }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            if (token == generation) { log("scan-failed", "status=$errorCode"); peers.clear(); onPeers(emptyMap()); onError("Bluetooth search stopped. Your saved work is safe.") }
        }
    }

    private fun compareUnsigned(first: ByteArray, second: ByteArray): Int {
        for (index in 0 until minOf(first.size, second.size)) {
            val difference = (first[index].toInt() and 0xff) - (second[index].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return first.size - second.size
    }

    suspend fun connect(address: String) = lifecycleMutex.withLock {
        ensurePermissions()
        require(role == Role.CENTRAL && peers.contains(address)) { "This Bluetooth device is no longer available. Search again." }
        val device = adapter?.getRemoteDevice(address) ?: error("This Bluetooth device is unavailable.")
        visibleTimer?.cancel(); visibleTimer = null; stopScanningAndAdvertising()
        val token = generation
        @Suppress("DEPRECATION")
        val client = device.connectGatt(appContext, false, clientCallback(token), BluetoothDevice.TRANSPORT_LE)
        gatt = client
        require(gatt != null) { "A Bluetooth connection could not start. Try again nearby." }
        status("Connecting by Bluetooth…")
    }

    private fun clientCallback(token: Long) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(client: BluetoothGatt, status: Int, newState: Int) {
            if (token != generation) { client.close(); return }
            log("client-connection-state", "status=$status newState=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (gatt === client) resetLink("Bluetooth connection changed. Your saved messages are safe.")
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = client
                if (!client.discoverServices()) failLink("Bluetooth services could not be found. Try again nearby.")
            }
        }

        override fun onServicesDiscovered(client: BluetoothGatt, status: Int) {
            if (token != generation) return
            if (status != BluetoothGatt.GATT_SUCCESS) { failLink("Bluetooth services could not be found. Try again nearby."); return }
            val service = client.getService(SERVICE_UUID)
            tx = service?.getCharacteristic(TX_UUID); rx = service?.getCharacteristic(RX_UUID)
            val descriptor = tx?.getDescriptor(CCCD_UUID)
            if (tx == null || rx == null || descriptor == null) { failLink("This device does not support Swarm Bluetooth messaging."); return }
            cccd = descriptor
            val requested = runCatching { client.requestMtu(REQUESTED_MTU) }.getOrDefault(false)
            if (!requested) enableIndications(client, token)
        }

        override fun onMtuChanged(client: BluetoothGatt, mtuValue: Int, status: Int) {
            if (token != generation) return
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) mtuValue.coerceAtLeast(23) else 23
            log("client-mtu", "status=$status")
            enableIndications(client, token)
        }

        override fun onDescriptorWrite(client: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (token != generation || descriptor.uuid != CCCD_UUID) return
            log("client-descriptor-write", "status=$status")
            if (status == GATT_INSUFFICIENT_AUTHENTICATION || status == GATT_INSUFFICIENT_ENCRYPTION) {
                log("link-bond-required", "status=$status")
                pendingDescriptor.getAndSet(null)?.completeExceptionally(IllegalStateException("Secure Bluetooth pairing is required."))
                bondRetry = { enableIndications(client, token) }
                requestBond(client.device)
                return
            }
            val pending = pendingDescriptor.getAndSet(null)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                pending?.completeExceptionally(IllegalStateException("Bluetooth pairing setup failed ($status)"))
                failLink("Secure Bluetooth pairing could not finish. Try again and approve Android’s pairing prompt.")
                return
            }
            pending?.complete(Unit)
            linkReady = true
            if (maximumFrameBytes == 0) { rejectSmallMtu(); return }
            log("client-indications-ready")
            scope.launch { repeatHello(token) }
        }

        override fun onCharacteristicWrite(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (token != generation || characteristic.uuid != RX_UUID) return
            val pending = pendingWrite.getAndSet(null) ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) pending.complete(Unit)
            else if (status == GATT_INSUFFICIENT_AUTHENTICATION || status == GATT_INSUFFICIENT_ENCRYPTION) {
                pending.completeExceptionally(IllegalStateException("Secure Bluetooth pairing is required ($status)"))
                requestBond(client.device)
            } else pending.completeExceptionally(IllegalStateException("Bluetooth write failed ($status)"))
        }

        override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (token == generation && characteristic.uuid == TX_UUID) receivePacket(value, mtu)
        }

        @Suppress("DEPRECATION")
        @Deprecated("Required for Android 8 through Android 12 compatibility")
        override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (token == generation && characteristic.uuid == TX_UUID) characteristic.value?.let { receivePacket(it, mtu) }
        }
    }

    private fun enableIndications(client: BluetoothGatt, token: Long) {
        if (token != generation || pendingDescriptor.get() != null) return
        val characteristic = tx ?: return
        val descriptor = cccd ?: return
        if (!client.setCharacteristicNotification(characteristic, true)) { failLink("Bluetooth notifications could not be enabled."); return }
        val pending = CompletableDeferred<Unit>()
        pendingDescriptor.set(pending)
        val started = if (Build.VERSION.SDK_INT >= 33) {
            client.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            @Suppress("DEPRECATION") client.writeDescriptor(descriptor)
        }
        if (!started) {
            pendingDescriptor.compareAndSet(pending, null)
            failLink("Secure Bluetooth pairing could not start. Try again nearby.")
        }
    }

    private fun requestBond(device: BluetoothDevice) {
        registerBondReceiver()
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            val started = runCatching { device.createBond() }.getOrDefault(false)
            log("bond-request", "started=$started")
            if (!started) failLink("Approve Bluetooth pairing in Android to continue.")
        } else bondRetry?.invoke()
    }

    private fun registerBondReceiver() {
        if (!bondReceiverRegistered.compareAndSet(false, true)) return
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= 33) appContext.registerReceiver(bondReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") appContext.registerReceiver(bondReceiver, filter)
        } catch (error: Exception) {
            bondReceiverRegistered.set(false)
            log("bond-receiver-failed", error.javaClass.simpleName)
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            if (state == BluetoothDevice.BOND_BONDED && device?.address == gatt?.device?.address) {
                log("bond-complete")
                bondRetry?.invoke()
                bondRetry = null
            } else if (state == BluetoothDevice.BOND_NONE && device?.address == gatt?.device?.address) {
                failLink("Android Bluetooth pairing was declined. Your saved work is safe.")
            }
        }
    }

    private suspend fun repeatHello(token: Long) {
        repeat(3) {
            if (token != generation || pairCode != null || !linkReady) return
            runCatching { sendControl(BlePacketKind.HELLO) }
                .onFailure { if (it !is CancellationException) log("hello-retry", it.javaClass.simpleName) }
            if (withTimeoutOrNull(2_000) { while (pairCode == null && token == generation) delay(50); true } == true) return
        }
        if (token == generation && pairCode == null) failLink("The nearby device did not finish secure Bluetooth pairing. Try again.")
    }

    override suspend fun send(frame: JSONObject) = frameMutex.withLock {
        ensurePermissions()
        require(connected && linkReady) { "Connect and compare the Bluetooth code first." }
        val raw = frame.toString().toByteArray(StandardCharsets.UTF_8)
        require(raw.size <= maximumFrameBytes && raw.isNotEmpty()) { "This message is too large for Bluetooth. It is saved on this phone." }
        val objectId = frame.optString("id").ifBlank { frame.hashCode().toString() }
        val frameId = UUID.randomUUID()
        val objectHash = BleFrameCodec.objectIdHash(objectId)
        val fragments = BleFrameCodec.fragment(raw, objectId, mtu, frameId)
        val acknowledged = CompletableDeferred<Unit>()
        receivedAcks[frameId] = acknowledged; expectedAcks[frameId] = objectHash
        try {
            repeat(MAX_FRAME_ATTEMPTS) { attempt ->
                for (fragment in fragments) sendGattPacket(fragment)
                if (withTimeoutOrNull(ACK_TIMEOUT_MS) { acknowledged.await(); true } == true) {
                    log("frame-acknowledged", "bytes=${raw.size} chunks=${fragments.size} attempt=${attempt + 1}")
                    return@withLock
                }
                log("frame-retry", "bytes=${raw.size} chunks=${fragments.size} attempt=${attempt + 1}")
            }
            throw IllegalStateException("Bluetooth message acknowledgement timed out. The saved message is safe to retry.")
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { runCatching { sendControl(BlePacketKind.CANCEL, frameId = frameId) } }
            throw cancelled
        } finally {
            receivedAcks.remove(frameId); expectedAcks.remove(frameId)
        }
    }

    private suspend fun sendControl(kind: BlePacketKind, payload: ByteArray = byteArrayOf(), frameId: UUID = UUID.randomUUID()) =
        sendGattPacket(BleFrameCodec.control(kind, payload, frameId))

    @Suppress("DEPRECATION")
    private suspend fun sendGattPacket(packet: ByteArray) = gattOperationMutex.withLock {
        ensurePermissions()
        check(linkReady) { "Bluetooth connection is not ready." }
        require(packet.isNotEmpty() && packet.size <= mtu - 3) { "Bluetooth packet exceeds the negotiated link size." }
        try { withTimeout(GATT_OPERATION_TIMEOUT_MS) {
            when (role) {
                Role.CENTRAL -> {
                    val client = gatt ?: error("Bluetooth connection ended.")
                    val characteristic = rx ?: error("Bluetooth message channel is unavailable.")
                    val pending = CompletableDeferred<Unit>(); pendingWrite.set(pending)
                    try {
                        val started = if (Build.VERSION.SDK_INT >= 33) {
                            client.writeCharacteristic(characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                        } else {
                            characteristic.value = packet
                            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            client.writeCharacteristic(characteristic)
                        }
                        if (!started) error("Bluetooth write could not start.")
                        pending.await()
                    } finally { pendingWrite.compareAndSet(pending, null) }
                }
                Role.PERIPHERAL -> {
                    val server = gattServer ?: error("Bluetooth connection ended.")
                    val device = serverDevice ?: error("Bluetooth connection ended.")
                    val characteristic = tx ?: error("Bluetooth message channel is unavailable.")
                    check(serverNotificationsEnabled) { "Secure Bluetooth channel is not ready." }
                    val pending = CompletableDeferred<Unit>(); pendingNotification.set(pending)
                    try {
                        val started = if (Build.VERSION.SDK_INT >= 33) {
                            server.notifyCharacteristicChanged(device, characteristic, true, packet) == BluetoothStatusCodes.SUCCESS
                        } else {
                            characteristic.value = packet
                            server.notifyCharacteristicChanged(device, characteristic, true)
                        }
                        if (!started) error("Bluetooth indication could not start.")
                        pending.await()
                    } finally { pendingNotification.compareAndSet(pending, null) }
                }
                Role.AUTO -> error("Waiting for the Bluetooth connection role to be selected.")
                Role.NONE -> error("Bluetooth connection ended.")
            }
        } } catch (timeout: TimeoutCancellationException) {
            failLink("The Bluetooth connection stopped responding. Your saved message is safe to retry.")
            throw timeout
        }
    }

    private fun receivePacket(raw: ByteArray, receivedMtu: Int) {
        if (raw.size > receivedMtu - 3 || raw.size > 512) { log("packet-rejected", "reason=size bytes=${raw.size}"); return }
        val packet = runCatching { BleFrameCodec.decode(raw, receivedMtu) }.getOrElse {
            log("packet-rejected", "reason=${it.javaClass.simpleName}"); return
        }
        when (packet.kind) {
            BlePacketKind.ACK -> {
                if (expectedAcks[packet.frameId]?.contentEquals(packet.objectId) == true) receivedAcks[packet.frameId]?.complete(Unit)
            }
            BlePacketKind.CANCEL -> {
                reassembler.cancel(packet.frameId)
                receivedAcks.remove(packet.frameId)?.cancel()
                expectedAcks.remove(packet.frameId)
                log("frame-cancelled")
            }
            BlePacketKind.DATA -> {
                if (!connected) return
                val result = runCatching { reassembler.accept(packet) }.getOrElse {
                    log("frame-rejected", "reason=${it.javaClass.simpleName}"); return
                }
                if (result.complete != null) {
                    val bytes = result.complete
                    val decoded = runCatching {
                        val text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                        JSONObject(text)
                    }.getOrElse { log("frame-rejected", "reason=invalid-json"); return }
                    scope.launch { runCatching { onFrame(decoded); sendGattPacket(BleFrameCodec.ack(packet.frameId, packet.objectId)) }
                        .onFailure { if (it !is CancellationException) log("frame-delivery-ack-failed", it.javaClass.simpleName) } }
                } else if (result.duplicateComplete) {
                    scope.launch { runCatching { sendGattPacket(BleFrameCodec.ack(packet.frameId, packet.objectId)) } }
                }
            }
            BlePacketKind.HELLO -> {
                log("hello-received", "role=$role linkReady=$linkReady")
                if (role == Role.PERIPHERAL && linkReady) {
                    val code = pairCode ?: "%06d".format(SecureRandom().nextInt(1_000_000)).also { pairCode = it; startPairTimeout(generation) }
                    scope.launch { runCatching { sendControl(BlePacketKind.PAIR_CODE, code.toByteArray(StandardCharsets.US_ASCII)); onPair(code) }
                        .onFailure { if (it !is CancellationException) log("pair-code-send-failed", it.javaClass.simpleName) } }
                }
            }
            BlePacketKind.PAIR_CODE -> if (role == Role.CENTRAL && linkReady) {
                val code = runCatching { packet.payload.toString(StandardCharsets.US_ASCII) }.getOrNull()
                if (code?.matches(Regex("[0-9]{6}")) == true) { log("pair-code-received", "bytes=${packet.payload.size}"); pairCode = code; startPairTimeout(generation); onPair(code) }
                else failLink("The nearby Bluetooth pairing code was invalid.")
            }
            BlePacketKind.APPROVE -> {
                remoteApproved = true
                if (localApproved) finishPair()
            }
            BlePacketKind.DENY -> failLink("Bluetooth pairing was declined. Your saved work is safe.")
        }
    }

    private fun startPairTimeout(token: Long) {
        pairingTimer?.cancel()
        pairingTimer = scope.launch {
            delay(PAIRING_TIMEOUT_MS)
            if (token == generation && !connected) failLink("Bluetooth pairing timed out. Try again when both people are ready.")
        }
    }

    fun confirm(match: Boolean) {
        if (!linkReady || pairCode == null || localApproved || connected) return
        if (!match) {
            scope.launch { runCatching { sendControl(BlePacketKind.DENY) }; disconnect() }
            onPair(null)
            return
        }
        localApproved = true
        onPair(null)
        scope.launch {
            try {
                sendControl(BlePacketKind.APPROVE)
                if (remoteApproved) finishPair()
            } catch (error: Exception) {
                if (error !is CancellationException) failLink("Secure Bluetooth pairing could not finish. Your saved work is safe.")
            }
        }
    }

    private fun finishPair() {
        if (connected || !localApproved || !remoteApproved) return
        pairingTimer?.cancel(); pairingTimer = null; pairCode = null; connected = true; session = UUID.randomUUID().toString()
        onPair(null); status("Connected by Bluetooth. Text and small Swarm updates are ready.")
    }

    private fun rejectSmallMtu() {
        failLink("This Bluetooth link cannot carry Swarm messages safely. Your saved work is still on this phone.")
    }

    private fun failLink(message: String) {
        log("link-failed", message)
        connected = false; linkReady = false
        onError(message)
        scope.launch { disconnect() }
    }

    private fun resetLink(message: String) {
        connected = false; linkReady = false; localApproved = false; remoteApproved = false; pairCode = null
        reassembler.clear(); pairingTimer?.cancel(); pairingTimer = null
        session = UUID.randomUUID().toString(); onPair(null); status(message)
        pendingWrite.getAndSet(null)?.completeExceptionally(IllegalStateException("Bluetooth connection ended."))
        pendingNotification.getAndSet(null)?.completeExceptionally(IllegalStateException("Bluetooth connection ended."))
        receivedAcks.values.forEach { it.completeExceptionally(IllegalStateException("Bluetooth connection ended.")) }
    }

    private fun stopScanningAndAdvertising() {
        visibleTimer?.cancel(); visibleTimer = null
        peerExpiryJob?.cancel(); peerExpiryJob = null
        runCatching { scanner?.stopScan(activeScanCallback) }
        runCatching { advertiser?.stopAdvertising(activeAdvertiseCallback) }
        scanner = null; advertiser = null
    }

    // Callback instances are retained so Android can match them during stopScan/stopAdvertising.
    private var activeScanCallback: ScanCallback? = null
    private var activeAdvertiseCallback: AdvertiseCallback? = null

    fun stopScan() {
        val previous = cleanupJob
        cleanupJob = scope.launch {
            previous?.join()
            lifecycleMutex.withLock {
                if (!connected) generation++
                stopScanningAndAdvertising()
                peers.clear(); onPeers(emptyMap())
            }
        }
    }

    private suspend fun closeGatt() {
        pairingTimer?.cancel(); pairingTimer = null; linkReady = false; connected = false
        gatt?.let { client ->
            runCatching { client.disconnect() }
            runCatching { client.close() }
        }
        gatt = null
        serverDevice?.let { device -> runCatching { gattServer?.cancelConnection(device) } }
        runCatching { gattServer?.close() }; gattServer = null; serverDevice = null
        unregisterBondReceiver()
        tx = null; rx = null; cccd = null; serverNotificationsEnabled = false; role = Role.NONE; mtu = 23
        pendingDescriptor.getAndSet(null)?.cancel()
        pendingService.getAndSet(null)?.cancel()
        receivedAcks.values.forEach { it.completeExceptionally(IllegalStateException("Bluetooth connection ended.")) }
        receivedAcks.clear(); expectedAcks.clear(); reassembler.clear()
    }

    private fun unregisterBondReceiver() {
        if (bondReceiverRegistered.compareAndSet(true, false)) runCatching { appContext.unregisterReceiver(bondReceiver) }
    }

    private suspend fun disconnectLocked() {
        stopScanningAndAdvertising(); closeGatt(); peers.clear(); onPeers(emptyMap()); onPair(null)
    }

    override fun disconnect() {
        generation++
        connected = false; onPair(null)
        val previous = cleanupJob
        cleanupJob = scope.launch {
            previous?.join()
            lifecycleMutex.withLock { disconnectLocked() }
        }
        session = UUID.randomUUID().toString()
        status("Bluetooth connection ended. Your saved work is safe.")
    }

    private companion object {
        const val TAG = "SwarmBLE"
        const val REQUESTED_MTU = 247
        const val MAX_FRAME_ATTEMPTS = 2
        const val ACK_TIMEOUT_MS = 5_000L
        const val GATT_OPERATION_TIMEOUT_MS = 8_000L
        const val PAIRING_TIMEOUT_MS = 60_000L
        const val GATT_INSUFFICIENT_AUTHENTICATION = 5
        const val GATT_INSUFFICIENT_ENCRYPTION = 15
        val SERVICE_UUID: UUID = UUID.fromString("a2fd4300-7057-4bb2-a376-dfb09d6d3e31")
        val RX_UUID: UUID = UUID.fromString("a2fd4301-7057-4bb2-a376-dfb09d6d3e31")
        val TX_UUID: UUID = UUID.fromString("a2fd4302-7057-4bb2-a376-dfb09d6d3e31")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
