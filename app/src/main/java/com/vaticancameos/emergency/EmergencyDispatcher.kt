package com.vaticancameos.emergency

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

/**
 * Stage 3 — Emergency Dispatcher
 *
 * callTheInstitution() : 112 / 119 auto-dial (but for the sake of test, random value is used)
 * distressingSignal()  : call friends and family with location
 *
 * Required permission:
 *   CALL_PHONE, SEND_SMS,
 *   ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION
 */
class EmergencyDispatcher(private val context: Context) {

    private val TAG = "EmergencyDispatcher"

    data class EmergencyContact(
        val name: String,
        val phoneNumber: String
    )

    data class DispatchConfig(
        val institutionNumber: String = "119-119-119",       // 119 or 112
        val contacts: List<EmergencyContact> = emptyList(),
        val preDesignatedMessage: String =
            "[Vatican Cameos] Emergency detected. Location attached."
    )

    // ── Public API ──────────────────────────────────────────────

    fun dispatch(config: DispatchConfig) {
        Log.i(TAG, "Emergency dispatch initiated → ${config.institutionNumber}")
        fetchLocationThen { location ->
            callTheInstitution(config.institutionNumber)
            distressingSignal(config, location)
        }
    }

    // ── Institution Call ─────────────────────────────────────────

    private fun callTheInstitution(number: String) {
        if (!hasPermission(Manifest.permission.CALL_PHONE)) {
            Log.e(TAG, "CALL_PHONE permission missing")
            return
        }
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$number")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        Log.i(TAG, "Calling: $number")
    }

    // ── Distress SMS ──────────────────────────────────────────────

    private fun distressingSignal(config: DispatchConfig, location: Location?) {
        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            Log.e(TAG, "SEND_SMS permission missing")
            return
        }

        val locationStr = location?.let {
            "https://maps.google.com/?q=${it.latitude},${it.longitude}"
        } ?: "Location unavailable"

        val message = "${config.preDesignatedMessage}\n$locationStr"
        val smsManager = SmsManager.getDefault()

        config.contacts.forEach { contact ->
            try {
                smsManager.sendTextMessage(
                    contact.phoneNumber, null, message, null, null
                )
                Log.i(TAG, "SMS sent to ${contact.name} (${contact.phoneNumber})")
            } catch (e: Exception) {
                Log.e(TAG, "SMS failed to ${contact.name}: ${e.message}")
            }
        }
    }

    // ── Location ──────────────────────────────────────────────────

    private fun fetchLocationThen(callback: (Location?) -> Unit) {
        val hasFine   = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val hasCoarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "Location permission missing — dispatching without location")
            callback(null)
            return
        }

        val client = LocationServices.getFusedLocationProviderClient(context)
        val cts    = CancellationTokenSource()

        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { location ->
                Log.i(TAG, "Location acquired: ${location?.latitude}, ${location?.longitude}")
                callback(location)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Location failed: ${e.message}")
                callback(null)
            }
    }

    // ── Util ──────────────────────────────────────────────────────

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
}
