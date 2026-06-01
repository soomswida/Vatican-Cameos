package com.vaticancameos.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.vaticancameos.audio.AudioPipeline
import com.vaticancameos.emergency.EmergencyDispatcher
import com.vaticancameos.emergency.EmergencyDispatcher.DispatchConfig
import com.vaticancameos.emergency.EmergencyDispatcher.EmergencyContact
import com.vaticancameos.model.KeywordClassifier
import com.vaticancameos.statemachine.SafewordConfig
import com.vaticancameos.statemachine.SafewordState
import com.vaticancameos.statemachine.SafewordStateMachine
import com.vaticancameos.statemachine.StateTransition

class SafewordService : Service() {

    private val TAG = "SafewordService"
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "vatican_cameos_channel"

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Core components ─────────────────────────────────────
    private lateinit var stateMachine:  SafewordStateMachine
    private lateinit var audioPipeline: AudioPipeline
    private lateinit var classifier:    KeywordClassifier
    private lateinit var dispatcher:    EmergencyDispatcher

    // ── Emergency dispatch configuration ────────────────────
    private val dispatchConfig = DispatchConfig(
        institutionNumber = "119",
        contacts = listOf(
            EmergencyContact("Emergency Contact 1", "010-0000-0000")
        ),
        preDesignatedMessage = "[Vatican Cameos] Emergency detected."
    )

    // ── Vibrator (IDLE state feedback) ──────────────────────
    private val vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else
            @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
    }

    // ════════════════════════════════════════════════════════
    //  Service lifecycle
    // ════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")

        val config = SafewordConfig()

        stateMachine  = SafewordStateMachine(config, serviceScope)
        audioPipeline = AudioPipeline(config, serviceScope)
        classifier    = KeywordClassifier(this, config)
        dispatcher    = EmergencyDispatcher(this)

        setupCallbacks()
        classifier.loadAll()

        _isRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START          -> startListening()
            ACTION_STOP           -> stopSelf()
            ACTION_HW_INTERRUPT   -> stateMachine.dispatch(StateTransition.HW_INTERRUPT)

            // for the sake of demonstration
            ACTION_SIM_KEYWORD_L1 -> stateMachine.dispatch(StateTransition.KEYWORD_L1_DETECTED)
            ACTION_SIM_KEYWORD_L2 -> stateMachine.dispatch(StateTransition.KEYWORD_L2_DETECTED)
            ACTION_SIM_KEYWORD_L3 -> stateMachine.dispatch(StateTransition.KEYWORD_L3_DETECTED)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")

        _isRunning.value = false
        _currentState.value = SafewordState.DEFAULT

        audioPipeline.stop()
        classifier.release()
        stateMachine.destroy()
        serviceScope.cancel()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ════════════════════════════════════════════════════════
    //  Start listening
    // ════════════════════════════════════════════════════════

    private fun startListening() {
        startForeground(NOTIFICATION_ID, buildNotification("Listening..."))
        audioPipeline.start()
        launchMainLoop()
        Log.i(TAG, "Listening started")
    }

    // ════════════════════════════════════════════════════════
    //  Main inference loop
    // ════════════════════════════════════════════════════════

    private fun launchMainLoop() {
        serviceScope.launch {
            audioPipeline.frames.collectLatest { frame ->
                val currentState = stateMachine.state.value

                if (currentState == SafewordState.ACTION) return@collectLatest

                val result = classifier.classify(
                    frame.melSpectrogram,
                    frame.melBins,
                    frame.timeSteps,
                    frame.state
                )

                if (!result.isKeyword) return@collectLatest

                val config = stateMachine.currentConfig()
                val transition = when {
                    currentState == SafewordState.DEFAULT &&
                            result.label == config.keywordL1 ->
                        StateTransition.KEYWORD_L1_DETECTED

                    currentState == SafewordState.IDLE &&
                            result.label == config.deactivateL2 ->
                        StateTransition.DEACTIVATE_VOICE

                    currentState == SafewordState.IDLE &&
                            result.label == config.keywordL2 ->
                        StateTransition.KEYWORD_L2_DETECTED

                    currentState == SafewordState.STANDBY &&
                            result.label == config.keywordL3 ->
                        StateTransition.KEYWORD_L3_DETECTED

                    else -> null
                }

                transition?.let { stateMachine.dispatch(it) }
            }
        }
    }

    // ════════════════════════════════════════════════════════
    //  State change callbacks
    // ════════════════════════════════════════════════════════

    private fun setupCallbacks() {
        stateMachine.onStateChanged = { newState ->
            // ★ UI에 상태 전파
            _currentState.value = newState

            // 1. 오디오 파이프라인 전환
            audioPipeline.switchToState(newState)

            // 2. 상태별 사이드 이펙트
            when (newState) {
                SafewordState.DEFAULT -> updateNotification("Listening...")
                SafewordState.IDLE -> {
                    updateNotification("Standby — say keyword to proceed")
                    vibrateIdle()
                }
                SafewordState.STANDBY -> {
                    updateNotification("Ready — say final keyword")
                    vibrateSB()
                }
                SafewordState.ACTION  -> {
                    vibrateAction()
                }
            }
        }

        stateMachine.onActionTriggered = {
            Log.i(TAG, "ACTION triggered — dispatching emergency")
            updateNotification("Emergency dispatched")
            dispatcher.dispatch(dispatchConfig)

            // Back to DEFAULT
            serviceScope.launch {
                delay(5_000L)
                stateMachine.dispatch(StateTransition.ACTION_COMPLETE)
            }
        }
    }

    // ════════════════════════════════════════════════════════
    //  Vibration feedback
    // ════════════════════════════════════════════════════════

    private fun vibrateIdle() {
        val pattern = longArrayOf(0, 200, 100, 200)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION") vibrator.vibrate(pattern, -1)
        }
    }

    private fun vibrateSB() {
        val pattern = longArrayOf(0, 100, 100, 200, 200)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION") vibrator.vibrate(pattern, -1)
        }
    }

    private fun vibrateAction() {
        val pattern = longArrayOf(0, 200, 200, 200, 200)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION") vibrator.vibrate(pattern, -1)
        }
    }

    // ════════════════════════════════════════════════════════
    //  Notification
    // ════════════════════════════════════════════════════════

    private fun buildNotification(text: String): Notification {
        createNotificationChannel()
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, SafewordService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vatican Cameos")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Vatican Cameos",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Safeword detection service" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    // ════════════════════════════════════════════════════════
    //  Static — Actions + UI-observable state
    // ════════════════════════════════════════════════════════

    companion object {
        // ── Intent actions ──────────────────────────────────
        const val ACTION_START          = "school.snu.project.vatican_cameos.START"
        const val ACTION_STOP           = "school.snu.project.vatican_cameos.STOP"
        const val ACTION_HW_INTERRUPT   = "school.snu.project.vatican_cameos.HW_INTERRUPT"

        // For simulation
        const val ACTION_SIM_KEYWORD_L1 = "school.snu.project.vatican_cameos.SIM_L1"
        const val ACTION_SIM_KEYWORD_L2 = "school.snu.project.vatican_cameos.SIM_L2"
        const val ACTION_SIM_KEYWORD_L3 = "school.snu.project.vatican_cameos.SIM_L3"

        // ── UI-observable state ─────────────────────────────
        private val _currentState = MutableStateFlow(SafewordState.DEFAULT)
        val currentState: StateFlow<SafewordState> = _currentState.asStateFlow()

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    }
}