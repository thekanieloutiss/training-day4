package com.example.screenwhip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.ForcedH264EncoderFactory
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "screen_whip"
        private const val NOTIFICATION_ID = 1001

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_WHIP_URL = "whip_url"
        const val ACTION_STOP = "com.example.screenwhip.STOP"

        const val ACTION_STATE = "com.example.screenwhip.STATE"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"

        private const val TARGET_FPS = 24
        private const val MAX_DIMENSION = 1280
        private const val ICE_GATHER_TIMEOUT_MS = 2500L
        private const val MAX_WHIP_RETRIES = 6
        private const val RETRY_DELAY_MS = 2000L

        private const val RECONNECT_DELAY_MS = 3000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val eglBase: EglBase by lazy { EglBase.create() }

    private var factory: PeerConnectionFactory? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var peerConnection: PeerConnection? = null
    private var whipClient: WhipClient? = null

    @Volatile private var manualStop = false
    @Volatile private var reconnecting = false
    @Volatile private var pcGeneration = 0
    @Volatile private var offerSent = false

    private var whipUrl: String? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            manualStop = true
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val url = intent?.getStringExtra(EXTRA_WHIP_URL)

        if (resultData == null || url.isNullOrBlank()) {
            Log.e(TAG, "Missing projection data or WHIP URL")
            stopSelf()
            return START_NOT_STICKY
        }

        whipUrl = url
        startAsForeground()
        acquireLocks()
        broadcastState("connecting", "Menyiapkan capture layar…")
        startPublishing(resultData, url)
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Screen streaming", NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val piFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openPending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            piFlags
        )
        val stopPending = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_STOP },
            piFlags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Menyiarkan layar")
            .setContentText("Layar dikirim ke server WebRTC")
            .setSmallIcon(R.drawable.ic_screencast)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(R.drawable.ic_screencast, "Hentikan", stopPending)
            .build()
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "GabutPoC::ScreenCapture"
            ).apply { acquire() }
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiLock == null && wm != null) {
            wifiLock = wm.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "GabutPoC::Wifi"
            ).apply { acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
    }

    private fun startPublishing(projectionData: Intent, whipUrl: String) {
        if (factory == null) initFactory()

        val (width, height) = screenSize()
        Log.i(TAG, "Capturing at ${width}x$height @ ${TARGET_FPS}fps")

        if (screenCapturer == null) {
            val capturer = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped by system/user")
                    manualStop = true
                    stopSelf()
                }
            })
            screenCapturer = capturer

            val source = factory!!.createVideoSource(true)
            videoSource = source
            val helper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase.eglBaseContext)
            surfaceTextureHelper = helper
            capturer.initialize(helper, applicationContext, source.capturerObserver)
            capturer.startCapture(width, height, TARGET_FPS)

            val track = factory!!.createVideoTrack("screen_video", source)
            videoTrack = track
        }

        createPeerConnectionAndOffer(videoTrack!!, whipUrl)
    }

    private fun initFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .createInitializationOptions()
        )
        val encoderFactory = ForcedH264EncoderFactory()
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    private fun createPeerConnectionAndOffer(track: VideoTrack, whipUrl: String) {
        pcGeneration++
        offerSent = false
        val thisGeneration = pcGeneration

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val pc = factory!!.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.d(TAG, "ICE gathering: $state (gen $thisGeneration)")
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    sendOffer(whipUrl, thisGeneration)
                }
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "PC state: $newState (gen $thisGeneration)")
                if (thisGeneration != pcGeneration) return
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        reconnecting = false
                        broadcastState("live", "LIVE — stream terkirim ke server")
                    }
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.DISCONNECTED -> {
                        if (!manualStop && !reconnecting) {
                            scheduleReconnect()
                        }
                    }
                    PeerConnection.PeerConnectionState.CLOSED -> {}
                    else -> {}
                }
            }
            override fun onIceCandidate(candidate: IceCandidate?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        }) ?: run {
            broadcastState("error", "Gagal membuat PeerConnection")
            stopSelf()
            return
        }
        peerConnection = pc

        pc.addTrack(track, listOf("screen_stream"))
        pc.transceivers
            .firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
            ?.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY

        val mediaConstraints = MediaConstraints()
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                if (thisGeneration != pcGeneration) return
                pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        if (thisGeneration != pcGeneration) return
                        scope.launch {
                            delay(ICE_GATHER_TIMEOUT_MS)
                            sendOffer(whipUrl, thisGeneration)
                        }
                    }
                }, sdp)
            }
            override fun onCreateFailure(error: String?) {
                broadcastState("error", "createOffer gagal: $error")
                stopSelf()
            }
        }, mediaConstraints)
    }

    private fun scheduleReconnect() {
        if (manualStop || reconnecting) return
        reconnecting = true
        broadcastState("connecting", "Koneksi terputus — menyambung ulang…")
        Log.i(TAG, "Scheduling reconnect in ${RECONNECT_DELAY_MS}ms")

        scope.launch {
            delay(RECONNECT_DELAY_MS)
            doReconnect()
        }
    }

    private fun doReconnect() {
        if (manualStop) return
        val url = whipUrl ?: return
        val track = videoTrack ?: return

        Log.i(TAG, "Reconnecting…")

        runCatching { whipClient?.delete() }
        whipClient = null

        runCatching { peerConnection?.dispose() }
        peerConnection = null

        reconnecting = true
        createPeerConnectionAndOffer(track, url)
    }

    private fun sendOffer(whipUrl: String, generation: Int) {
        if (generation != pcGeneration) return
        if (offerSent) return
        offerSent = true
        val localSdp = peerConnection?.localDescription?.description ?: return

        scope.launch(Dispatchers.IO) {
            val client = WhipClient(whipUrl)
            whipClient = client
            var lastError: Exception? = null
            for (attempt in 1..MAX_WHIP_RETRIES) {
                try {
                    val answerSdp = client.publish(localSdp)
                    val answer = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
                    peerConnection?.setRemoteDescription(SimpleSdpObserver(), answer)
                    Log.i(TAG, "Remote answer applied (gen $generation, attempt $attempt)")
                    return@launch
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "WHIP publish attempt $attempt/$MAX_WHIP_RETRIES failed: ${e.message}")
                    if (attempt < MAX_WHIP_RETRIES) {
                        broadcastState("connecting", "Menyambung ulang… ($attempt/$MAX_WHIP_RETRIES)")
                        delay(RETRY_DELAY_MS)
                    }
                }
            }
            Log.e(TAG, "WHIP publish failed after $MAX_WHIP_RETRIES attempts", lastError)
            broadcastState("error", "Gagal menyambung ke server: ${lastError?.message}")
            scheduleReconnect()
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        var w = metrics.widthPixels
        var h = metrics.heightPixels
        val longEdge = maxOf(w, h)
        if (longEdge > MAX_DIMENSION) {
            val scale = MAX_DIMENSION.toFloat() / longEdge
            w = (w * scale).toInt()
            h = (h * scale).toInt()
        }
        return Pair(w - (w % 2), h - (h % 2))
    }

    private fun broadcastState(state: String, message: String) {
        sendBroadcast(Intent(ACTION_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_MESSAGE, message)
        })
    }

    override fun onDestroy() {
        Log.i(TAG, "Tearing down")
        val deliberate = manualStop
        manualStop = true
        reconnecting = true
        runCatching { whipClient?.delete() }
        runCatching { screenCapturer?.stopCapture() }
        screenCapturer?.dispose()
        videoTrack?.dispose()
        videoSource?.dispose()
        surfaceTextureHelper?.dispose()
        peerConnection?.dispose()
        factory?.dispose()
        eglBase.release()
        releaseLocks()
        scope.cancel()
        broadcastState(if (deliberate) "stopped" else "error", "Streaming berhenti")
        super.onDestroy()
    }
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
