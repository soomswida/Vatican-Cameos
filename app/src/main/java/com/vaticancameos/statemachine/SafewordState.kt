package com.vaticancameos.statemachine

/**
 * Vatican Cameos — State Machine
 *
 * DEFAULT  →(keyword_l1)→  IDLE
 * IDLE     →(keyword_l2)→  STANDBY
 * IDLE     →(deactivate / timeout / hw_interrupt)→  DEFAULT
 * STANDBY  →(keyword_l3)→  ACTION
 * STANDBY  →(timeout / hw_interrupt)→  DEFAULT
 * ACTION   →(complete)→  DEFAULT
 */
enum class SafewordState {
    DEFAULT,    // always-on, low-res feature extractor, smallest model
    IDLE,       // mid-res feature extractor, mid model, vibrate feedback
    STANDBY,    // high-res feature extractor, large model
    ACTION      // dispatch emergency
}

enum class StateTransition {
    KEYWORD_L1_DETECTED,
    KEYWORD_L2_DETECTED,
    KEYWORD_L3_DETECTED,
    DEACTIVATE_VOICE,
    HW_INTERRUPT,
    TIMEOUT,
    ACTION_COMPLETE
}

data class SafewordConfig(
    val keywordL1: String = "stomping",
    val keywordL2: String = "gosh",
    val keywordL3: String = "vatican cameos",
    val deactivateL2: String = "clapping",

    // Timeouts
    val idleTimeoutMs: Long = 30_000L,      // 30s idle → back to DEFAULT
    val standbyTimeoutMs: Long = 15_000L,   // 15s standby → back to DEFAULT

    // Audio feature extractor config per layer
    val defaultSampleRate: Int = 8_000,     // low-res: 8kHz
    val idleSampleRate: Int = 16_000,       // mid-res: 16kHz
    val standbySampleRate: Int = 44_100,    // high-res: 44.1kHz

    val defaultMelBins: Int = 20,
    val idleMelBins: Int = 40,
    val standbyMelBins: Int = 80
)
