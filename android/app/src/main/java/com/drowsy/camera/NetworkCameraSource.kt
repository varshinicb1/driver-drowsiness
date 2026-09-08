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
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

enum class CameraConnectionState { IDLE, CONNECTING, LIVE, OFFLINE }

/**
 * Vehicle camera client — persistent MJPEG over OkHttp for ultra-low latency,
 * snapshot fallback when the stream drops.
 */
class NetworkCameraSource(
    private val baseUrl: String,
    private val preferMjpeg: Boolean = true,
    private val reconnectDelayMs: Long = 600,
    private val connectTimeoutMs: Int = 5000,
    private val readTimeoutMs: Int = 8000,
    private val snapshotTargetFps: Int = 12,
    private val network: android.net.Network? = null,
) : CameraSource {

    override val name: String = "NetworkCamera:$baseUrl"
    override var isRunning: Boolean = false
        private set

    private val _connection = MutableStateFlow(CameraConnectionState.IDLE)
    val connection: StateFlow<CameraConnectionState> = _connection.asStateFlow()

    @Volatile private var latencyMs: Long = -1
    fun lastLatencyMs(): Long = latencyMs

    private val streamClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(1, 1, TimeUnit.MINUTES))
            .apply { network?.let { socketFactory(it.socketFactory) } }
            .build()
    }

    private val snapshotClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(2, 30, TimeUnit.SECONDS))
            .apply { network?.let { socketFactory(it.socketFactory) } }
            .build()
    }

    override fun start() {
        isRunning = true
        _connection.value = CameraConnectionState.CONNECTING
    }

    override fun stop() {
        isRunning = false
        _connection.value = CameraConnectionState.IDLE
        streamClient.dispatcher.executorService.shutdown()
        snapshotClient.dispatcher.executorService.shutdown()
    }

    override fun frames(): Flow<CameraFrame> = flow {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "mode=${if (preferMjpeg) "mjpeg" else "snapshot"} url=$baseUrl")
        }
        var failures = 0
        while (isRunning) {
            coroutineContext.ensureActive()
            try {
                if (preferMjpeg) {
                    emitMjpegOkHttp(this)
                } else {
                    emitSnapshotLoop(this)
                }
                failures = 0
            } catch (e: Exception) {
                failures++
                if (failures >= 2) _connection.value = CameraConnectionState.OFFLINE
                if (BuildConfig.DEBUG) Log.w(TAG, "stream error: ${e.message}")
                if (preferMjpeg && failures <= 3) {
                    try { emitSnapshotLoop(this) } catch (_: Exception) {}
                }
                delay(reconnectDelayMs)
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun emitMjpegOkHttp(collector: kotlinx.coroutines.flow.FlowCollector<CameraFrame>) {
        val url = streamUrl("/stream")
        val req = Request.Builder()
            .url(url)
            .header("Accept", "multipart/x-mixed-replace")
            .header("Connection", "keep-alive")
            .header("Cache-Control", "no-cache")
            .build()
        val tConnect = System.currentTimeMillis()
        streamClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("empty body")
            val reader = MjpegStreamReader(body.source())
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = false
            }
            var frames = 0
            while (isRunning) {
                coroutineContext.ensureActive()
                val jpeg = reader.nextJpeg() ?: break
                val t0 = System.currentTimeMillis()
                val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
                    ?: continue
                latencyMs = if (frames == 0) t0 - tConnect else System.currentTimeMillis() - t0
                frames++
                _connection.value = CameraConnectionState.LIVE
                collector.emit(CameraFrame(bmp, System.currentTimeMillis()))
            }
        }
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
            try {
                val url = streamUrl("/snapshot?t=${System.currentTimeMillis()}&n=${seq++}")
                val req = Request.Builder().url(url).get()
                    .header("Connection", "close")
                    .header("Cache-Control", "no-cache")
                    .build()
                val bytes = snapshotClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    resp.body?.bytes() ?: throw IllegalStateException("empty")
                }
                if (bytes.size < 100) throw IllegalStateException("tiny frame")
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    ?: throw IllegalStateException("decode failed")
                latencyMs = System.currentTimeMillis() - t0
                failures = 0
                _connection.value = CameraConnectionState.LIVE
                collector.emit(CameraFrame(bmp, System.currentTimeMillis()))
            } catch (e: Exception) {
                failures++
                if (failures >= 3) {
                    _connection.value = CameraConnectionState.OFFLINE
                    throw e
                }
            }
            val elapsed = System.currentTimeMillis() - t0
            val sleep = frameGapMs - elapsed
            if (sleep > 0) delay(sleep)
        }
    }

    private fun streamUrl(path: String): String {
        val base = baseUrl.trimEnd('/')
        return "$base$path"
    }

    companion object {
        private const val TAG = "NetworkCameraSource"
    }
}
