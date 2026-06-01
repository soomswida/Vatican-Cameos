package com.vaticancameos

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vaticancameos.service.SafewordService
import com.vaticancameos.statemachine.SafewordState

class MainActivity : ComponentActivity() {

    private val requiredPermissions: Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.VIBRATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /*ignore*/ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestMissingPermissions()

        setContent {
            MaterialTheme {
                MainScreen(
                    onToggleService = { running ->
                        if (running) stopSafewordService() else startSafewordService()
                    },
                    onHwInterrupt = { sendAction(SafewordService.ACTION_HW_INTERRUPT) },
                    onSimL1       = { sendAction(SafewordService.ACTION_SIM_KEYWORD_L1) },
                    onSimL2       = { sendAction(SafewordService.ACTION_SIM_KEYWORD_L2) },
                    onSimL3       = { sendAction(SafewordService.ACTION_SIM_KEYWORD_L3) },
                )
            }
        }
    }

    // ── Permission handling ─────────────────────────────────

    private fun requestMissingPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ── Service control ─────────────────────────────────────

    private fun startSafewordService() {
        val intent = Intent(this, SafewordService::class.java).apply {
            action = SafewordService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopSafewordService() {
        sendAction(SafewordService.ACTION_STOP)
    }

    private fun sendAction(actionName: String) {
        val intent = Intent(this, SafewordService::class.java).apply {
            action = actionName
        }
        startService(intent)
    }
}

// ════════════════════════════════════════════════════════════════
// Composable UI
// ════════════════════════════════════════════════════════════════

@Composable
fun MainScreen(
    onToggleService: (currentlyRunning: Boolean) -> Unit,
    onHwInterrupt:   () -> Unit,
    onSimL1:         () -> Unit,
    onSimL2:         () -> Unit,
    onSimL3:         () -> Unit,
) {
    val state     by SafewordService.currentState.collectAsState()
    val isRunning by SafewordService.isRunning.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── (1) Header ─────────────────────────────────────
        HeaderSection()

        // ── (2) Current state display ──────────────────────
        StateDisplay(state)

        // ── (3) STOP button (HW interrupt) ─────────────────
        Button(
            onClick = onHwInterrupt,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
        ) {
            Text("STOP", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        // ── (4) Keyword simulation buttons ─────────────────
        Text(
            text = "Simulate Keyword Detection",
            fontSize = 13.sp,
            color = Color.Gray,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SimButton(label = "1", onClick = onSimL1, modifier = Modifier.weight(1f))
            SimButton(label = "2", onClick = onSimL2, modifier = Modifier.weight(1f))
            SimButton(label = "3", onClick = onSimL3, modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.weight(1f))

        // ── (5) Service start/stop toggle ──────────────────
        Button(
            onClick = { onToggleService(isRunning) },
            modifier = Modifier.fillMaxWidth().height(60.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) Color(0xFF424242) else Color(0xFF1B5E20)
            ),
        ) {
            Text(
                text = if (isRunning) "STOP SERVICE" else "START SERVICE",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun HeaderSection() {
    Column {
        Text(
            text = "Project Vatican Cameos",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Robust voice-triggered emergency detection system.\n" +
                    "On-device keyword spotting → automatic dispatch.",
            fontSize = 13.sp,
            color = Color.Gray,
        )
    }
}

@Composable
private fun StateDisplay(state: SafewordState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorForState(state)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "CURRENT STATE",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.8f),
                letterSpacing = 2.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.name,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = descriptionFor(state),
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun SimButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
    ) {
        Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

// ── State visualization helpers ─────────────────────────────

private fun colorForState(state: SafewordState): Color = when (state) {
    SafewordState.DEFAULT -> Color(0xFF455A64)  // blue-grey
    SafewordState.IDLE    -> Color(0xFFF9A825)  // amber
    SafewordState.STANDBY -> Color(0xFFEF6C00)  // orange
    SafewordState.ACTION  -> Color(0xFFC62828)  // red
}

private fun descriptionFor(state: SafewordState): String = when (state) {
    SafewordState.DEFAULT -> "Listening for trigger keyword"
    SafewordState.IDLE    -> "Awaiting second keyword"
    SafewordState.STANDBY -> "Ready — say final keyword"
    SafewordState.ACTION  -> "Emergency dispatched"
}
