package com.drowsy.fatigue

import com.drowsy.perception.PerceptionFrame
import kotlin.math.min
import kotlin.math.roundToInt

/** Weighted 0-100 score (§10). Tracking-quality gated. Mirrors Python score_snapshot. */
fun scoreSnapshot(s: TemporalSnapshot, th: FatigueThresholds): Int {
    val perclosScore = if (th.perclosHigh > 0) min(100f, (s.perclos / th.perclosHigh) * 100f) else 0f
    val closureScore = if (th.closureHighMs > 0) min(100f, (s.maxClosureMs.toFloat() / th.closureHighMs) * 100f) else 0f
    val yawnScore = if (th.yawnHighCount > 0) min(100f, (s.yawnCount.toFloat() / th.yawnHighCount) * 100f) else 0f
    val headScore = s.headAbnormalRatio * 100f
    val gazeScore = s.gazeOffRatio * 100f
    var raw = th.wPerclos * perclosScore + th.wMaxClosure * closureScore + th.wYawn * yawnScore + th.wHeadPose * headScore + th.wGaze * gazeScore
    if (s.prolongedClosure && s.maxClosureMs > 1000) raw = min(100f, raw + 10f)
    val gate = when {
        s.meanSceneLuma < 55f -> th.trackingQualityGate * 0.65f
        s.meanSceneLuma < 80f -> th.trackingQualityGate * 0.82f
        else -> th.trackingQualityGate
    }
    if (s.trackingQualityMean < gate && gate > 0) {
        raw *= (s.trackingQualityMean / gate)
    }
    return raw.roundToInt().coerceIn(0, 100)
}

/** Port of Python FatigueEngine:148 — buffer + scoring + alert cooldown. */
class FatigueEngine(
    val thresholds: FatigueThresholds = FatigueThresholds(),
    val temporal: TemporalConfig = TemporalConfig(),
    val perceptionCfg: PerceptionConfig = PerceptionConfig(),
) {
    val buffer = TemporalFeatureBuffer(temporal, perceptionCfg)
    private var lastAlertMs: Long? = null

    fun update(frame: PerceptionFrame): Pair<Int, TemporalSnapshot> {
        buffer.push(frame)
        val snap = buffer.snapshot(frame.timestampMs)
        val score = scoreSnapshot(snap, thresholds)
        return score to snap
    }

    /** True when cooldown elapsed — use for periodic re-alert while fatigued. */
    fun shouldRepeatAlert(nowMs: Long): Boolean {
        if (!canAlert(nowMs)) return false
        markAlert(nowMs)
        return true
    }

    fun canAlert(nowMs: Long): Boolean {
        val last = lastAlertMs ?: return true
        return (nowMs - last) >= (thresholds.alertCooldownS * 1000).toLong()
    }
    fun markAlert(nowMs: Long) { lastAlertMs = nowMs }
}

/** Seam for future learned model §12 / §25. Keeps TemporalSnapshot → probability contract. */
interface FatigueInferenceEngine {
    fun infer(snapshots: List<TemporalSnapshot>): Float // 0..1 probability
}
class RuleBasedFatigueEngine(private val th: FatigueThresholds = FatigueThresholds()) : FatigueInferenceEngine {
    override fun infer(snapshots: List<TemporalSnapshot>): Float {
        if (snapshots.isEmpty()) return 0f
        return scoreSnapshot(snapshots.last(), th) / 100f
    }
}
// Future: class TemporalMLFatigueEngine(private val tflite: Interpreter) : FatigueInferenceEngine { ... }
