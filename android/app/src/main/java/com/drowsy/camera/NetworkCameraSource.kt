package com.drowsy.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * ESP32-S3 / generic MJPEG + snapshot (§17-20).
 * Supports: GET /stream (MJPEG multipart) and GET /snapshot (single JPEG).
 * Handles reconnect, timeout, malformed frames, dropped frames, latency.
 * Fatigue pipeline sees only CameraFrame Flow.
 */
class NetworkCameraSource(
    private val baseUrl: String, // e.g. http://192.168.4.1
    private val preferMjpeg: Boolean = true,
    private val reconnectDelayMs: Long = 2000,
    private val jpegQualityTimeoutMs: Int = 4000,
    // When set (via WifiNetworkSpecifier), requests route through this specific WiFi network
    // instead of the phone's default route — needed since the ESP32's AP has no internet and
    // Android would otherwise prefer cellular for a bare URL.openConnection().
    private val network: android.net.Network? = null,
) : CameraSource {

    override val name: String = "NetworkCamera:$baseUrl"
    override var isRunning: Boolean = false
        private set

    private fun openConn(url: URL): HttpURLConnection =
        (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection

    @Volatile private var latencyMs: Long = -1
    fun lastLatencyMs(): Long = latencyMs

    override fun start() { isRunning = true }
    override fun stop() { isRunning = false }

    override fun frames(): Flow<CameraFrame> = flow {
        Log.d("NetworkCameraSource", "frames() started, isRunning=$isRunning, network=$network, baseUrl=$baseUrl")
        while (isRunning) {
            try {
                if (preferMjpeg) emitMjpeg(this) else emitSnapshotLoop(this)
            } catch (e: Exception) {
                Log.e("NetworkCameraSource", "frames() loop exception: ${e.javaClass.simpleName}: ${e.message}", e)
                // reconnect with backoff
                delay(reconnectDelayMs)
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun emitMjpeg(collector: kotlinx.coroutines.flow.FlowCollector<CameraFrame>) {
        val url = URL("$baseUrl/stream")
        var conn: HttpURLConnection? = null
        try {
            conn = openConn(url).apply {
                requestMethod = "GET"
                connectTimeout = jpegQualityTimeoutMs
                readTimeout = 5000 // allow cancellation via timeout, not infinite block
                setRequestProperty("Accept", "multipart/x-mixed-replace")
            }
            Log.d("NetworkCameraSource", "emitMjpeg connecting to $url via network=$network")
            conn.connect()
            Log.d("NetworkCameraSource", "emitMjpeg connected, responseCode=${conn.responseCode}")
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val input = conn.inputStream
            val buffer = ByteArray(64 * 1024)
            var leftover = ByteArray(0)
            val opts = BitmapFactory.Options().apply { inSampleSize = 1; inPreferredConfig = Bitmap.Config.RGB_565 }
            while (isRunning) {
                coroutineContext.ensureActive()
                val n = try { input.read(buffer) } catch (_: Exception) { break }
                if (n <= 0) break
                val chunk = leftover + buffer.copyOf(n)
                var start = -1; var end = -1
                for (i in 0 until chunk.size - 1) {
                    if (chunk[i] == 0xFF.toByte() && chunk[i+1] == 0xD8.toByte() && start==-1) start=i
                    if (chunk[i] == 0xFF.toByte() && chunk[i+1] == 0xD9.toByte() && start!=-1) { end=i+1; break }
                }
                if (start!=-1 && end!=-1) {
                    val jpeg = chunk.copyOfRange(start, end+1)
                    val t0 = System.currentTimeMillis()
                    val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
                    if (bmp != null) {
                        latencyMs = System.currentTimeMillis() - t0
                        collector.emit(CameraFrame(bmp, System.currentTimeMillis()))
                    }
                    leftover = if (end+1 < chunk.size) chunk.copyOfRange(end+1, chunk.size) else ByteArray(0)
                } else {
                    leftover = chunk
                    if (leftover.size > 512*1024) leftover = leftover.copyOfRange(leftover.size - 256*1024, leftover.size)
                }
            }
        } finally { try { conn?.inputStream?.close() } catch (_: Exception) {}; try { conn?.disconnect() } catch (_: Exception) {} }
    }

    private suspend fun emitSnapshotLoop(collector: kotlinx.coroutines.flow.FlowCollector<CameraFrame>) {
        val opts = BitmapFactory.Options().apply { inSampleSize = 1; inPreferredConfig = Bitmap.Config.RGB_565 }
        while (isRunning) {
            coroutineContext.ensureActive()
            val t0 = System.currentTimeMillis()
            var conn: HttpURLConnection? = null
            try {
                val url = URL("$baseUrl/snapshot")
                conn = openConn(url).apply {
                    connectTimeout = jpegQualityTimeoutMs; readTimeout = jpegQualityTimeoutMs
                }
                val bytes = conn.inputStream.readBytes()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                if (bmp != null) {
                    latencyMs = System.currentTimeMillis() - t0
                    collector.emit(CameraFrame(bmp, System.currentTimeMillis()))
                }
            } catch (_: Exception) { /* dropped frame — tolerated */ }
            finally { try { conn?.disconnect() } catch (_: Exception) {} }
            delay(100) // ~10 FPS snapshot polling (§19)
        }
    }
}
