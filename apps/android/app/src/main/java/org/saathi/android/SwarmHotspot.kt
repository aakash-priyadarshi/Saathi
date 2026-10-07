package org.saathi.android

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * A local-only Wi-Fi hotspot (no internet) that nearby phones join so Nearby can find everyone on one network,
 * in both directions. iPhones cannot be discovered by Android over Bluetooth, so this is the two-way path for
 * mixed groups; it also gives Wi-Fi speed for photos and videos. Android chooses the name and password.
 */
class SwarmHotspot(context: Context) {
    data class Network(val ssid: String, val password: String) {
        /** Standard Wi-Fi QR text: the iPhone Camera and Android camera/settings can join from it. */
        val qr get() = "WIFI:T:WPA;S:${escape(ssid)};P:${escape(password)};;"
        private fun escape(value: String) = value.replace(Regex("""([\\;,:"])"""), """\\$1""")
    }
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    val active get() = reservation != null

    @Suppress("DEPRECATION")
    fun start(onStarted: (Network) -> Unit, onFailed: (String) -> Unit) {
        if (reservation != null) return
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(value: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = value
                    val network = if (Build.VERSION.SDK_INT >= 30) {
                        val config = value.softApConfiguration
                        val ssid = if (Build.VERSION.SDK_INT >= 33) config.wifiSsid?.toString()?.trim('"') else config.ssid
                        Network(ssid.orEmpty(), config.passphrase.orEmpty())
                    } else Network(value.wifiConfiguration?.SSID.orEmpty().trim('"'), value.wifiConfiguration?.preSharedKey.orEmpty().trim('"'))
                    if (network.ssid.isBlank()) { stop(); onFailed("The hotspot started without a network name. Try again."); return }
                    onStarted(network)
                }
                override fun onStopped() { reservation = null; onFailed("The Swarm hotspot stopped.") }
                override fun onFailed(reason: Int) {
                    reservation = null
                    onFailed(when (reason) {
                        ERROR_TETHERING_DISALLOWED -> "This phone does not allow hotspots."
                        ERROR_INCOMPATIBLE_MODE -> "Turn off this phone's own hotspot or Wi-Fi Direct, then try again."
                        ERROR_NO_CHANNEL -> "No Wi-Fi channel is free for a hotspot right now."
                        else -> "The Swarm hotspot could not start."
                    })
                }
            }, Handler(Looper.getMainLooper()))
        } catch (error: SecurityException) {
            onFailed("Allow nearby devices for Swarm, then start the hotspot again.")
        } catch (error: IllegalStateException) {
            onFailed("A hotspot is already running on this phone.")
        }
    }
    fun stop() { reservation?.close(); reservation = null }
}
