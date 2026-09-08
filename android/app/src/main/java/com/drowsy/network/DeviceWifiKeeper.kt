package com.drowsy.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import com.drowsy.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Keeps the phone attached to the vehicle camera AP and verifies reachability with periodic pings.
 * Uses a persistent NetworkCallback (not one-shot) so we detect drops and can re-request join.
 */
class DeviceWifiKeeper(
    context: Context,
    private val cameraBaseUrl: String,
    private val ssid: String,
    private val password: String,
) {
    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pingJob: Job? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var boundNetwork: Network? = null

    private val _linked = MutableStateFlow(false)
    val linked: StateFlow<Boolean> = _linked.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(okhttp3.ConnectionPool(1, 30, TimeUnit.SECONDS))
        .build()

    fun start() {
        if (pingJob != null) return
        requestJoin()
        bindBestLocalWifi()
        pingJob = scope.launch {
            while (isActive) {
                _linked.value = ping()
                delay(2500)
            }
        }
    }

    fun stop() {
        pingJob?.cancel()
        pingJob = null
        callback?.let { cb ->
            try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }
        callback = null
        try { cm.bindProcessToNetwork(null) } catch (_: Exception) {}
        boundNetwork = null
        _linked.value = false
    }

    private fun requestJoin() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        callback?.let { cb ->
            try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()
        val builder = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            @Suppress("WrongConstant")
            builder.addCapability(26) // NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK (API 31+)
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (BuildConfig.DEBUG) Log.d(TAG, "camera network available: $network")
                boundNetwork = network
                try { cm.bindProcessToNetwork(network) } catch (_: Exception) {}
            }
            override fun onLost(network: Network) {
                if (BuildConfig.DEBUG) Log.d(TAG, "camera network lost: $network")
                if (boundNetwork == network) {
                    boundNetwork = null
                    try { cm.bindProcessToNetwork(null) } catch (_: Exception) {}
                    bindBestLocalWifi()
                }
            }
            override fun onUnavailable() {
                if (BuildConfig.DEBUG) Log.d(TAG, "camera network unavailable")
            }
        }
        callback = cb
        cm.requestNetwork(builder.build(), cb)
    }

    /** Fallback when user joined DRIVER-CAM manually in system settings. */
    private fun bindBestLocalWifi() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            // ESP32 AP: no internet — prefer WiFi without validated internet route.
            if (!hasInternet || !isValidated) {
                boundNetwork = network
                try {
                    cm.bindProcessToNetwork(network)
                    if (BuildConfig.DEBUG) Log.d(TAG, "bound to local wifi $network")
                } catch (_: Exception) {}
                return
            }
        }
    }

    private fun ping(): Boolean {
        val url = if (cameraBaseUrl.endsWith("/")) "${cameraBaseUrl}ping" else "$cameraBaseUrl/ping"
        return try {
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "DeviceWifiKeeper"
    }
}
