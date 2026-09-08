package com.drowsy.ui

import android.app.Application
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drowsy.alerts.PhoneAlertManager
import com.drowsy.camera.CameraConnectionState
import com.drowsy.camera.CameraSource
import com.drowsy.camera.NetworkCameraSource
import com.drowsy.data.*
import com.drowsy.fatigue.*
import com.drowsy.location.LocationProvider
import com.drowsy.metrics.PerformanceMetrics
import com.drowsy.perception.DriverPerceptionEngine
import com.drowsy.perception.MediaPipeLandmarkerEngine
import com.drowsy.perception.FrameEnhancer
import com.drowsy.perception.Point2D
import com.drowsy.camera.CameraFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class UiState(
    val state: DriverState = DriverState.NORMAL,
    val score: Int = 0,
    val perclos: Float = 0f,
    val maxClosureMs: Long = 0,
    val yawnCount: Int = 0,
    val headAbnormal: Boolean = false,
    val trackingQuality: Float = 0f,
    val facePresent: Boolean = false,
    val eventsToday: Int = 0,
    val alertActive: Boolean = false,
    val previewBitmap: android.graphics.Bitmap? = null,
    val nightVisionOn: Boolean = false,
    val vehicleDevice: Boolean = false,
    val ear: Float = 0f,
    val mar: Float = 0f,
    val eyesClosed: Boolean = false,
    val yawning: Boolean = false,
    val landmarks: List<Point2D>? = null,
    val modelReady: Boolean = false,
    val modelLabel: String = "",
    val deviceMessage: String = "",
    val recentEvents: List<FatigueEvent> = emptyList(),
    val cameraConnection: CameraConnectionState = CameraConnectionState.IDLE,
    val streamLatencyMs: Long = -1,
    val sceneAuto: Boolean = true,
    val sceneLuma: Float = -1f,
)

