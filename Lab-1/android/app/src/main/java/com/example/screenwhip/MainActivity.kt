package com.example.screenwhip

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.screenwhip.databinding.ActivityMainBinding

/**
 * Two menus:
 *   1. "Aktifkan Stream"       — screen-cast to the pre-set SRS server (WHIP).
 *   2. "Aktifkan Full Control" — enable the remote-control accessibility service.
 * Both use the standard Android permission flows, so the app installs and runs on
 * any device without root.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var projectionManager: MediaProjectionManager

    private var streaming = false
    private lateinit var server: String
    private lateinit var deviceId: String

    private companion object {
        val GRAY = 0xFF9CA3AF.toInt()
        val AMBER = 0xFFF59E0B.toInt()
        val GREEN = 0xFF22C55E.toInt()
        val RED = 0xFFEF4444.toInt()
    }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                startStreaming(result.resultCode, result.data!!)
            } else {
                setStreamingUi(false)
                setStatus(GRAY, getString(R.string.status_offline), "Izin rekam layar ditolak.")
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(ScreenCaptureService.EXTRA_STATE)
            val message = intent?.getStringExtra(ScreenCaptureService.EXTRA_MESSAGE).orEmpty()
            when (state) {
                "connecting" -> setStatus(AMBER, getString(R.string.status_connecting), message)
                "live" -> { setStreamingUi(true); setStatus(GREEN, getString(R.string.status_live), getString(R.string.status_live_hint)) }
                "error" -> { setStreamingUi(false); setStatus(RED, getString(R.string.status_offline), message) }
                "stopped" -> { setStreamingUi(false); setStatus(GRAY, getString(R.string.status_offline), getString(R.string.status_stopped)) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        server = getString(R.string.default_server)
        deviceId = DeviceId.get(this)
        binding.serverValue.text = server
        binding.deviceValue.text = deviceId

        binding.streamButton.setOnClickListener {
            if (streaming) stopStreaming() else startCasting()
        }
        binding.controlButton.setOnClickListener { requestFullControl() }

        // Ask for notification permission upfront (once), decoupled from the
        // capture consent so the two dialogs don't stack.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    // ---- Menu 1: stream ----

    private fun startCasting() {
        setStatus(AMBER, getString(R.string.status_connecting), "Meminta izin rekam layar…")
        projectionLauncher.launch(screenCaptureIntent())
    }

    /** Force entire-screen on Android 14+ (no "single app" option). */
    private fun screenCaptureIntent() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projectionManager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            projectionManager.createScreenCaptureIntent()
        }

    private fun startStreaming(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCaptureService.EXTRA_WHIP_URL, whipUrl())
        }
        ContextCompat.startForegroundService(this, intent)
        setStreamingUi(true)
        setStatus(AMBER, getString(R.string.status_connecting), "Menghubungkan ke server…")
    }

    private fun stopStreaming() {
        startService(Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        })
        setStreamingUi(false)
        setStatus(GRAY, getString(R.string.status_offline), getString(R.string.status_stopped))
    }

    private fun whipUrl(): String {
        val host = server.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        val stream = deviceId.replace(Regex("[^A-Za-z0-9_-]"), "-").trim('-')
        return "http://$host/rtc/v1/whip/?app=live&stream=$stream"
    }

    // ---- Menu 2: full control (accessibility) ----

    /**
     * Accessibility has NO runtime permission dialog (unlike camera/location) —
     * enabling the service in Settings IS the grant, and it can only be flipped
     * by the user (or root/MDM). So we show an explicit request dialog, then send
     * the user straight to the toggle.
     */
    private fun requestFullControl() {
        if (isAccessibilityEnabled()) {
            Toast.makeText(this, "Full Control sudah aktif.", Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Aktifkan Full Control")
            .setMessage(
                "GabutPoC memerlukan izin Aksesibilitas untuk kontrol jarak jauh " +
                    "(ketuk/geser, buka konten). Kamu akan diarahkan ke Pengaturan — " +
                    "nyalakan tombol untuk “GabutPoC”, lalu kembali."
            )
            .setPositiveButton("Buka & Aktifkan") { _, _ -> openAccessibilitySettings() }
            .setNegativeButton("Batal", null)
            .setCancelable(true)
            .show()
    }

    private fun openAccessibilitySettings() {
        Toast.makeText(this, "Nyalakan tombol untuk GabutPoC.", Toast.LENGTH_LONG).show()

        // Android has no runtime permission dialog for accessibility — enabling
        // the service IS the grant. Deep-link STRAIGHT to GabutPoC's own toggle
        // page (Android 11+) so it's a single switch, not the whole list.
        val component = ComponentName(this, KioskControlService::class.java).flattenToString()
        val opened = runCatching {
            val args = Bundle().apply { putString(":settings:fragment_args_key", component) }
            startActivity(Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                putExtra(":settings:fragment_args_key", component)
                putExtra(":settings:show_fragment_args", args)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        }.getOrDefault(false)
        // Fallback to the general list if the direct page isn't available.
        if (!opened) runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = "$packageName/${KioskControlService::class.java.name}"
        val list = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return list.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    // ---- UI ----

    private fun refreshUi() {
        val ctrlOn = isAccessibilityEnabled()
        binding.controlButton.text =
            getString(if (ctrlOn) R.string.btn_control_active else R.string.btn_enable_control)
        // Control is routed via the server relay, so show the server endpoint
        // (this is what the dashboard connects to), not the device's own IP.
        val relayHost = server.substringBefore(':')
        binding.controlValue.text =
            if (ctrlOn) "relay $relayHost:${KioskControlService.RELAY_PORT}" else "nonaktif"
        binding.controlValue.setTextColor(if (ctrlOn) GREEN else RED)
        if (!streaming) setStreamingUi(false)
    }

    private fun setStreamingUi(active: Boolean) {
        streaming = active
        binding.streamButton.text =
            getString(if (active) R.string.btn_stop_stream else R.string.btn_enable_stream)
    }

    private fun setStatus(color: Int, title: String, hint: String) {
        binding.statusCircle.backgroundTintList = ColorStateList.valueOf(color)
        binding.statusText.text = title
        binding.statusHint.text = hint
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, stateReceiver,
            IntentFilter(ScreenCaptureService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(stateReceiver) }
    }
}
