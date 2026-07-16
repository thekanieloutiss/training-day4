package com.example.screenwhip

import android.content.Context
import android.provider.Settings
import android.os.Build

object DeviceId {

    private const val EMULATOR_SUFFIX = "emulator"

    fun get(context: Context): String {
        val isEmulator = isEmulator()
        val prefix = if (isEmulator) EMULATOR_SUFFIX else Build.MODEL
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "0000"
        val suffix = androidId.replace("-", "").takeLast(4)
            .filter { it.isLetterOrDigit() }
            .padStart(4, '0')
        val raw = "$prefix-$suffix"
        return raw.lowercase()
            .map { if (it.isLetterOrDigit() || it == '-') it else '-' }
            .joinToString("")
            .trimEnd('-')
    }

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.startsWith("unknown") ||
                Build.MODEL.contains("google_sdk") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK built for x86") ||
                Build.MANUFACTURER.contains("Genymotion") ||
                Build.PRODUCT.contains("sdk") ||
                Build.PRODUCT.contains("vbox86p") ||
                Build.HARDWARE.contains("ranchu") ||
                Build.HARDWARE.contains("vbox86")
    }
}
