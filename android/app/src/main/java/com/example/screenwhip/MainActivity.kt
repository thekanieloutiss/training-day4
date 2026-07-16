package com.example.screenwhip

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnStream: Button
    private lateinit var btnControl: Button

    private var mediaProjectionCode: Int = -1
    private var mediaProjectionData: Intent? = null

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra("state") ?: return
            runOnUiThread {
                statusText.text = state
                when (state) {
                    "connecting" -> statusText.setTextColor(0xFFFFA000.toInt())
                    "live" -> statusText.setTextColor(0xFF4CAF50.toInt())
                    "error" -> statusText.setTextColor(0xFFF44336.toInt())
                }
            }
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            mediaProjectionCode = result.resultCode
            mediaProjectionData = result.data
            startScreenCaptureService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        btnStream = findViewById(R.id.btnStream)
        btnControl = findViewById(R.id.btnControl)

        registerReceiver(stateReceiver, IntentFilter("com.example.screenwhip.ACTION_STATE"),
            ContextCompat.RECEIVER_NOT_EXPORTED)

        btnStream.setOnClickListener {
            if (mediaProjectionData == null) {
                requestScreenCapture()
            } else {
                stopScreenCaptureService()
            }
        }

        btnControl.setOnClickListener {
            if (isAccessibilityEnabled()) {
                showControlInfo()
            } else {
                promptEnableAccessibility()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
    }

    private fun requestScreenCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val config = MediaProjectionConfig.createConfigForDefaultDisplay()
            mpm.createScreenCaptureIntent(config)
        } else {
            mpm.createScreenCaptureIntent()
        }
        mediaProjectionLauncher.launch(intent)
    }

    private fun startScreenCaptureService() {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra("code", mediaProjectionCode)
            putExtra("data", mediaProjectionData)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        btnStream.text = getString(R.string.stop_stream)
    }

    private fun stopScreenCaptureService() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        mediaProjectionCode = -1
        mediaProjectionData = null
        btnStream.text = getString(R.string.start_stream)
        statusText.text = ""
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        return enabledServices.contains(packageName)
    }

    private fun promptEnableAccessibility() {
        AlertDialog.Builder(this)
            .setTitle("Aksesibilitas")
            .setMessage("Aktifkan GabutPoC di pengaturan Accessibility untuk remote control.")
            .setPositiveButton("Buka Pengaturan") { _, _ ->
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        putExtra(":settings:fragment_args_key", packageName)
                    }
                }
                startActivity(intent)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showControlInfo() {
        AlertDialog.Builder(this)
            .setTitle("Full Control Aktif")
            .setMessage("Remote control siap digunakan.")
            .setPositiveButton("OK", null)
            .show()
    }
}
