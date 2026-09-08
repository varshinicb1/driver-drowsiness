package com.drowsy.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.drowsy.camera.CameraSource
import com.drowsy.location.LocationProvider
import com.drowsy.perception.DriverPerceptionEngine
import com.drowsy.ui.MonitorViewModel

class MonitorViewModelFactory(
    private val app: Application,
    private val camera: CameraSource,
    private val perception: DriverPerceptionEngine,
    private val locationProvider: LocationProvider? = null,
    private val deviceBaseUrl: String? = null,
    private val deviceNetwork: android.net.Network? = null,
    private val modelReady: Boolean = false,
    private val modelLabel: String = "",
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MonitorViewModel(
            app, camera, perception, locationProvider = locationProvider,
            deviceBaseUrl = deviceBaseUrl, deviceNetwork = deviceNetwork,
            modelReady = modelReady, modelLabel = modelLabel,
        ) as T
    }
}
