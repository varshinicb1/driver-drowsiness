package com.drowsy.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drowsy.alerts.PhoneAlertManager
import com.drowsy.camera.CameraSource
import com.drowsy.data.*
import com.drowsy.fatigue.*
import com.drowsy.location.LocationProvider
import com.drowsy.metrics.PerformanceMetrics
import com.drowsy.perception.DriverPerceptionEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    private val deviceId: String = app.getSharedPreferences("drowsy", 0).getString("device_id", null) ?: run {
        val id = "DEV-${java.util.UUID.randomUUID().toString().take(8)}"
        app.getSharedPreferences("drowsy", 0).edit().putString("device_id", id).apply(); id
    },
    private val vehicleId: String = app.getSharedPreferences("drowsy", 0).getString("vehicle_id", "DEMO-001")!!,
) : AndroidViewModel(app) {
    private val alerts = PhoneAlertManager(app, scope = viewModelScope)

    private val db = (app as com.drowsy.DrowsyApp).db
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    val perf = metrics.snapshot

    private var job: Job? = null
    private var activeEvent: FatigueEvent? = null
    private var maxScoreInEvent = 0

    fun start() {
        if (job != null) return
        camera.start()
        job = viewModelScope.launch {
            camera.frames().collect { frame ->
                metrics.onFrameReceived()
                // Copy before handing to perception: MediaPipe's BitmapImageBuilder can recycle
                // the source bitmap once it's done with it, which would crash Compose's Image
                // if it tried to draw the same (now-recycled) object afterward.
                val previewBmp = try {
                    frame.bitmap.copy(frame.bitmap.config ?: android.graphics.Bitmap.Config.ARGB_8888, false)
                } catch (_: Exception) { null }
                val t0 = System.currentTimeMillis()
                val pf = try { with(kotlinx.coroutines.Dispatchers.Default) { perception.processFrame(frame) } } catch (_: Exception) { null; }
                if (pf == null) { metrics.onDropped(); return@collect }
                val (score, snap) = fatigue.update(pf)
                val didAlert = fatigue.handleAlert(pf.timestampMs)
                val state = stateMachine.step(score, pf.timestampMs)
                val alertFired = alerts.handleState(state, pf.timestampMs, didAlert)
                if (alertFired) triggerDeviceAlert()

                metrics.onInferenceDone(System.currentTimeMillis() - t0, pf.faceConfidence, pf.trackingQuality, score, state.name)
                _ui.value = UiState(
                    state = state, score = score, perclos = snap.perclos, maxClosureMs = snap.maxClosureMs,
                    yawnCount = snap.yawnCount, headAbnormal = pf.headPose?.abnormal == true,
                    trackingQuality = pf.trackingQuality, facePresent = pf.facePresent,
                    eventsToday = _ui.value.eventsToday, alertActive = state != DriverState.NORMAL,
                    previewBitmap = previewBmp ?: _ui.value.previewBitmap,
                )
                handleEventLifecycle(pf, score, snap, state)
            }
        }
        // events today count
        viewModelScope.launch {
            val since = System.currentTimeMillis() - 24*3600*1000
            _ui.value = _ui.value.copy(eventsToday = db.fatigueEventDao().countSince(since))
        }
    }

    fun stop() { job?.cancel(); job = null; camera.stop() }

    private val deviceHttpClient by lazy {
        okhttp3.OkHttpClient.Builder().apply {
            deviceNetwork?.let { socketFactory(it.socketFactory) }
        }.build()
    }

    // Fires the ESP32's own vehicle-mounted speaker alongside the phone beep. Best-effort —
    // must never block or fail the phone-side alert if the device is unreachable.
    private fun triggerDeviceAlert() {
        val base = deviceBaseUrl?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val req = okhttp3.Request.Builder().url("$base/alert").get().build()
                deviceHttpClient.newCall(req).execute().close()
            } catch (_: Exception) { /* device offline/unreachable — phone alert already fired */ }
        }
    }

    private suspend fun handleEventLifecycle(pf: com.drowsy.perception.PerceptionFrame, score: Int, snap: TemporalSnapshot, state: DriverState) {
        // Resolve GPS with 2s timeout, fallback 0,0 — does not block state machine
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
            // recovery — close event
            val ev = activeEvent!!.copy(recovered = true, timestampEnd = pf.timestampMs, durationMs = (pf.timestampMs - activeEvent!!.timestampStart).toInt(), severity = if (maxScoreInEvent>=76) Severity.HIGH.name else Severity.MEDIUM.name)
            saveEvent(db, ev)
            activeEvent = null; maxScoreInEvent = 0
            _ui.value = _ui.value.copy(eventsToday = _ui.value.eventsToday + 1)
        }
    }

    override fun onCleared() { stop(); super.onCleared() }
}
