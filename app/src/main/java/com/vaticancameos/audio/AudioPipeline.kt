package com.vaticancameos.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.vaticancameos.statemachine.SafewordConfig
import com.vaticancameos.statemachine.SafewordState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Layer-aware audio pipeline.
 *
 * DEFAULT  → 8kHz,  20 mel bins  (smallest CNN)
 * IDLE     → 16kHz, 40 mel bins  (mid CNN)
 * STANDBY  → 44.1kHz, 80 mel bins (large CNN)
 *
 */
class AudioPipeline(
    private val config: SafewordConfig,
    private val scope: CoroutineScope
) {
    private val TAG = "AudioPipeline"

    // Feature frame emission
    private val _frames = MutableSharedFlow<AudioFrame>(extraBufferCapacity = 32)
    val frames: SharedFlow<AudioFrame> = _frames

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var currentState: SafewordState = SafewordState.DEFAULT

    data class AudioFrame(
        val melSpectrogram: FloatArray,   // flattened [time x melBins]
        val melBins: Int,
        val timeSteps: Int,
        val state: SafewordState
    )

    // ── Public API ──────────────────────────────────────────────

    fun switchToState(state: SafewordState) {
        if (state == currentState) return
        Log.d(TAG, "Pipeline switch: $currentState → $state")
        currentState = state
        restartRecording()
    }

    fun start() {
        restartRecording()
    }

    fun stop() {
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    // ── Internal ─────────────────────────────────────────────────

    private fun configForState(state: SafewordState): Triple<Int, Int, Int> {
        // Returns (sampleRate, melBins, windowMs)
        return when (state) {
            SafewordState.DEFAULT -> Triple(config.defaultSampleRate, config.defaultMelBins, 25)
            SafewordState.IDLE    -> Triple(config.idleSampleRate,    config.idleMelBins,    25)
            SafewordState.STANDBY,
            SafewordState.ACTION  -> Triple(config.standbySampleRate, config.standbyMelBins, 25)
        }
    }

    private fun restartRecording() {
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()

        val (sampleRate, melBins, _) = configForState(currentState)
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding),
            sampleRate / 10   // 100ms buffer minimum
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            encoding,
            bufferSize
        ).also { if (it.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed at ${sampleRate}Hz")
            return
        }}

        audioRecord!!.startRecording()
        Log.i(TAG, "Recording started: ${sampleRate}Hz, ${melBins} mel bins")

        recordingJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(bufferSize)
            val extractor = MelSpectrogramExtractor(sampleRate, melBins)

            while (isActive) {
                val read = audioRecord?.read(buffer, 0, bufferSize) ?: break
                if (read <= 0) continue

                val floatBuffer = FloatArray(read) { buffer[it] / 32768f }
                val (mel, timeSteps) = extractor.extract(floatBuffer)

                _frames.emit(AudioFrame(
                    melSpectrogram = mel,
                    melBins = melBins,
                    timeSteps = timeSteps,
                    state = currentState
                ))
            }
        }
    }
}

/**
 * Minimal log-Mel spectrogram extractor.
 */
class MelSpectrogramExtractor(
    private val sampleRate: Int,
    private val melBins: Int,
    private val windowMs: Int = 25,
    private val hopMs: Int = 10
) {
    private val windowSize = (sampleRate * windowMs / 1000)
    private val hopSize    = (sampleRate * hopMs  / 1000)

    // Returns (flattenedMel: FloatArray, timeSteps: Int)
    fun extract(pcm: FloatArray): Pair<FloatArray, Int> {
        val frames = mutableListOf<FloatArray>()
        var start = 0
        while (start + windowSize <= pcm.size) {
            val frame = pcm.slice(start until start + windowSize).toFloatArray()
            frames.add(applyMelFilterbank(frame))
            start += hopSize
        }
        if (frames.isEmpty()) return Pair(FloatArray(0), 0)

        val flat = FloatArray(frames.size * melBins)
        frames.forEachIndexed { i, f -> f.copyInto(flat, i * melBins) }
        return Pair(flat, frames.size)
    }

    private fun applyMelFilterbank(frame: FloatArray): FloatArray {
        // Simplified: RMS energy per mel band (placeholder for real FFT+mel filterbank)
        // TODO: replace with JTRANSFORMS or TFLite AudioOp
        val bandSize = frame.size / melBins
        return FloatArray(melBins) { b ->
            val band = frame.slice(b * bandSize until minOf((b + 1) * bandSize, frame.size))
            val rms = sqrt(band.sumOf { it.toDouble() * it }.toFloat() / band.size)
            // log-Mel
            if (rms > 1e-10f) (10f * log10(rms + 1e-10f)) else -80f
        }
    }
}
