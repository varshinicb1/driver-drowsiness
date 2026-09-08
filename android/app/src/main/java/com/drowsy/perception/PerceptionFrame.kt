package com.drowsy.perception

/**
 * Model-independent perception output (§7-8). Mirrors Python PerceptionFrame (§5).
 * No MediaPipe types leak outside this module.
 */
data class Point2D(val x: Float, val y: Float)

data class EyeFeatures(
    val earLeft: Float,
    val earRight: Float,
    val earMean: Float,
    val eyesClosed: Boolean,
    val closureConfidence: Float,
)

data class MouthFeatures(
    val mar: Float,
    val isYawning: Boolean,
    val mouthOpenRatio: Float,
)

data class HeadPose(
    val pitch: Float,
    val yaw: Float,
    val roll: Float,
    val abnormal: Boolean,
)

data class GazeFeatures(
    val yaw: Float,
    val pitch: Float,
    val forwardProb: Float,
)

data class PerceptionFrame(
    val facePresent: Boolean,
    val faceConfidence: Float,
    val landmarkConfidence: Float,
    val trackingQuality: Float,
    val eye: EyeFeatures? = null,
    val mouth: MouthFeatures? = null,
    val headPose: HeadPose? = null,
    val gaze: GazeFeatures? = null,
    val timestampMs: Long = 0L,
    // Convenience: expose left/right eyes independently (§8)
    val leftEye: EyeFeatures? = null,
    val rightEye: EyeFeatures? = null,
    val nose: Point2D? = null,
    val faceOrientation: HeadPose? = null,
    /** Normalized 0..1 landmarks for UI overlay only; fatigue ignores this. */
    val landmarks: List<Point2D>? = null,
    /** Center luma 0-255 for adaptive thresholds (§11). */
    val sceneLuma: Float = 128f,
)
