package com.drowsy.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.drowsy.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

enum class CameraConnectionState { IDLE, CONNECTING, LIVE, OFFLINE }

/**
 * Low-latency snapshot polling against ESP32 /snapshot (Connection: close per frame).
 */
class NetworkCameraSource(
    private val baseUrl: String,
    private val preferMjpeg: Boolean = false,
    private val reconnectDelayMs: Long = 800,
    private val connectTimeoutMs: Int = 2000,
    private val readTimeoutMs: Int = 2000,
    private val snapshotTargetFps: Int = 15,
    private val network: android.net.Network? = null,
) : CameraSource {

    override val name: String = "NetworkCamera:$baseUrl"
    override var isRunning: Boolean = false
        private set

    private val _connection = MutableStateFlow(CameraConnectionState.IDLE)
    val connection: StateFlow<CameraConnectionState> = _connection.asStateFlow()

    @Volatile private var latencyMs: Long = -1
    fun lastLatencyMs(): Long = latencyMs

    private fun openConn(url: URL): HttpURLConnection =
        (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection

    override fun start() {
        isRunning = true
        _connection.value = CameraConnectionState.CONNECTING
    }

    override fun stop() {
        isRunning = false
        _connection.value = CameraConnectionState.IDLE
    }

    override fun frames(): Flow<CameraFrame> = flow {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "frames mode=${if (preferMjpeg) "mjpeg" else "snapshot"} url=$baseUrl")
        }
        var offlineStreak = 0
        while (isRunning) {
            try {
                if (preferMjpeg) emitMjpeg(this) else emitSnapshotLoop(this)
                offlineStreak = 0
            } catch (e: Exception) {
                offlineStreak++
                _connection.value = CameraConnectionState.OFFLINE
                if (BuildConfig.DEBUG) Log.w(TAG, "camera loop: ${e.message}")
                delay(reconnectDelayMs.coerceAtMost(3000))
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun configure(conn: HttpURLConnection) {
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.useCaches = false
        conn.setRequestProperty("Connection", "close")
        conn.setRequestProperty("Cache-Control", "no-cache")
    }

    private suspend fun emitSnapshotLoop(collector: kotlinx.coroutines.flow.FlowCollector<CameraFrame>) {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = 1
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val frameGapMs = (1000L / snapshotTargetFps.coerceIn(5, 20))
        var seq = 0L
        var failures = 0
        while (isRunning) {
            coroutineContext.ensureActive()
            val t0 = System.currentTimeMillis()
            var conn: HttpURLConnection? = null
            try {
                val url = URL("$baseUrl/snapshot?t=${System.currentTimeMillis()}&n=${seq++}")
                conn = openConn(url).apply {
                    requestMethod = "GET"
                    configure(this)
                }
                if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.size < 100) throw IllegalStateException("empty frame")
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    ?: throw IllegalStateException("decode failed")
                latencyMs = System.currentTimeMillis() - t0
                failures = 0
                _connection.value = CameraConnectionState.LIVE
                collector.emit(CameraFrame(bmp, System.currentTimeMillis()))
            } catch (_: Exception) {
                failures++
                if (failures >= 3) _connection.value = CameraConnectionState.OFFLINE
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
            val elapsed = System.currentTimeMillis() - t0
            val sleep = frameGapMs - elapsed
            if (sleep > 0) delay(sleep)
        }
    }

    private suspend fun emitMjpeg(collector: kotlinx.coroutines.flow.FlowCollector<CameraFrame>) {
        val url = URL("$baseUrl/stream")
        var conn: HttpURLConnection? = null
        try {
            conn = openConn(url).apply {
                requestMethod = "GET"
                configure(this)
                readTimeout = 4000
                setRequestProperty("Accept", "multipart/x-mixed-replace")
            }
            conn.connect()
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val input = conn.inputStream
            val buffer = ByteArray(16 * 1024)
            var leftover = ByteArray(0)
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            while (isRunning) {
                coroutineContext.ensureActive()
                val n = try { input.read(buffer) } catch (_: Exception) { break }
                if (n <= 0) break
                leftover = leftover + buffer.copyOfRange(0, n)
                var lastStart = -1
                var lastEnd = -1
                var i = 0
                while (i < leftover.size - 1) {
                    if (leftover[i] == 0xFF.toByte() && leftover[i + 1] == 0xD8.toByte()) lastStart = i
                    if (leftover[i] == 0xFF.toByte() && leftover[i + 1] == 0xD9.toByte() && lastStart >= 0) lastEnd = i + 1
                    i++
                }
                if (lastStart >= 0 && lastEnd > lastStart) {
                    val jpeg = leftover.copyOfRange(lastStart, lastEnd + 1)
                    leftover = if (lastEnd + 1 < leftover.size) leftover.copyOfRange(lastEnd + 1, leftover.size) else ByteArray(0)
                    val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
                    if (bmp != null) {
                        _connection.value = CameraConnectionState.LIVE
                        collector.emit(CameraFrame(bmp, System.currentTimeMillis()))
                    }
                } else if (leftover.size > 128 * 1024) {
                    leftover = leftover.copyOfRange(leftover.size - 32 * 1024, leftover.size)
                }
            }
        } finally {
            try { conn?.inputStream?.close() } catch (_: Exception) {}
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "NetworkCameraSource"
    }
}
