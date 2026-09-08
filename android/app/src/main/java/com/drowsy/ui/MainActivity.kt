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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.drowsy.camera.AndroidFrontCameraSource
import com.drowsy.camera.CameraConnectionState
import com.drowsy.camera.CameraSource
import com.drowsy.fatigue.DriverState
import com.drowsy.location.FusedLocationProvider
import com.drowsy.network.DeviceWifiConnector
import com.drowsy.perception.MediaPipeLandmarkerEngine
import com.drowsy.perception.Point2D
import com.drowsy.ui.theme.DrowsyPalette
import com.drowsy.ui.theme.DrowsyTheme
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
        val locationProvider = FusedLocationProvider(applicationContext)

        setContent {
            DrowsyTheme {
                var ready by remember { mutableStateOf<Pair<CameraSource, android.net.Network?>?>(null) }
                var wifiError by remember { mutableStateOf<String?>(null) }
                var connecting by remember { mutableStateOf(source == "network") }

                LaunchedEffect(Unit) {
                    if (source == "network" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        wifiConnector.connect(
                            apSsid, apPassword,
                            onConnected = { network ->
                                ready = com.drowsy.camera.NetworkCameraSource(deviceBaseUrl, network = network) to network
                                connecting = false
                                wifiError = null
                            },
                            onFailed = {
                                wifiError = "Join Wi-Fi \"$apSsid\" manually in Settings, then reopen the app."
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
                    LoadingScreen(if (connecting) "Connecting to $apSsid…" else "Starting…")
                } else {
                    val (camera, network) = current
                    val vehicleUrl = if (source == "network") deviceBaseUrl else null
                    val factory = remember(camera, vehicleUrl) {
                        MonitorViewModelFactory(
                            application, camera, landmarker, locationProvider, vehicleUrl, network,
                            modelReady, modelLabel,
                        )
                    }
                    val vm: MonitorViewModel = viewModel(factory = factory)
                    LaunchedEffect(camera) { if (hasPermissions()) vm.start() }
                    DisposableEffect(camera) { onDispose { vm.stop() } }
                    AppShell(vm, wifiError)
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
        return when (source) {
            "network" -> com.drowsy.camera.NetworkCameraSource(networkUrl)
            "uvc" -> com.drowsy.camera.UsbUvcCameraSource()
            else -> AndroidFrontCameraSource(applicationContext, this)
        }
    }

    private fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun LoadingScreen(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text(message, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private enum class AppTab(val label: String) {
    Monitor("Monitor"), Events("Events"), Device("Device")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(vm: MonitorViewModel, wifiHint: String?) {
    var tab by remember { mutableStateOf(AppTab.Monitor) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Driver Safety", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { item ->
                    val icon = when (item) {
                        AppTab.Monitor -> Icons.Default.Videocam
                        AppTab.Events -> Icons.Default.History
                        AppTab.Device -> Icons.Default.Settings
                    }
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                AppTab.Monitor -> MonitorScreen(vm, wifiHint)
                AppTab.Events -> EventsScreen(vm)
                AppTab.Device -> DeviceScreen(vm)
            }
        }
    }
}

@Composable
fun MonitorScreen(vm: MonitorViewModel, wifiHint: String?) {
    val ui by vm.ui.collectAsState()
    val perf by vm.perf.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectionBanner(ui.cameraConnection, ui.streamLatencyMs, wifiHint)
        CameraCard(ui)
        ModelStatusRow(ui)
        MetricsRow(ui)
        StatusCard(ui)
        if (com.drowsy.BuildConfig.ENABLE_PERF_OVERLAY) PerfOverlay(perf)
        if (ui.vehicleDevice) {
            NightVisionRow(ui.nightVisionOn) { vm.setNightVision(it) }
        }
        if (ui.state == DriverState.FATIGUE || ui.state == DriverState.HIGH_RISK) {
            FatigueAlertCard(ui)
        }
    }
}

@Composable
private fun ConnectionBanner(state: CameraConnectionState, latencyMs: Long, wifiHint: String?) {
    val (color, icon, text) = when (state) {
        CameraConnectionState.LIVE -> Triple(
            DrowsyPalette.normal,
            Icons.Default.Wifi,
            if (latencyMs > 0) "Camera live · ${latencyMs}ms" else "Camera live",
        )
        CameraConnectionState.CONNECTING -> Triple(
            DrowsyPalette.attention,
            Icons.Default.Wifi,
            "Connecting to vehicle camera…",
        )
        CameraConnectionState.OFFLINE -> Triple(
            MaterialTheme.colorScheme.error,
            Icons.Default.WifiOff,
            "Camera offline — join DRIVER-CAM Wi-Fi",
        )
        CameraConnectionState.IDLE -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            Icons.Default.Wifi,
            "Camera idle",
        )
    }
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f))) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(text, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                if (wifiHint != null) {
                    Text(wifiHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(0.7f))
                }
            }
        }
    }
}

