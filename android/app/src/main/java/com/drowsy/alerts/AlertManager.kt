package com.drowsy.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.drowsy.fatigue.DriverState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.sin

enum class AlertLevel { NONE, ATTENTION, FATIGUE, HIGH_RISK }

interface AlertManager {
    fun attention(nowMs: Long)
    fun fatigueWarning(nowMs: Long)
    fun highRiskWarning(nowMs: Long)
    fun stop(nowMs: Long)
    fun handleState(state: DriverState, nowMs: Long, didAlert: Boolean): Boolean
}

class PhoneAlertManager(
    context: Context,
    private val beepOnAttention: Boolean = false,
    private val scope: CoroutineScope,
) : AlertManager {
    private val appContext = context.applicationContext
    private val audioMutex = Mutex()
    private var active: AlertLevel = AlertLevel.NONE
        private set
    var lastBeepMs: Long? = null
        private set
    val history: MutableList<Pair<Long, String>> = mutableListOf()

    override fun attention(nowMs: Long) {
        if (!beepOnAttention) return
        active = AlertLevel.ATTENTION; lastBeepMs = nowMs
        history.add(nowMs to "ATTENTION_BEEP")
        scope.launch { beep(800, 150) }
    }
    override fun fatigueWarning(nowMs: Long) {
        active = AlertLevel.FATIGUE; lastBeepMs = nowMs
        history.add(nowMs to "FATIGUE_BEEP")
        scope.launch { beep(1000, 400) }
    }
    override fun highRiskWarning(nowMs: Long) {
        active = AlertLevel.HIGH_RISK; lastBeepMs = nowMs
        history.add(nowMs to "HIGH_RISK_BEEP")
        scope.launch { beep(1200, 600); beep(1200, 600) }
    }
    override fun stop(nowMs: Long) {
        if (active != AlertLevel.NONE) history.add(nowMs to "STOP")
        active = AlertLevel.NONE
    }

    override fun handleState(state: DriverState, nowMs: Long, didAlert: Boolean): Boolean {
        return when {
            state == DriverState.HIGH_RISK && didAlert -> { highRiskWarning(nowMs); true }
            state == DriverState.FATIGUE && didAlert -> { fatigueWarning(nowMs); true }
            state == DriverState.ATTENTION && beepOnAttention && didAlert -> { attention(nowMs); true }
            state == DriverState.NORMAL -> { stop(nowMs); false }
            state == DriverState.ATTENTION -> { if (active != AlertLevel.NONE && active != AlertLevel.ATTENTION) stop(nowMs); false }
            else -> false
        }
    }

    private suspend fun beep(freqHz: Int, durationMs: Int) = audioMutex.withLock {
        withContext(Dispatchers.Default) {
            var track: AudioTrack? = null
            try {
                val sr = 44100
                val n = (sr * durationMs / 1000).toInt()
                val buf = ShortArray(n) { i ->
                    (Short.MAX_VALUE * 0.7 * sin(2 * Math.PI * freqHz * i / sr)).toInt().toShort()
                }
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(buf.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(buf, 0, buf.size)
                track.play()
                delay(durationMs.toLong() + 20)
            } catch (_: Exception) { /* headless / no audio */ }
            finally {
                try { track?.stop() } catch (_: Exception) {}
                try { track?.release() } catch (_: Exception) {}
            }
        }
    }
}