class MonitorViewModel(
    app: Application,
    private val camera: CameraSource,
    private val perception: DriverPerceptionEngine,
    private val fatigue: FatigueEngine = FatigueEngine(),
    private val stateMachine: DriverStateMachine = DriverStateMachine(fatigue.thresholds),
    private val metrics: PerformanceMetrics = PerformanceMetrics(),
    private val locationProvider: LocationProvider? = null,
    private val deviceBaseUrl: String? = null,
    private val deviceNetwork: android.net.Network? = null,
    modelReady: Boolean = false,
    modelLabel: String = "",
    private val deviceId: String = app.getSharedPreferences("drowsy", 0).getString("device_id", null) ?: run {
        val id = "DEV-${java.util.UUID.randomUUID().toString().take(8)}"
        app.getSharedPreferences("drowsy", 0).edit().putString("device_id", id).apply(); id
    },
    private val vehicleId: String = app.getSharedPreferences("drowsy", 0).getString("vehicle_id", "DEMO-001")!!,
) : AndroidViewModel(app) {
    private val alerts = PhoneAlertManager(app, scope = viewModelScope)
    private var clipPlayer: MediaPlayer? = null
    private val deviceHttpMutex = Mutex()
    private var lastDriverState = DriverState.NORMAL

    private val db = (app as com.drowsy.DrowsyApp).db
    private val _ui = MutableStateFlow(
        UiState(
            modelReady = modelReady || (perception as? MediaPipeLandmarkerEngine)?.isReady == true,
            modelLabel = modelLabel.ifBlank {
                (perception as? MediaPipeLandmarkerEngine)?.statusMessage ?: perception.javaClass.simpleName
            },
            vehicleDevice = !deviceBaseUrl.isNullOrBlank(),
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    val perf = metrics.snapshot

    private var frameJob: Job? = null
    private var auxJob: Job? = null
    private var scenePollJob: Job? = null
    private var activeEvent: FatigueEvent? = null
    private var maxScoreInEvent = 0

    fun start() {
        if (frameJob != null) return
        camera.start()
        val netCam = camera as? NetworkCameraSource
        auxJob = viewModelScope.launch {
            netCam?.connection?.collect { conn ->
                _ui.update { it.copy(cameraConnection = conn, streamLatencyMs = netCam.lastLatencyMs()) }
            }
        }
        frameJob = viewModelScope.launch(Dispatchers.Default) {
            camera.frames()
                .conflate()
                .collect { frame ->
                    try {
                        metrics.onFrameReceived()
                        val enhanced = FrameEnhancer.enhance(frame.bitmap)
                        val procBmp = enhanced.bitmap
                        val previewBmp = try {
                            procBmp.copy(
                                procBmp.config ?: android.graphics.Bitmap.Config.ARGB_8888,
                                false,
                            )
                        } catch (_: Exception) { null }

                        val t0 = System.currentTimeMillis()
                        val procFrame = CameraFrame(procBmp, frame.timestampMs)
                        val pf = try {
                            when (val engine = perception) {
                                is MediaPipeLandmarkerEngine -> engine.processFrame(procFrame, enhanced.luma)
                                else -> engine.processFrame(procFrame)
                            }
                        } catch (_: Exception) {
                            null
                        }
                        if (enhanced.enhanced && procBmp !== frame.bitmap && !procBmp.isRecycled) {
                            procBmp.recycle()
                        }
                        if (pf == null) {
                            metrics.onDropped()
                            return@collect
                        }

                        val (score, snap) = fatigue.update(pf)
                        val state = stateMachine.step(score, pf.timestampMs)
                        val repeatOk = (state == DriverState.FATIGUE || state == DriverState.HIGH_RISK) &&
                            fatigue.shouldRepeatAlert(pf.timestampMs)
                        val alertState = alerts.handleState(state, pf.timestampMs, repeatOk)
                        if (alertState != null) triggerDeviceAlert(alertState)

                        val netCamInner = camera as? NetworkCameraSource
                        metrics.onInferenceDone(
                            System.currentTimeMillis() - t0,
                            pf.faceConfidence,
                            pf.trackingQuality,
                            score,
                            state.name,
                        )
                        val mp = perception as? MediaPipeLandmarkerEngine
                        _ui.update { prev ->
                            val oldBmp = prev.previewBitmap
                            prev.copy(
                                state = state,
                                score = score,
                                perclos = snap.perclos,
                                maxClosureMs = snap.maxClosureMs,
                                yawnCount = snap.yawnCount,
                                headAbnormal = pf.headPose?.abnormal == true,
                                trackingQuality = pf.trackingQuality,
                                facePresent = pf.facePresent,
                                alertActive = state == DriverState.FATIGUE || state == DriverState.HIGH_RISK,
                                previewBitmap = previewBmp ?: prev.previewBitmap,
                                ear = pf.eye?.earMean ?: 0f,
                                mar = pf.mouth?.mar ?: 0f,
                                eyesClosed = pf.eye?.eyesClosed == true,
                                yawning = pf.mouth?.isYawning == true,
                                landmarks = pf.landmarks,
                                modelReady = mp?.isReady ?: prev.modelReady,
                                modelLabel = mp?.statusMessage ?: prev.modelLabel,
                                streamLatencyMs = netCamInner?.lastLatencyMs() ?: prev.streamLatencyMs,
                                sceneLuma = pf.sceneLuma,
                            ).also {
                                if (previewBmp != null && oldBmp != null && oldBmp !== previewBmp && !oldBmp.isRecycled) {
                                    oldBmp.recycle()
                                }
                            }
                        }
                        lastDriverState = state
                        handleEventLifecycle(pf, score, snap, state)
                    } finally {
                        if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
                    }
                }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val since = System.currentTimeMillis() - 24 * 3600 * 1000
            _ui.update {
                it.copy(
                    eventsToday = db.fatigueEventDao().countSince(since),
                    recentEvents = db.fatigueEventDao().recent(50),
                )
            }
            enableAutoSceneOnDevice()
            refreshDeviceStatus()
        }
        if (!deviceBaseUrl.isNullOrBlank()) {
            scenePollJob = viewModelScope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(8000)
                    refreshDeviceStatus()
                }
            }
        }
    }

    fun onForeground() {
        if (deviceBaseUrl.isNullOrBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            enableAutoSceneOnDevice()
            refreshDeviceStatus()
        }
    }

    fun setNightVision(on: Boolean) {
        val base = deviceBaseUrl?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            deviceHttpMutex.withLock {
                try {
                    val req = Request.Builder().url("$base/night-vision?on=${if (on) 1 else 0}").get().build()
                    deviceHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                        applyDeviceSceneBody(resp.body?.string().orEmpty())
                    }
                } catch (e: Exception) {
                    _ui.update { it.copy(deviceMessage = "Lighting failed: ${e.message}") }
                    refreshDeviceStatus()
                }
            }
        }
    }

    fun setSceneAuto() {
        val base = deviceBaseUrl?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            deviceHttpMutex.withLock {
                try {
                    val req = Request.Builder().url("$base/night-vision?on=auto").get().build()
                    deviceHttpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) applyDeviceSceneBody(resp.body?.string().orEmpty())
                    }
                } catch (_: Exception) { }
            }
        }
    }

    fun testPhoneBeep() {
        alerts.fatigueWarning(System.currentTimeMillis())
        _ui.update { it.copy(deviceMessage = "Phone beep sent — check alarm volume") }
    }

    fun testVehicleSpeaker() {
        val base = deviceBaseUrl?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            deviceHttpMutex.withLock {
                _ui.update { it.copy(deviceMessage = "Triggering vehicle speaker…") }
                try {
                    val req = Request.Builder().url("$base/alert?type=fatigue").get().build()
                    deviceHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    }
                    _ui.update { it.copy(deviceMessage = "Vehicle speaker OK — plug 4Ω/8Ω speaker into SPK port") }
                } catch (e: Exception) {
                    _ui.update { it.copy(deviceMessage = "Speaker failed: ${e.message}") }
                }
            }
        }
    }

    fun testDeviceMic(seconds: Int = 3) {
        val base = deviceBaseUrl?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            deviceHttpMutex.withLock {
                _ui.update { it.copy(deviceMessage = "Recording ${seconds}s from device mic…") }
                try {
                    val req = Request.Builder().url("$base/audio-clip?sec=$seconds").get().build()
                    val bytes = deviceHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                        resp.body?.bytes()
                    }
                    if (bytes == null || bytes.size < 44) throw IllegalStateException("empty clip")
                    val file = File(getApplication<Application>().cacheDir, "device-mic.wav")
                    file.writeBytes(bytes)
                    withContext(Dispatchers.Main) { playClip(file) }
                    _ui.update { it.copy(deviceMessage = "Playing ${bytes.size} byte mic clip") }
                } catch (e: Exception) {
                    _ui.update { it.copy(deviceMessage = "Mic failed: ${e.message}") }
                }
            }
        }
    }

    private fun playClip(file: File) {
        try { clipPlayer?.release() } catch (_: Exception) {}
        clipPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setDataSource(file.absolutePath)
            setOnCompletionListener { it.release(); clipPlayer = null }
            prepare()
            start()
        }
    }

    private suspend fun enableAutoSceneOnDevice() {
        val base = deviceBaseUrl?.takeIf { it.isNotBlank() } ?: return
        deviceHttpMutex.withLock {
            try {
                val req = Request.Builder().url("$base/night-vision?on=auto").get().build()
                deviceHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) applyDeviceSceneBody(resp.body?.string().orEmpty())
                }
            } catch (_: Exception) { }
        }
    }

    private fun applyDeviceSceneBody(body: String) {
        _ui.update {
            it.copy(
                nightVisionOn = body.contains("\"nightVision\":true"),
                sceneAuto = !body.contains("\"sceneAuto\":false"),
                sceneLuma = parseSceneLuma(body).takeIf { v -> v >= 0 } ?: it.sceneLuma,
                deviceMessage = "",
            )
        }
    }

    private fun parseSceneLuma(body: String): Float {
        val key = "\"sceneLuma\":"
        val i = body.indexOf(key)
        if (i < 0) return -1f
        return body.substring(i + key.length).takeWhile { it.isDigit() || it == '.' }.toFloatOrNull() ?: -1f
    }

    private suspend fun refreshDeviceStatus() {
        val base = deviceBaseUrl?.takeIf { it.isNotBlank() } ?: return
        deviceHttpMutex.withLock {
            try {
                val req = Request.Builder().url("$base/status").get().build()
                deviceHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return
                    applyDeviceSceneBody(resp.body?.string().orEmpty())
                }
            } catch (_: Exception) { }
        }
    }

    fun stop() {
        frameJob?.cancel()
        frameJob = null
        auxJob?.cancel()
        auxJob = null
        scenePollJob?.cancel()
        scenePollJob = null
        camera.stop()
    }

    private val deviceHttpClient by lazy {
        OkHttpClient.Builder().apply {
            connectTimeout(5, TimeUnit.SECONDS)
            readTimeout(25, TimeUnit.SECONDS)
            // Default route only — matches manual DRIVER-CAM join on ColorOS.
        }.build()
    }

    private var lastMicClipMs = 0L

    private fun triggerDeviceAlert(state: DriverState) {
        val base = deviceBaseUrl?.takeIf { it.isNotBlank() } ?: return
        val type = when (state) {
            DriverState.ATTENTION -> "attention"
            DriverState.HIGH_RISK -> "high"
            else -> "fatigue"
        }
        viewModelScope.launch(Dispatchers.IO) {
            deviceHttpMutex.withLock {
                try {
                    val req = Request.Builder().url("$base/alert?type=$type").get().build()
                    deviceHttpClient.newCall(req).execute().close()
                } catch (_: Exception) { }
            }
            if (state == DriverState.HIGH_RISK) {
                val now = System.currentTimeMillis()
                if (now - lastMicClipMs > 15_000) {
                    lastMicClipMs = now
                    captureDeviceMicClip(2)
                }
            }
        }
    }

    private suspend fun captureDeviceMicClip(seconds: Int) {
        val base = deviceBaseUrl?.takeIf { it.isNotBlank() } ?: return
        deviceHttpMutex.withLock {
            try {
                val req = Request.Builder().url("$base/audio-clip?sec=$seconds").get().build()
                val bytes = deviceHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return
                    resp.body?.bytes()
                }
                if (bytes == null || bytes.size < 44) return
                val file = File(getApplication<Application>().cacheDir, "fatigue-evidence.wav")
                file.writeBytes(bytes)
                withContext(Dispatchers.Main) { playClip(file) }
            } catch (_: Exception) { }
        }
    }

    private suspend fun handleEventLifecycle(
        pf: com.drowsy.perception.PerceptionFrame,
        score: Int,
        snap: TemporalSnapshot,
        state: DriverState,
    ) {
        val gps = withTimeoutOrNull(2000) { locationProvider?.lastFix() }
        if (state == DriverState.FATIGUE || state == DriverState.HIGH_RISK) {
            if (activeEvent == null) {
                activeEvent = FatigueEvent(
                    deviceId = deviceId,
                    vehicleId = vehicleId,
                    timestampStart = pf.timestampMs,
                    timestampEnd = pf.timestampMs,
                    durationMs = 0,
                    severity = if (state == DriverState.HIGH_RISK) Severity.HIGH.name else Severity.MEDIUM.name,
                    maxFatigueScore = score,
                    eyeClosure = snap.prolongedClosure,
                    yawning = snap.yawnCount > 0,
                    headPoseAbnormal = pf.headPose?.abnormal == true,
                    alertTriggered = true,
                    recovered = false,
                    gpsLat = gps?.lat ?: 0.0,
                    gpsLng = gps?.lng ?: 0.0,
                    trackingQuality = pf.trackingQuality,
                )
                maxScoreInEvent = score
            } else {
                maxScoreInEvent = maxOf(maxScoreInEvent, score)
                activeEvent = activeEvent!!.copy(
                    timestampEnd = pf.timestampMs,
                    durationMs = (pf.timestampMs - activeEvent!!.timestampStart).toInt(),
                    maxFatigueScore = maxScoreInEvent,
                )
            }
        } else if (state == DriverState.NORMAL && activeEvent != null) {
            val ev = activeEvent!!.copy(
                recovered = true,
                timestampEnd = pf.timestampMs,
                durationMs = (pf.timestampMs - activeEvent!!.timestampStart).toInt(),
                severity = if (maxScoreInEvent >= 76) Severity.HIGH.name else Severity.MEDIUM.name,
            )
            saveEvent(db, ev)
            activeEvent = null
            maxScoreInEvent = 0
            _ui.update {
                it.copy(
                    eventsToday = it.eventsToday + 1,
                    recentEvents = listOf(ev) + it.recentEvents,
                )
            }
        }
    }

    override fun onCleared() {
        try { clipPlayer?.release() } catch (_: Exception) {}
        stop()
        super.onCleared()
    }
}
