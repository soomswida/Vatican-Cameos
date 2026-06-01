package com.vaticancameos.audio

import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * torchaudio.transforms.MelSpectrogram + AmplitudeToDB
 *  -> Extractor equivalent to these
 *
 * Learning Pipeline
 *   waveform → STFT (n_fft, win, hop) → power spectrum → mel filterbank
 *           → AmplitudeToDB(top_db=80) → Normalize(mean=0, std=1)
 *
 * Same parameters as the notebook
 *   L1: sample_rate=8000,  mel_bins=20, target_length=49
 *   L2: sample_rate=16000, mel_bins=40, target_length=98
 *   L3: sample_rate=16000, mel_bins=80, target_length=98
 */
class TorchaudioMelExtractor(
    val sampleRate: Int,
    val melBins: Int,
    val targetLength: Int,
    val nFft: Int = 512,
    windowMs: Int = 25,
    hopMs: Int = 10,
    private val fMin: Float = 0f,
    private val fMax: Float = sampleRate / 2f,
    private val topDb: Float = 80f,
) {
//    private val winLength = (sampleRate * windowMs / 1000)
    private val winLength = (sampleRate * windowMs / 2000)
//    private val hopLength = (sampleRate * hopMs / 1000)
    private val hopLength = (sampleRate * hopMs / 2000)
    private val numFreqBins = nFft / 2 + 1

    private val fft = FloatFFT_1D(nFft.toLong())
    private val hannWindow: FloatArray = createHannWindow(winLength)
    private val melFilterbank: Array<FloatArray> = createMelFilterbank()

    // ════════════════════════════════════════════════════════════
    //  Public — 1 sec of waveform → log-Mel [targetLength, melBins]
    // ════════════════════════════════════════════════════════════

    fun extract(waveform: FloatArray): FloatArray {
        // 1. STFT → power spectrogram  [numFreqBins, T]
        val power = computePowerSpectrogram(waveform)
        val timeSteps = power[0].size

        // 2. Mel filterbank
        val mel = applyMelFilterbank(power, timeSteps)

        // 3. Power → dB  (AmplitudeToDB)
        val melDb = amplitudeToDb(mel, timeSteps)

        // 4. [time, melBins] transpose + target_length normalize
        val transposed = transposeAndPad(melDb, timeSteps)

        // 5. normalize (per-sample mean=0, std=1)
        // normalizeInPlace(transposed)

        return transposed   // flattened: targetLength * melBins
    }

    // ════════════════════════════════════════════════════════════
    //  Step 1 — Power Spectrogram (STFT magnitude squared)
    // ════════════════════════════════════════════════════════════

    private fun computePowerSpectrogram(waveform: FloatArray): Array<FloatArray> {
        // torchaudio default
        val padded = reflectPad(waveform, nFft / 2)

        val numFrames = (padded.size - winLength) / hopLength + 1
        // shape: [numFreqBins, numFrames]
        val power = Array(numFreqBins) { FloatArray(numFrames) }

        val fftBuffer = FloatArray(nFft)   // 재사용 버퍼

        for (frameIdx in 0 until numFrames) {
            val start = frameIdx * hopLength

            // window + n_fft of zero-pad
            java.util.Arrays.fill(fftBuffer, 0f)
            for (i in 0 until winLength) {
                fftBuffer[i] = padded[start + i] * hannWindow[i]
            }

            // In-place real FFT
            fft.realForward(fftBuffer)
            // JTransforms 출력 형식:
            //   fftBuffer[0]   = Re(X[0])         (DC)
            //   fftBuffer[1]   = Re(X[N/2])       (Nyquist)
            //   fftBuffer[2k]  = Re(X[k])   (k = 1..N/2-1)
            //   fftBuffer[2k+1]= Im(X[k])   (k = 1..N/2-1)

            // DC
            power[0][frameIdx] = fftBuffer[0] * fftBuffer[0]
            // Nyquist
            power[numFreqBins - 1][frameIdx] = fftBuffer[1] * fftBuffer[1]
            // etc.
            for (k in 1 until numFreqBins - 1) {
                val re = fftBuffer[2 * k]
                val im = fftBuffer[2 * k + 1]
                power[k][frameIdx] = re * re + im * im
            }
        }

        return power
    }

    private fun reflectPad(signal: FloatArray, padSize: Int): FloatArray {
        val out = FloatArray(signal.size + 2 * padSize)
        // left part - reflection
        for (i in 0 until padSize) {
            out[i] = signal[padSize - i]
        }
        // body
        signal.copyInto(out, padSize)
        // right part - reflection
        for (i in 0 until padSize) {
            out[out.size - 1 - i] = signal[signal.size - 1 - padSize + i]
        }
        return out
    }

    // ════════════════════════════════════════════════════════════
    //  Step 2 — Mel filterbank
    // ════════════════════════════════════════════════════════════

    private fun applyMelFilterbank(
        power: Array<FloatArray>,
        timeSteps: Int
    ): Array<FloatArray> {
        // mel[m, t] = sum_k filterbank[m, k] * power[k, t]
        val mel = Array(melBins) { FloatArray(timeSteps) }
        for (m in 0 until melBins) {
            for (t in 0 until timeSteps) {
                var sum = 0f
                for (k in 0 until numFreqBins) {
                    sum += melFilterbank[m][k] * power[k][t]
                }
                mel[m][t] = sum
            }
        }
        return mel
    }

    /**
     * mel filterbank equivalent to torchaudio.functional.melscale_fbanks  생성.
     * Slaney-style
     */
    private fun createMelFilterbank(): Array<FloatArray> {
        fun hzToMel(hz: Float): Float = 2595f * log10(1f + hz / 700f)
        fun melToHz(mel: Float): Float = 700f * (Math.pow(10.0, (mel / 2595f).toDouble()).toFloat() - 1f)

        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)

        // melBins + 2 of mel-spaced points
        val melPoints = FloatArray(melBins + 2) { i ->
            melMin + (melMax - melMin) * i / (melBins + 1)
        }
        val hzPoints = FloatArray(melBins + 2) { melToHz(melPoints[it]) }

        // to FFT bin index
        val binPoints = FloatArray(melBins + 2) { hzPoints[it] * nFft / sampleRate }

        // Triangular filterbank
        val fb = Array(melBins) { FloatArray(numFreqBins) }
        for (m in 0 until melBins) {
            val left   = binPoints[m]
            val center = binPoints[m + 1]
            val right  = binPoints[m + 2]

            for (k in 0 until numFreqBins) {
                val k_f = k.toFloat()
                val weight = when {
                    k_f < left  -> 0f
                    k_f < center -> (k_f - left) / (center - left)
                    k_f < right  -> (right - k_f) / (right - center)
                    else -> 0f
                }
                fb[m][k] = weight
            }
        }
        return fb
    }

    // ════════════════════════════════════════════════════════════
    //  Step 3 — Amplitude → dB  (torchaudio.transforms.AmplitudeToDB)
    // ════════════════════════════════════════════════════════════

    private fun amplitudeToDb(
        mel: Array<FloatArray>,
        timeSteps: Int
    ): Array<FloatArray> {
        val multiplier = 10f
        val aminLog = log10(1e-10f)  // amin = 1e-10

        val db = Array(melBins) { m ->
            FloatArray(timeSteps) { t ->
                multiplier * max(log10(mel[m][t] + 1e-10f), aminLog)
            }
        }

        // top_db clipping
        var maxVal = Float.NEGATIVE_INFINITY
        for (m in 0 until melBins) {
            for (t in 0 until timeSteps) {
                if (db[m][t] > maxVal) maxVal = db[m][t]
            }
        }
        val cutoff = maxVal - topDb
        for (m in 0 until melBins) {
            for (t in 0 until timeSteps) {
                if (db[m][t] < cutoff) db[m][t] = cutoff
            }
        }
        return db
    }

    // ════════════════════════════════════════════════════════════
    //  Step 4 — Transpose + pad/truncate to targetLength
    //          [melBins, T] → [targetLength, melBins] (flattened)
    // ════════════════════════════════════════════════════════════

    private fun transposeAndPad(
        melDb: Array<FloatArray>,
        timeSteps: Int
    ): FloatArray {
        val out = FloatArray(targetLength * melBins)
        val copyT = minOf(timeSteps, targetLength)
        for (t in 0 until copyT) {
            for (m in 0 until melBins) {
                out[t * melBins + m] = melDb[m][t]
            }
        }
        return out
    }

    // ════════════════════════════════════════════════════════════
    //  Step 5 — In-place normalization (mean=0, std=1)
    // ════════════════════════════════════════════════════════════

    private fun normalizeInPlace(data: FloatArray) {
        var sum = 0f
        for (v in data) sum += v
        val mean = sum / data.size

        var varSum = 0f
        for (v in data) {
            val d = v - mean
            varSum += d * d
        }
        val std = sqrt(varSum / data.size)
        val invStd = 1f / (std + 1e-8f)

        for (i in data.indices) {
            data[i] = (data[i] - mean) * invStd
        }
    }

    // ════════════════════════════════════════════════════════════
    //  Hann window
    // ════════════════════════════════════════════════════════════

    private fun createHannWindow(size: Int): FloatArray {
        // torchaudio default
        return FloatArray(size) { i ->
            (0.5f * (1f - cos(2.0 * PI * i / size).toFloat()))
        }
    }
}