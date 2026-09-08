package com.drowsy.ui

import android.app.Application
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drowsy.alerts.PhoneAlertManager
import com.drowsy.camera.CameraSource
import com.drowsy.data.*
import com.drowsy.fatigue.*
import com.drowsy.location.LocationProvider
import com.drowsy.metrics.PerformanceMetrics
import com.drowsy.perception.DriverPerceptionEngine
import com.drowsy.perception.MediaPipeLandmarkerEngine
import com.drowsy.perception.Point2D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

    private var job: Job? = null
    private var activeEvent: FatigueEvent? = null
    private var maxScoreInEvent = 0

    fun start() {
        if (job != null) return
        camera.start()
        job = viewModelScope.launch {
            camera.frames()
                .conflate() // always process the freshest frame — never backlog perception
                .collect { frame ->
                metrics.onFrameReceived()
                val previewBmp = try {
                    frame.bitmap.copy(frame.bitmap.config ?: android.graphics.Bitmap.Config.ARGB_8888, false)
                } catch (_: Exception) { null }
                val t0 = System.currentTimeMillis()
                val pf = try {
                    withContext(Dispatchers.Default) { perception.processFrame(frame) }
                } catch (_: Exception) { null }
                if (pf == null) { metrics.onDropped(); return@collect }
                val (score, snap) = fatigue.update(pf)
                val didAlert = fatigue.handleAlert(pf.timestampMs)
                val state = stateMachine.step(score, pf.timestampMs)
                val alertFired = alerts.handleState(state, pf.timestampMs, didAlert)
                if (alertFired) triggerDeviceAlert()

                metrics.onInferenceDone(System.currentTimeMillis() - t0, pf.faceConfidence, pf.trackingQuality, score, state.name)
                val mp = perception as? MediaPipeLandmarkerEngine
                _ui.update {
                    it.copy(
                        state = state, score = score, perclos = snap.perclos, maxClosureMs = snap.maxClosureMs,
                        yawnCount = snap.yawnCount, headAbnormal = pf.headPose?.abnormal == true,
                        trackingQuality = pf.trackingQuality, facePresent = pf.facePresent,
                        alertActive = state == DriverState.FATIGUE || state == DriverState.HIGH_RISK,
                        previewBitmap = previewBmp ?: it.previewBitmap,
                        ear = pf.eye?.earMean ?: 0f,
                        mar = pf.mouth?.mar ?: 0f,
                        eyesClosed = pf.eye?.eyesClosed == true,
                        yawning = pf.mouth?.isYawning == true,
                        landmarks = pf.landmarks,
                        modelReady = mp?.isReady ?: it.modelReady,
                        modelLabel = mp?.statusMessage ?: it.modelLabel,
                    )
                }
                handleEventLifecycle(pf, score, snap, state)
            }
        }
        viewModelScope.launch {
            val since = System.currentTimeMillis() - 24*3600*1000
            _ui.update {
                it.copy(
                    eventsToday = db.fatigueEventDao().countSince(since),
                    recentEvents = db.fatigueEventDao().recent(50),
                    vehicleDevice = !deviceBaseUrl.isNullOrBlank(),
                )
            }
            refreshDeviceStatus()
            if (!deviceBaseUrl.isNullOrBlank()) {
                // Ensure IR is off on connect — daylight default; user can re-enable on Device tab.
                setNightVision(false)
            }
        }
    }

    fun setNightVision(on: Boolean) {
        val base = deviceBaseUrl?.takeIf { it.isNotBlank() } ?: return
        _ui.update { it.copy(nightVisionOn = on) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder().url("$base/night-vision?on=${if (on) 1 else 0}").get().build()
                deviceHttpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    val actual = body.contains("\"nightVision\":true")
                    _ui.update { it.copy(nightVisionOn = actual, deviceMessage = if (actual) "IR night vision on" else "IR night vision off") }
                }
            } catch (e: Exception) {
                _ui.update { it.copy(deviceMessage = "Night vision failed: ${e.message}") }
            }
        }
    }

    fun testPhoneBeep() {
        alerts.fatigueWarning(System.currentTimeMillis())
        _ui.update { it.copy(deviceMessage = "Phone speaker beep sent (turn media/alarm volume up)") }
    }

    fun testVehicleSpeaker() {
        val base = deviceBaseUrl?.takeIf { it.isNotBlank() } ?: return
        _ui.update { it.copy(deviceMessage = "Triggering vehicle speaker…") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder().url("$base/alert").get().build()
                deviceHttpClient.newCall(req).execute().close()
                _ui.update { it.copy(deviceMessage = "Vehicle /alert OK — plug a 4Ω/8Ω speaker into the MX1.25 SPK port") }
            } catch (e: Exception) {
                _ui.update { it.copy(deviceMessage = "Vehicle speaker failed: ${e.message}") }
            }
        }
    }

    fun testDeviceMic(seconds: Int = 3) {
        val base = deviceBaseUrl?.takeIf { it.isNotBlank() } ?: return
        _ui.update { it.copy(deviceMessage = "Recording $seconds s from device mic…") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder().url("$base/audio-clip?sec=$seconds").get().build()
                val bytes = deviceHttpClient.newCall(req).execute().use { it.body?.bytes() }
                if (bytes == null || bytes.size < 44) {
                    _ui.update { it.copy(deviceMessage = "Mic clip empty") }
                    return@launch
                }
                val file = File(getApplication<Application>().cacheDir, "device-mic.wav")
                file.writeBytes(bytes)
                withContext(Dispatchers.Main) { playClip(file) }
                _ui.update { it.copy(deviceMessage = "Playing ${bytes.size} byte WAV from onboard PDM mic") }
            } catch (e: Exception) {
                _ui.update { it.copy(deviceMessage = "Mic failed: ${e.message}") }
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
                    .build()
            )
            setDataSource(file.absolutePath)
            setOnCompletionListener { it.release(); clipPlayer = null }
            prepare()
            start()
        }
    }

    private fun refreshDeviceStatus() {
        val base = deviceBaseUrl?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder().url("$base/status").get().build()
                deviceHttpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (body.contains("nightVision")) {
                        _ui.update { it.copy(nightVisionOn = body.contains("\"nightVision\":true")) }
                    }
                }
            } catch (_: Exception) { }
        }
    }

    fun stop() { job?.cancel(); job = null; camera.stop() }

    private val deviceHttpClient by lazy {
        OkHttpClient.Builder().apply {
            connectTimeout(5, TimeUnit.SECONDS)
            readTimeout(20, TimeUnit.SECONDS)
            deviceNetwork?.let { socketFactory(it.socketFactory) }
        }.build()
    }

    private fun triggerDeviceAlert() {
        val base = deviceBaseUrl?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder().url("$base/alert").get().build()
                deviceHttpClient.newCall(req).execute().close()
            } catch (_: Exception) { }
        }
    }

    private suspend fun handleEventLifecycle(pf: com.drowsy.perception.PerceptionFrame, score: Int, snap: TemporalSnapshot, state: DriverState) {
        val gps = withTimeoutOrNull(2000) { locationProvider?.lastFix() }
        if (state == DriverState.FATIGUE || state == DriverState.HIGH_RISK) {
            if (activeEvent == null) {
                activeEvent = FatigueEvent(
                    deviceId = deviceId, vehicleId = vehicleId,
                    timestampStart = pf.timestampMs, timestampEnd = pf.timestampMs,
                    durationMs = 0, severity = if (state==DriverState.HIGH_RISK) Severity.HIGH.name else Severity.MEDIUM.name,
                    maxFatigueScore = score, eyeClosure = snap.prolongedClosure, yawning = snap.yawnCount>0,
                    headPoseAbnormal = pf.headPose?.abnormal==true, alertTriggered = true, recovered = false,
                    gpsLat = gps?.lat ?: 0.0, gpsLng = gps?.lng ?: 0.0, trackingQuality = pf.trackingQuality,
                )
                maxScoreInEvent = score
            } else {
                maxScoreInEvent = maxOf(maxScoreInEvent, score)
                activeEvent = activeEvent!!.copy(timestampEnd = pf.timestampMs, durationMs = (pf.timestampMs - activeEvent!!.timestampStart).toInt(), maxFatigueScore = maxScoreInEvent)
            }
        } else if (state == DriverState.NORMAL && activeEvent != null) {
            val ev = activeEvent!!.copy(recovered = true, timestampEnd = pf.timestampMs, durationMs = (pf.timestampMs - activeEvent!!.timestampStart).toInt(), severity = if (maxScoreInEvent>=76) Severity.HIGH.name else Severity.MEDIUM.name)
            saveEvent(db, ev)
            activeEvent = null; maxScoreInEvent = 0
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
        stop(); super.onCleared()
    }
}
