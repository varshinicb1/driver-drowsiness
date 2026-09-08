package com.drowsy.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.drowsy.camera.AndroidFrontCameraSource
import com.drowsy.camera.CameraSource
import com.drowsy.fatigue.DriverState
import com.drowsy.location.FusedLocationProvider
import com.drowsy.network.DeviceWifiConnector
import com.drowsy.perception.MediaPipeLandmarkerEngine
import com.drowsy.perception.Point2D
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val wifiConnector by lazy { DeviceWifiConnector(applicationContext) }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.CAMERA] == true) recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!hasPermissions()) {
            permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
        }

        val cfg = readConfig()
        val source = cfg?.optString("camera_source", "front") ?: "front"
        val deviceBaseUrl = (cfg?.optString("network_camera_url", "") ?: "").ifBlank { "http://192.168.4.1" }
        val apSsid = cfg?.optString("device_ap_ssid", "DRIVER-CAM") ?: "DRIVER-CAM"
        val apPassword = cfg?.optString("device_ap_password", "drowsy123") ?: "drowsy123"
        val landmarker = MediaPipeLandmarkerEngine(applicationContext)
        val modelReady = landmarker.initialize()
        val modelLabel = landmarker.statusMessage
        val perception = landmarker
        val locationProvider = FusedLocationProvider(applicationContext)

        setContent {
            MaterialTheme {
                var ready by remember { mutableStateOf<Pair<CameraSource, android.net.Network?>?>(null) }
                var connecting by remember { mutableStateOf(source == "network") }

                LaunchedEffect(Unit) {
                    if (source == "network" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        wifiConnector.connect(
                            apSsid, apPassword,
                            onConnected = { network ->
                                ready = com.drowsy.camera.NetworkCameraSource(deviceBaseUrl, network = network) to network
                                connecting = false
                            },
                            onFailed = {
                                ready = com.drowsy.camera.NetworkCameraSource(deviceBaseUrl) to null
                                connecting = false
                            },
                        )
                    } else {
                        ready = createCameraSource(source, deviceBaseUrl) to null
                        connecting = false
                    }
                }

                val current = ready
                if (current == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (connecting) "Connecting to vehicle deviceΓÇª" else "StartingΓÇª")
                    }
                } else {
                    val (camera, network) = current
                    val factory = remember(camera) {
                        val vehicleUrl = if (source == "network") deviceBaseUrl else null
                        MonitorViewModelFactory(
                            application, camera, perception, locationProvider, vehicleUrl, network,
                            modelReady, modelLabel,
                        )
                    }
                    val vm: MonitorViewModel = viewModel(factory = factory)
                    LaunchedEffect(camera) { if (hasPermissions()) vm.start() }
                    DisposableEffect(camera) { onDispose { vm.stop() } }
                    AppShell(vm)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiConnector.disconnect()
    }

    private fun readConfig(): org.json.JSONObject? = try {
        assets.open("config.json").bufferedReader().readText().let { org.json.JSONObject(it) }
    } catch (_: Exception) { null }

    private fun createCameraSource(source: String, networkUrl: String): CameraSource {
        return if (source == "network") {
            com.drowsy.camera.NetworkCameraSource(networkUrl)
        } else if (source == "uvc") {
            com.drowsy.camera.UsbUvcCameraSource()
        } else {
            AndroidFrontCameraSource(applicationContext, this)
        }
    }

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
}

private enum class AppTab { Monitor, Events, Device }

@Composable
fun AppShell(vm: MonitorViewModel) {
    var tab by remember { mutableStateOf(AppTab.Monitor) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Text(item.name.take(1)) },
                        label = { Text(item.name) },
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                AppTab.Monitor -> MonitorScreen(vm)
                AppTab.Events -> EventsScreen(vm)
                AppTab.Device -> DeviceScreen(vm)
            }
        }
    }
}

