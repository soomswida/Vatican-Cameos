package com.vaticancameos.statemachine

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SafewordStateMachine(
    private val config: SafewordConfig = SafewordConfig(),
    private val scope: CoroutineScope
) {
    private val TAG = "SafewordSM"

    private val _state = MutableStateFlow(SafewordState.DEFAULT)
    val state: StateFlow<SafewordState> = _state.asStateFlow()

    // Callbacks
    var onStateChanged: ((SafewordState) -> Unit)? = null
    var onActionTriggered: (() -> Unit)? = null

    private var timeoutJob: Job? = null

    // ── Public API ──────────────────────────────────────────────

    fun dispatch(transition: StateTransition) {
        val current = _state.value
        val next = resolve(current, transition)

        if (next == current) {
            Log.d(TAG, "Transition $transition ignored at state $current")
            return
        }

        Log.i(TAG, "[$current] --($transition)--> [$next]")
        cancelTimeout()
        _state.value = next
        onStateChanged?.invoke(next)
        onEnter(next)
    }

    fun currentConfig(): SafewordConfig = config

    // ── Transition table ─────────────────────────────────────────

    private fun resolve(state: SafewordState, t: StateTransition): SafewordState =
        when (state) {
            SafewordState.DEFAULT -> when (t) {
                StateTransition.KEYWORD_L1_DETECTED -> SafewordState.IDLE
                else -> state
            }
            SafewordState.IDLE -> when (t) {
                StateTransition.KEYWORD_L2_DETECTED  -> SafewordState.STANDBY
                StateTransition.DEACTIVATE_VOICE     -> SafewordState.DEFAULT
                StateTransition.HW_INTERRUPT         -> SafewordState.DEFAULT
                StateTransition.TIMEOUT              -> SafewordState.DEFAULT
                else -> state
            }
            SafewordState.STANDBY -> when (t) {
                StateTransition.KEYWORD_L3_DETECTED  -> SafewordState.ACTION
                StateTransition.HW_INTERRUPT         -> SafewordState.DEFAULT
                StateTransition.TIMEOUT              -> SafewordState.DEFAULT
                else -> state
            }
            SafewordState.ACTION -> when (t) {
                StateTransition.ACTION_COMPLETE -> SafewordState.DEFAULT
                else -> state
            }
        }

    // ── Entry actions per state ──────────────────────────────────

    private fun onEnter(state: SafewordState) {
        when (state) {
            SafewordState.DEFAULT -> {
                Log.d(TAG, "Entering DEFAULT: low-res pipeline active")
            }
            SafewordState.IDLE -> {
                Log.d(TAG, "Entering IDLE: mid-res pipeline, timeout=${config.idleTimeoutMs}ms")
                scheduleTimeout(config.idleTimeoutMs)
            }
            SafewordState.STANDBY -> {
                Log.d(TAG, "Entering STANDBY: high-res pipeline, timeout=${config.standbyTimeoutMs}ms")
                scheduleTimeout(config.standbyTimeoutMs)
            }
            SafewordState.ACTION -> {
                Log.i(TAG, "Entering ACTION: dispatching emergency")
                onActionTriggered?.invoke()
            }
        }
    }

    // ── Timeout management ───────────────────────────────────────

    private fun scheduleTimeout(delayMs: Long) {
        timeoutJob = scope.launch {
            delay(delayMs)
            Log.w(TAG, "Timeout in state ${_state.value} → DEFAULT")
            dispatch(StateTransition.TIMEOUT)
        }
    }

    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    fun destroy() {
        cancelTimeout()
    }
}
