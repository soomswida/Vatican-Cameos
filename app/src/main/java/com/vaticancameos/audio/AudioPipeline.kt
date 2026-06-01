package com.vaticancameos.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.vaticancameos.statemachine.SafewordConfig
import com.vaticancameos.statemachine.SafewordState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


//Layer-aware audio pipeline with proper Mel extraction.

class AudioPipeline(
    private val config: SafewordConfig,
    private val scope: CoroutineScope
) {
    private val TAG = "AudioPipeline"

    private val _frames = MutableSharedFlow<AudioFrame>(extraBufferCapacity = 32)
    val frames: SharedFlow<AudioFrame> = _frames

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var currentState: SafewordState = SafewordState.DEFAULT

    data class AudioFrame(
        val melSpectrogram: FloatArray,
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

    fun start()  { restartRecording() }
    fun stop()   {
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    // ── Per-layer config ─────────────────────────────────────────

    private data class LayerSpec(
        val sampleRate: Int,
        val melBins: Int,
        val targetLength: Int
    )

    private fun specFor(state: SafewordState): LayerSpec = when (state) {
        SafewordState.DEFAULT -> LayerSpec(8_000,  20, 49)
        SafewordState.IDLE    -> LayerSpec(16_000, 40, 98)
        SafewordState.STANDBY,
        SafewordState.ACTION  -> LayerSpec(16_000, 40, 98)
    }

    // ── Recording loop ──────────────────────────────────────────

    private fun restartRecording() {
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()

        val spec = specFor(currentState)
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(spec.sampleRate, channelConfig, encoding),
            spec.sampleRate / 10
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            spec.sampleRate,
            channelConfig,
            encoding,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed at ${spec.sampleRate}Hz")
            return
        }

        audioRecord!!.startRecording()
        Log.i(TAG, "Recording: ${spec.sampleRate}Hz, ${spec.melBins} mel bins, target=${spec.targetLength}")

        recordingJob = scope.launch(Dispatchers.IO) {
            val readBuffer  = ShortArray(bufferSize)
            val oneSecond   = FloatArray(spec.sampleRate)  // buffer
            var fillPos = 0

            val extractor = TorchaudioMelExtractor(
                sampleRate   = spec.sampleRate,
                melBins      = spec.melBins,
                targetLength = spec.targetLength,
            )

            while (isActive) {
                val read = audioRecord?.read(readBuffer, 0, bufferSize) ?: break
                if (read <= 0) continue

                // Short PCM → Float, put it in the buffer
                for (i in 0 until read) {
                    if (fillPos < spec.sampleRate) {
                        oneSecond[fillPos++] = readBuffer[i] / 32768f
                    }
                }

                // mel extracting → emit → reset
                if (fillPos >= spec.sampleRate) {
                    val waveformCopy = oneSecond.copyOf()
                    val mel = extractor.extract(waveformCopy)

                    _frames.emit(AudioFrame(
                        melSpectrogram = mel,
                        melBins        = spec.melBins,
                        timeSteps      = spec.targetLength,
                        state          = currentState
                    ))

                    fillPos = 0
                }
            }
        }
    }
}