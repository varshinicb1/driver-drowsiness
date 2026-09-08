package com.drowsy.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import com.drowsy.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Joins the ESP32 AP via WifiNetworkSpecifier. Callbacks are delivered on the main thread
 * and guarded against duplicate onAvailable / onUnavailable races.
 */
class DeviceWifiConnector(context: Context) {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val finished = AtomicBoolean(false)

    @RequiresApi(Build.VERSION_CODES.Q)
    fun connect(ssid: String, password: String, onConnected: (Network) -> Unit, onFailed: () -> Unit) {
        disconnect()
        finished.set(false)
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
                if (!finished.compareAndSet(false, true)) return
                if (BuildConfig.DEBUG) Log.d(TAG, "onAvailable: $network")
                mainHandler.post {
                    try { cm.unregisterNetworkCallback(this) } catch (_: Exception) {}
                    callback = null
                    onConnected(network)
                }
            }
            override fun onUnavailable() {
                if (!finished.compareAndSet(false, true)) return
                if (BuildConfig.DEBUG) Log.d(TAG, "onUnavailable")
                mainHandler.post {
                    callback = null
                    onFailed()
                }
            }
            override fun onLost(network: Network) {
                if (BuildConfig.DEBUG) Log.d(TAG, "onLost: $network")
            }
        }
        callback = cb
        cm.requestNetwork(request, cb, 20_000)
    }

    fun disconnect() {
        finished.set(true)
        callback?.let { cb ->
            try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }
        callback = null
    }

    companion object {
        private const val TAG = "DeviceWifiConnector"
    }
}
