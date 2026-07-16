package com.example.screenwhip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Launches the app on device boot so casting resumes unattended.
 *
 * Note: starting an Activity from BOOT_COMPLETED is restricted on Android 10+
 * for ordinary apps — it works reliably for a device-owner / kiosk-provisioned
 * device. Combined with the PROJECT_MEDIA appop (see MainActivity), the device
 * boots straight into a live cast with zero interaction.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            val launch = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(launch) }
        }
    }
}
