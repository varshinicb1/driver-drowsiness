package com.drowsy.fatigue

import com.drowsy.perception.PerceptionFrame
import java.util.ArrayDeque

data class TemporalSnapshot(
    val perclos: Float,
    val maxClosureMs: Long,
    val meanEar: Float,
    val blinkCount: Int,
    val yawnCount: Int,
    val headAbnormalRatio: Float,
    val gazeOffRatio: Float,
    val trackingQualityMean: Float,
    val meanSceneLuma: Float = 128f,
    val eyeClosure: Boolean,
    val prolongedClosure: Boolean,
)

/**
 * Rolling temporal buffer §9. Never classify from single frames.
 * Mirrors Python TemporalFeatureBuffer (fatigue:27-119) exactly for parity testing.
 */
class TemporalFeatureBuffer(
    val config: TemporalConfig = TemporalConfig(),
    val pconfig: PerceptionConfig = PerceptionConfig(),
) {
    private val frames: ArrayDeque<PerceptionFrame> = ArrayDeque()
    private var closureStartMs: Long? = null
    private var yawnStartMs: Long? = null
    private val yawnEvents: ArrayDeque<Long> = ArrayDeque()

    fun push(f: PerceptionFrame) {
        frames.addLast(f)
        val cutoff = f.timestampMs - (config.windowS * 1000).toLong()
        while (frames.isNotEmpty() && frames.first().timestampMs < cutoff) frames.removeFirst()
        // yawn streak → event if >=800ms (single open frame must not become yawn §8)
        if (f.mouth?.isYawning == true) {
            if (yawnStartMs == null) yawnStartMs = f.timestampMs
        } else {
            yawnStartMs?.let { start ->
                val dur = f.timestampMs - start
                if (dur >= pconfig.marYawnMinDurationMs) yawnEvents.addLast(start)
                yawnStartMs = null
            }
        }
        while (yawnEvents.isNotEmpty() && yawnEvents.first() < cutoff) yawnEvents.removeFirst()
        // closure streak
        if (f.eye?.eyesClosed == true && f.facePresent) {
            if (closureStartMs == null) closureStartMs = f.timestampMs
        } else {
            closureStartMs = null
        }
    }

    fun snapshot(nowMs: Long): TemporalSnapshot {
        if (frames.isEmpty()) return TemporalSnapshot(0f, 0, 0f, 0, 0, 0f, 0f, 0f, 128f, false, false)
        val total = frames.size
        val closed = frames.count { it.eye?.eyesClosed == true && it.facePresent }
        val perclos = closed.toFloat() / total
        val ears = frames.mapNotNull { it.eye?.earMean }
        val meanEar = if (ears.isNotEmpty()) ears.average().toFloat() else 0f
        // max closure: longest contiguous closed streak, extended to now if still closed — clamp start to window cutoff
        val cutoff = frames.first().timestampMs // window start after prune
        var maxClosure = 0L; var curStart: Long? = null
        for (fr in frames) {
            if (fr.eye?.eyesClosed == true && fr.facePresent) {
                if (curStart == null) curStart = maxOf(fr.timestampMs, cutoff)
                val cur = fr.timestampMs - curStart
                if (cur > maxClosure) maxClosure = cur
            } else { curStart = null }
        }
        closureStartMs?.let { start ->
            val clampedStart = maxOf(start, cutoff)
            val cur = nowMs - clampedStart; if (cur > maxClosure) maxClosure = cur
        }
        // blink count: only closures with 100ms < dur < eyeClosureMinMs (short blinks), prolonged excluded
        var blinkCount = 0; var inClosure = false; var cStart: Long? = null
        for (fr in frames) {
            val closedNow = fr.eye?.eyesClosed == true && fr.facePresent
            if (closedNow && !inClosure) { inClosure = true; cStart = fr.timestampMs }
            else if (!closedNow && inClosure) {
                val dur = fr.timestampMs - (cStart ?: fr.timestampMs)
                if (dur in 100 until pconfig.eyeClosureMinMs) blinkCount++
                inClosure = false; cStart = null
            }
        }
        val headAb = frames.count { it.headPose?.abnormal == true }.toFloat() / total
        val gazeOff = frames.count { (it.gaze?.forwardProb ?: 1f) < 0.5f }.toFloat() / total
        val tq = frames.map { it.trackingQuality }.average().toFloat()
        val meanLuma = frames.map { it.sceneLuma }.average().toFloat()
        val currentlyClosed = frames.last().let { it.eye?.eyesClosed == true && it.facePresent }
        val prolonged = maxClosure >= pconfig.eyeClosureMinMs
        var yawnCount = yawnEvents.size
        yawnStartMs?.let { if (nowMs - it >= pconfig.marYawnMinDurationMs) yawnCount++ }
        return TemporalSnapshot(perclos, maxClosure, meanEar, blinkCount, yawnCount, headAb, gazeOff, tq, meanLuma, currentlyClosed, prolonged)
    }

    fun size() = frames.size
    fun clear() { frames.clear(); yawnEvents.clear(); closureStartMs=null; yawnStartMs=null }
}