@Composable
private fun CameraCard(ui: UiState) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(DrowsyPalette.cameraBg),
        ) {
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
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DrowsyPalette.overlay)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    when {
                        bmp == null -> "Waiting for camera…"
                        ui.facePresent -> "Face tracked"
                        else -> "No face detected"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun ModelStatusRow(ui: UiState) {
    val ok = ui.modelReady
    AssistChip(
        onClick = {},
        label = { Text(if (ok) "Face model ready" else "Face model missing") },
        leadingIcon = {},
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (ok) DrowsyPalette.normal.copy(0.2f) else MaterialTheme.colorScheme.error.copy(0.2f),
        ),
    )
    if (!ok) {
        Text(ui.modelLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun MetricsRow(ui: UiState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricChip("EAR", "%.2f".format(ui.ear))
        MetricChip("MAR", "%.2f".format(ui.mar))
        MetricChip("PERCLOS", "${"%.0f".format(ui.perclos * 100)}%")
        MetricChip("Score", "${ui.score}")
    }
}

@Composable
private fun MetricChip(label: String, value: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun NightVisionRow(on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("Night vision (IR)", style = MaterialTheme.typography.titleSmall)
            Text("Off in daylight", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(0.6f))
        }
        Switch(checked = on, onCheckedChange = onToggle)
    }
}

@Composable
private fun FatigueAlertCard(ui: UiState) {
    Card(colors = CardDefaults.cardColors(containerColor = DrowsyPalette.highRisk)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("FATIGUE DETECTED", color = Color.White, fontWeight = FontWeight.Bold)
            Text("Score ${ui.score} / 100", color = Color.White)
            if (ui.alertActive) Text("Alert sounding", color = Color.White)
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
            val r = if (i in key) 4f else 1.5f
            drawCircle(Color(0xFF00E676), radius = r, center = Offset(p.x * w, p.y * h))
        }
    }
}

@Composable
fun EventsScreen(vm: MonitorViewModel) {
    val ui by vm.ui.collectAsState()
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Fatigue events", style = MaterialTheme.typography.headlineSmall)
        Text("Today: ${ui.eventsToday}")
        if (ui.recentEvents.isEmpty()) {
            Text(
                "No events yet. Close your eyes for a few seconds while facing the camera.",
                color = MaterialTheme.colorScheme.onSurface.copy(0.7f),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ui.recentEvents, key = { it.eventId }) { ev ->
                    Card {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${ev.severity} · score ${ev.maxFatigueScore}", fontWeight = FontWeight.SemiBold)
                            Text("${fmt.format(Date(ev.timestampStart))} · ${ev.durationMs} ms")
                            Text(
                                "Eyes ${ev.eyeClosure} · Yawn ${ev.yawning} · Recovered ${ev.recovered}",
                                style = MaterialTheme.typography.bodySmall,
                            )
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
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Device", style = MaterialTheme.typography.headlineSmall)
        Text(ui.modelLabel, style = MaterialTheme.typography.bodyMedium)
        Text(
            "Drowsiness uses EAR + MAR + PERCLOS on-device (no separate drowsiness neural net).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(0.7f),
        )
        if (ui.vehicleDevice) {
            Text("ESP32 camera board — speaker, mic, and IR are on the vehicle unit.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.testVehicleSpeaker() }) { Text("Test speaker") }
                OutlinedButton(onClick = { vm.testDeviceMic() }) { Text("Test mic") }
            }
            NightVisionRow(ui.nightVisionOn) { vm.setNightVision(it) }
            Text(
                "Plug a 4 ohm or 8 ohm speaker into the board SPK connector.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text("Using phone camera — vehicle hardware tests are unavailable.")
        }
        OutlinedButton(onClick = { vm.testPhoneBeep() }) { Text("Test phone beep") }
        if (ui.deviceMessage.isNotBlank()) {
            Text(ui.deviceMessage, style = MaterialTheme.typography.bodySmall, color = DrowsyPalette.attention)
        }
    }
}

@Composable
fun StatusCard(ui: UiState) {
    val color = when (ui.state) {
        DriverState.NORMAL -> DrowsyPalette.normal
        DriverState.ATTENTION -> DrowsyPalette.attention
        DriverState.FATIGUE -> DrowsyPalette.fatigue
        DriverState.HIGH_RISK -> DrowsyPalette.highRisk
    }
    Card(colors = CardDefaults.cardColors(containerColor = color), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(ui.state.name, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Fatigue ${ui.score} / 100", color = Color.White)
            Text(
                "Eyes: ${if (ui.maxClosureMs > 1000) "Closed ${ui.maxClosureMs}ms" else if (ui.eyesClosed) "Closed" else "Open"}",
                color = Color.White,
            )
            Text("Yawns: ${ui.yawnCount}", color = Color.White)
            Text("Head: ${if (ui.headAbnormal) "Abnormal" else "Normal"}", color = Color.White)
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
        "FPS ${"%.1f".format(perf.inferenceFps)} · infer ${perf.inferenceMs}ms · face ${"%.2f".format(perf.faceConfidence)} · ${perf.state}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(0.5f),
    )
}