@Composable
fun MonitorScreen(vm: MonitorViewModel) {
    val ui by vm.ui.collectAsState()
    val perf by vm.perf.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("DRIVER SAFETY", style = MaterialTheme.typography.headlineSmall)
        Box(Modifier.fillMaxWidth().height(280.dp).background(Color(0xFF111111))) {
            val bmp = ui.previewBitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Camera preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                FaceMeshOverlay(ui.landmarks, Modifier.fillMaxSize())
            }
            Text(
                when {
                    bmp == null -> "Waiting for cameraΓÇª"
                    ui.facePresent -> "Face landmarker: tracking"
                    else -> "No face in frame"
                },
                color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            )
        }
        Text(
            if (ui.modelReady) "Face model: ${ui.modelLabel}" else "Face model not loaded: ${ui.modelLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = if (ui.modelReady) Color(0xFF2E7D32) else Color(0xFFB00020),
        )
        Text(
            "Drowsiness (EAR/MAR/PERCLOS): EAR ${"%.2f".format(ui.ear)}  MAR ${"%.2f".format(ui.mar)}  PERCLOS ${"%.0f".format(ui.perclos * 100)}%",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Eyes ${if (ui.eyesClosed) "CLOSED" else "open"} ┬╖ Yawn ${if (ui.yawning) "YES" else "no"}",
            style = MaterialTheme.typography.bodySmall,
        )
        StatusCard(ui)
        if (com.drowsy.BuildConfig.ENABLE_PERF_OVERLAY) {
            PerfOverlay(perf)
        }
        if (ui.vehicleDevice) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Night vision (IR)", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = ui.nightVisionOn, onCheckedChange = { vm.setNightVision(it) })
            }
        }
        if (ui.state == DriverState.FATIGUE || ui.state == DriverState.HIGH_RISK) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFB00020))) {
                Column(Modifier.padding(16.dp)) {
                    Text("ΓÜá FATIGUE DETECTED", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("Fatigue score: ${ui.score} / 100", color = Color.White)
                    Text(if (ui.alertActive) "≡ƒöè ALERT PLAYING" else "", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun FaceMeshOverlay(landmarks: List<Point2D>?, modifier: Modifier) {
    if (landmarks.isNullOrEmpty()) return
    val key = intArrayOf(33, 133, 362, 263, 1, 61, 291, 13, 14)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        landmarks.forEachIndexed { i, p ->
            val x = p.x * w
            val y = p.y * h
            val r = if (i in key) 5f else 1.6f
            drawCircle(Color(0xFF00E676), radius = r, center = Offset(x, y))
        }
    }
}

@Composable
fun EventsScreen(vm: MonitorViewModel) {
    val ui by vm.ui.collectAsState()
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Events", style = MaterialTheme.typography.headlineSmall)
        Text("Today: ${ui.eventsToday}", style = MaterialTheme.typography.bodyMedium)
        if (ui.recentEvents.isEmpty()) {
            Text("No fatigue events stored yet. Close your eyes for a few seconds on the Monitor tab to generate one.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ui.recentEvents, key = { it.eventId }) { ev ->
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            Text("${ev.severity} ┬╖ score ${ev.maxFatigueScore}", style = MaterialTheme.typography.titleMedium)
                            Text(fmt.format(Date(ev.timestampStart)) + "  ${ev.durationMs} ms")
                            Text("eyes=${ev.eyeClosure} yawn=${ev.yawning} head=${ev.headPoseAbnormal} recovered=${ev.recovered}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceScreen(vm: MonitorViewModel) {
    val ui by vm.ui.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Device", style = MaterialTheme.typography.headlineSmall)
        Text("Face detector: ${ui.modelLabel}")
        Text("Drowsiness: on-device EAR + MAR + PERCLOS (not a separate TFLite drowsiness net)")
        if (ui.vehicleDevice) {
            Text("ESP32 at vehicle AP ΓÇö speaker, mic, and IR are on the camera board.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.testVehicleSpeaker() }) { Text("Test speaker") }
                Button(onClick = { vm.testDeviceMic() }) { Text("Test mic") }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Night vision (IR)")
                Switch(checked = ui.nightVisionOn, onCheckedChange = { vm.setNightVision(it) })
            }
            Text("Speaker only plays if a 4╬⌐/8╬⌐ speaker is plugged into the boardΓÇÖs SPK connector.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Phone front camera mode ΓÇö vehicle speaker/mic are unavailable.")
        }
        Button(onClick = { vm.testPhoneBeep() }) { Text("Test phone beep") }
        if (ui.deviceMessage.isNotBlank()) {
            Text(ui.deviceMessage, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun StatusCard(ui: UiState) {
    val color = when (ui.state) {
        DriverState.NORMAL -> Color(0xFF2E7D32)
        DriverState.ATTENTION -> Color(0xFFF9A825)
        DriverState.FATIGUE -> Color(0xFFEF6C00)
        DriverState.HIGH_RISK -> Color(0xFFB00020)
    }
    Card(colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Status: ${ui.state.name}", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text("Fatigue score: ${ui.score} / 100", color = Color.White)
            Text("Eyes: ${if (ui.maxClosureMs > 1000) "Prolonged closure ${ui.maxClosureMs}ms" else "Normal"}", color = Color.White)
            Text("Blinking/Yawns: ${ui.yawnCount} yawns", color = Color.White)
            Text("Head pose: ${if (ui.headAbnormal) "Abnormal" else "Normal"}", color = Color.White)
            Text(
                "Tracking: ${when {
                    ui.trackingQuality > 0.7f -> "Good"
                    ui.trackingQuality > 0.35f -> "Fair"
                    else -> "Poor"
                }} (${"%.2f".format(ui.trackingQuality)})",
                color = Color.White,
            )
        }
    }
}

@Composable
fun PerfOverlay(perf: com.drowsy.metrics.PerfSnapshot) {
    Text(
        "FPS: ${"%.1f".format(perf.inferenceFps)}  Inference: ${perf.inferenceMs}ms  " +
            "E2E: ${perf.endToEndMs}ms  Face: ${"%.2f".format(perf.faceConfidence)}  " +
            "Fatigue: ${perf.fatigueScore}  State: ${perf.state}",
        style = MaterialTheme.typography.labelSmall, color = Color.Gray
    )
}
