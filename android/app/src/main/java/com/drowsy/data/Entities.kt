package com.drowsy.data

import androidx.room.*
import java.util.UUID

/** Severity mirrors Python data.Severity. */
enum class Severity { LOW, MEDIUM, HIGH }
enum class DriverStateStr { NORMAL, ATTENTION, FATIGUE, HIGH_RISK }

@Entity(tableName = "vehicles")
data class Vehicle(
    @PrimaryKey val vehicleId: String,
    val name: String? = null,
    val fleetId: String? = null,
    val lastScore: Int = 0,
    val lastState: String = DriverStateStr.NORMAL.name,
    val lastSeen: Long? = null, // epoch ms
)

@Entity(tableName = "devices", indices = [Index("vehicleId")])
data class Device(
    @PrimaryKey val deviceId: String,
    val vehicleId: String,
    val name: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "fatigue_events", indices = [Index("deviceId"), Index(value = ["vehicleId"]), Index(value = ["severity"]), Index(value = ["synced"])])
data class FatigueEvent(
    @PrimaryKey val eventId: String = UUID.randomUUID().toString(),
    val deviceId: String,
    val vehicleId: String,
    val timestampStart: Long,
    val timestampEnd: Long,
    val durationMs: Int,
    val severity: String, // Severity.name
    val maxFatigueScore: Int,
    val eyeClosure: Boolean = false,
    val yawning: Boolean = false,
    val headPoseAbnormal: Boolean = false,
    val alertTriggered: Boolean = false,
    val recovered: Boolean = false,
    val gpsLat: Double = 0.0,
    val gpsLng: Double = 0.0,
    val trackingQuality: Float = 1f,
    val synced: Boolean = false,
)

@Entity(tableName = "alert_events")
data class AlertEvent(
    @PrimaryKey val alertId: String = UUID.randomUUID().toString(),
    val eventId: String? = null,
    val deviceId: String,
    val vehicleId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val alertType: String, // ATTENTION/FATIGUE/HIGH_RISK
    val score: Int,
)

@Entity(tableName = "sync_queue")
data class SyncQueue(
    @PrimaryKey val queueId: String = UUID.randomUUID().toString(),
    val eventId: String,
    val attempts: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAttempt: Long? = null,
)

@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey val key: String,
    val value: String,
)

@Entity(tableName = "calibration")
data class Calibration(
    @PrimaryKey val calibrationId: String = UUID.randomUUID().toString(),
    val deviceId: String,
    val baselinePitch: Float = 0f,
    val baselineYaw: Float = 0f,
    val baselineRoll: Float = 0f,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey val tripId: String = UUID.randomUUID().toString(),
    val vehicleId: String,
    val deviceId: String,
    val startMs: Long = System.currentTimeMillis(),
    val endMs: Long? = null,
)
