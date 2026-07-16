package com.example.screenwhip

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Derives a stable, URL/stream-safe device identifier used as the SRS stream
 * name so multiple devices can publish concurrently and be told apart on the
 * dashboard. Example: "pixel-7-9f3a", or "emulator-1a2b".
 */
object DeviceId {

    fun get(context: Context): String {
        val base = if (isEmulator()) "emulator" else Build.MODEL.ifBlank { "android" }
        val slug = base.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "android" }
        val suffix = androidIdSuffix(context)
        return if (suffix.isNotEmpty()) "$slug-$suffix" else slug
    }

    private fun androidIdSuffix(context: Context): String {
        val id = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        return id.filter { it.isLetterOrDigit() }.takeLast(4)
    }

    private fun isEmulator(): Boolean {
        val fp = Build.FINGERPRINT
        return fp.startsWith("generic") || fp.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true)
    }
}
