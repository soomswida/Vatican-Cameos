package com.vaticancameos.model

import android.content.Context
import android.util.Log
import com.vaticancameos.statemachine.SafewordConfig
import com.vaticancameos.statemachine.SafewordState


 //Layer-aware CNN classifier wrapper.
class KeywordClassifier(
    private val context: Context,
    private val config: SafewordConfig
) {
    private val TAG = "KeywordClassifier"

    // TFLite Interpreter placeholders
    private var defaultInterpreter: Any? = null
    private var idleInterpreter: Any? = null
    private var standbyInterpreter: Any? = null

    data class ClassificationResult(
        val label: String,
        val confidence: Float,
        val isKeyword: Boolean
    )

    // ── Lifecycle ─────────────────────────────────────────────────

    fun loadAll() {
        defaultInterpreter = loadModel("smallest_cnn.tflite")
        idleInterpreter    = loadModel("mid_cnn.tflite")
        standbyInterpreter = loadModel("large_cnn.tflite")
        Log.i(TAG, "All models loaded")
    }

    fun release() {
        // (interpreter as? Interpreter)?.close()
        defaultInterpreter = null
        idleInterpreter    = null
        standbyInterpreter = null
    }

    // ── Inference ─────────────────────────────────────────────────

    fun classify(
        melSpectrogram: FloatArray,
        melBins: Int,
        timeSteps: Int,
        state: SafewordState
    ): ClassificationResult {

        val (targetKeyword, threshold) = targetFor(state)
        val interpreter = interpreterFor(state)

        if (interpreter == null || melSpectrogram.isEmpty()) {
            return ClassificationResult("none", 0f, false)
        }

        // ── TFLite inference (stub) ──────────────────────────────
        // val inputTensor  = Array(1) { Array(timeSteps) { FloatArray(melBins) } }
        // val outputTensor = Array(1) { FloatArray(NUM_CLASSES) }
        // flattenedMel → inputTensor
        // interpreter.run(inputTensor, outputTensor)
        // val scores = outputTensor[0]
        // val bestIdx = scores.indices.maxByOrNull { scores[it] } ?: 0
        // val label   = LABELS[bestIdx]
        // val conf    = scores[bestIdx]
        // ────────────────────────────────────────────────────────

        // Stub: random confidence for structural testing
        val mockConfidence = if (Math.random() < 0.05) 0.92f else 0.10f
        val mockLabel      = if (mockConfidence > threshold) targetKeyword else "none"

        return ClassificationResult(
            label      = mockLabel,
            confidence = mockConfidence,
            isKeyword  = mockLabel == targetKeyword && mockConfidence >= threshold
        ).also {
            Log.d(TAG, "[$state] classify → label=${it.label}, conf=${"%.2f".format(it.confidence)}")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    /**
     * L1 (DEFAULT): low threshold → minimize FN (high recall)
     * L2 (IDLE)   : middle threshold
     * L3 (STANDBY): high threshold → maximize FP (high precision)
     */
    private fun targetFor(state: SafewordState): Pair<String, Float> = when (state) {
        SafewordState.DEFAULT -> Pair(config.keywordL1, 0.70f)
        SafewordState.IDLE    -> Pair(config.keywordL2, 0.80f)
        SafewordState.STANDBY -> Pair(config.keywordL3, 0.90f)
        SafewordState.ACTION  -> Pair("", 1.0f)
    }

    private fun interpreterFor(state: SafewordState): Any? = when (state) {
        SafewordState.DEFAULT -> defaultInterpreter
        SafewordState.IDLE    -> idleInterpreter
        SafewordState.STANDBY,
        SafewordState.ACTION  -> standbyInterpreter
    }

    private fun loadModel(assetName: String): Any? {
        return try {
            // val modelBuffer = loadModelFile(context.assets, assetName)
            // Interpreter(modelBuffer, Interpreter.Options().apply { setNumThreads(2) })
            Log.i(TAG, "Model loaded: $assetName")
            Object() // stub
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $assetName: ${e.message}")
            null
        }
    }
}
