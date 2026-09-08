package com.drowsy.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.drowsy.BuildConfig

/**
 * Joins the ESP32's own WiFi AP by SSID/password, scoped to this app only — never touches the
 * phone's system WiFi connection or its default (cellular) route.
 *
 * Production topology: the vehicle-mounted device hosts its own network (no router, no phone
 * hotspot). A phone cannot reach devices on a hotspot it is itself hosting (Android keeps a
 * hosting phone's own app traffic on its primary connection), so the phone must join the ESP32's
 * AP as an ordinary WiFi client instead — this is exactly what WifiNetworkSpecifier is for.
 */
class DeviceWifiConnector(context: Context) {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    @RequiresApi(Build.VERSION_CODES.Q)
    fun connect(ssid: String, password: String, onConnected: (Network) -> Unit, onFailed: () -> Unit) {
        disconnect()
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (BuildConfig.DEBUG) Log.d("DeviceWifiConnector", "onAvailable: $network")
                // Do NOT bind all process traffic here — only explicit ESP32-facing calls should
                // use this network; backend/internet sync must keep using the default route.
                onConnected(network)
            }
            override fun onUnavailable() {
                if (BuildConfig.DEBUG) Log.d("DeviceWifiConnector", "onUnavailable (timeout)")
                onFailed()
            }
            override fun onLost(network: Network) {
                if (BuildConfig.DEBUG) Log.d("DeviceWifiConnector", "onLost: $network")
            }
            override fun onLosing(network: Network, maxMsToLive: Int) {
                if (BuildConfig.DEBUG) Log.d("DeviceWifiConnector", "onLosing: $network in ${maxMsToLive}ms")
            }
        }
        callback = cb
        cm.requestNetwork(request, cb, 15_000)
    }

    fun disconnect() {
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        callback = null
    }
}
