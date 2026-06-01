package com.vaticancameos.model

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import org.tensorflow.lite.Interpreter
import com.vaticancameos.statemachine.SafewordConfig
import com.vaticancameos.statemachine.SafewordState
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * mel-input  (l1/l2/l3_integer_quant.tflite)
 *
 * input : log-Mel spectrogram [1, 1, targetLength, melBins]
 *   L1: [1, 1, 49, 20]
 *   L2: [1, 1, 98, 40]
 *   L3: [1, 1, 98, 40]
 *
 * by TorchaudioMelExtractor
 */
class KeywordClassifier(
    private val context: Context,
    private val config: SafewordConfig
) {
    private val TAG = "KeywordClassifier"

    private var defaultInterpreter: Interpreter? = null
    private var idleInterpreter:    Interpreter? = null
    private var standbyInterpreter: Interpreter? = null

    data class ClassificationResult(
        val label: String,
        val confidence: Float,
        val isKeyword: Boolean
    )

    companion object {
        val LABELS = listOf(
            "yes", "no", "up", "down", "left", "right",
            "on", "off", "stop", "go", "_silence_"
        )
        const val NUM_CLASSES = 11

        const val TARGET_LENGTH_L1 = 49
        const val TARGET_LENGTH_L2 = 98
        const val TARGET_LENGTH_L3 = 98
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    fun loadAll() {
//        defaultInterpreter = loadModel("l1_integer_quant.tflite")
//        idleInterpreter    = loadModel("l2_integer_quant.tflite")
//        standbyInterpreter = loadModel("l3_integer_quant.tflite")
        defaultInterpreter = loadModel("l1_float32.tflite")
        idleInterpreter    = loadModel("l2_float32.tflite")
        standbyInterpreter = loadModel("l3_float32.tflite")
        Log.i(TAG, "All models loaded")
    }

    fun release() {
        defaultInterpreter?.close()
        idleInterpreter?.close()
        standbyInterpreter?.close()
        defaultInterpreter = null
        idleInterpreter    = null
        standbyInterpreter = null
    }

    // ── Inference ─────────────────────────────────────────────────

    fun classify(
        melSpectrogram: FloatArray,   // already shape [targetLength * melBins] flattened
        melBins: Int,
        timeSteps: Int,
        state: SafewordState
    ): ClassificationResult {

        val (targetKeyword, threshold) = targetFor(state)
        val interpreter = interpreterFor(state)

        if (interpreter == null || melSpectrogram.isEmpty()) {
            return ClassificationResult("none", 0f, false)
        }

        try {
            val targetLength = when (state) {
                SafewordState.DEFAULT -> TARGET_LENGTH_L1
                SafewordState.IDLE    -> TARGET_LENGTH_L2
                SafewordState.STANDBY,
                SafewordState.ACTION  -> TARGET_LENGTH_L3
            }

            // [1, 1, targetLength, melBins]
            val inputTensor = Array(1) {
                Array(1) {
                    Array(targetLength) { t ->
                        FloatArray(melBins) { m ->
                            val idx = t * melBins + m
                            if (idx < melSpectrogram.size) melSpectrogram[idx] else 0f
                        }
                    }
                }
            }

            val outputTensor = Array(1) { FloatArray(NUM_CLASSES) }

            interpreter.run(inputTensor, outputTensor)

            val probs    = softmax(outputTensor[0])
            val bestIdx  = probs.indices.maxByOrNull { probs[it] } ?: 0
            val bestProb = probs[bestIdx]
            val bestLabel = LABELS[bestIdx]

            val isKeyword = (bestLabel == targetKeyword && bestProb >= threshold)

            val distStr = probs.mapIndexed { i, p -> "${LABELS[i]}=${"%.2f".format(p)}" }
                .joinToString(", ")
            Log.d(TAG, "[$state] dist: $distStr")

            return ClassificationResult(bestLabel, bestProb, isKeyword).also {
                Log.d(TAG, "[$state] → label=${it.label}, " +
                        "conf=${"%.2f".format(it.confidence)}, " +
                        "target=$targetKeyword, match=${it.isKeyword}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}", e)
            return ClassificationResult("none", 0f, false)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun softmax(logits: FloatArray): FloatArray {
        val maxLogit = logits.max()
        val exps = FloatArray(logits.size) {
            exp((logits[it] - maxLogit).toDouble()).toFloat()
        }
        val sumExp = exps.sum()
        return FloatArray(logits.size) { exps[it] / sumExp }
    }

    private fun targetFor(state: SafewordState): Pair<String, Float> = when (state) {
        SafewordState.DEFAULT -> Pair(config.keywordL1, 0.70f)
        SafewordState.IDLE    -> Pair(config.keywordL2, 0.80f)
        SafewordState.STANDBY -> Pair(config.keywordL3, 0.90f)
        SafewordState.ACTION  -> Pair("", 1.0f)
    }

    private fun interpreterFor(state: SafewordState): Interpreter? = when (state) {
        SafewordState.DEFAULT -> defaultInterpreter
        SafewordState.IDLE    -> idleInterpreter
        SafewordState.STANDBY,
        SafewordState.ACTION  -> standbyInterpreter
    }

    private fun loadModel(assetName: String): Interpreter? {
        return try {
            val buffer = loadModelFile(context.assets, assetName)
            Interpreter(buffer, Interpreter.Options().apply { setNumThreads(2) }).also {
                Log.i(TAG, "Model loaded: $assetName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $assetName: ${e.message}")
            null
        }
    }

    private fun loadModelFile(assets: AssetManager, name: String): MappedByteBuffer {
        val fd = assets.openFd(name)
        FileInputStream(fd.fileDescriptor).use { input ->
            return input.channel.map(
                FileChannel.MapMode.READ_ONLY,
                fd.startOffset,
                fd.declaredLength
            )
        }
    }
}