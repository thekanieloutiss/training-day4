package com.example.screenwhip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

class ScreenCaptureService : Service() {

    companion object {
        const val TAG = "ScreenCapture"
        const val TARGET_FPS = 24
        const val MAX_DIMENSION = 1280
        const val ICE_GATHER_TIMEOUT_MS = 2500L
        const val MAX_WHIP_RETRIES = 6
        const val RETRY_DELAY_MS = 2000L
        const val ACTION_STATE = "com.example.screenwhip.ACTION_STATE"
        const val EXTRA_STATE = "state"
    }

    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionIntent: Intent? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var eglBase: EglBase? = null
    private var whipClient: WhipClient? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var manualStop = false

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            manualStop = true
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerReceiver(stopReceiver, IntentFilter("com.example.screenwhip.ACTION_STOP"),
            Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        manualStop = false
        Log.d(TAG, "onStartCommand")

        val code = intent?.getIntExtra("code", -1) ?: -1
        val data = intent?.getParcelableExtra("data", Intent::class.java)
        if (code == -1 || data == null) {
            Log.e(TAG, "Invalid intent data: code=$code data=$data")
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        startForeground(1, notification)

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(code, data)
        mediaProjectionIntent = data
        Log.d(TAG, "MediaProjection obtained")
        startStream()
        return START_NOT_STICKY
    }

    private fun startStream() {
        broadcastState("connecting")
        Log.d(TAG, "startStream: initializing WebRTC")

        eglBase = EglBase.create()
        Log.d(TAG, "EGL created")

        val factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase?.eglBaseContext))
            .setVideoEncoderFactory(ForcedH264EncoderFactory())
            .setAudioDeviceModule(JavaAudioDeviceModule.builder(this).createAudioDeviceModule())
            .createPeerConnectionFactory()

        peerConnectionFactory = factory
        Log.d(TAG, "PeerConnectionFactory created")

        val surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext)
        surfaceTextureHelper = surfaceHelper

        val source = factory.createVideoSource(false)
        videoSource = source

        val mpCallback = object : MediaProjection.Callback() {}
        val capturer = ScreenCapturerAndroid(mediaProjectionIntent!!, mpCallback)
        this.capturer = capturer
        capturer.initialize(surfaceHelper, this, source.capturerObserver)

        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val maxDim = MAX_DIMENSION
        val (outW, outH) = if (width > height) {
            val ratio = maxDim.toFloat() / width
            (maxDim to (height * ratio).toInt())
        } else {
            val ratio = maxDim.toFloat() / height
            ((width * ratio).toInt() to maxDim)
        }
        val adjW = if (outW % 2 == 0) outW else outW + 1
        val adjH = if (outH % 2 == 0) outH else outH + 1
        capturer.startCapture(adjW, adjH, TARGET_FPS)

        val track = factory.createVideoTrack("screen_track", source)
        videoTrack = track

        val server = getString(R.string.default_server)
        val deviceId = DeviceId.get(this)
        val serverUrl = "http://$server/rtc/v1/whip/?app=live&stream=$deviceId"
        Log.d(TAG, "WHIP URL: $serverUrl, DeviceId: $deviceId")
        whipClient = WhipClient(serverUrl)

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    synchronized(this@ScreenCaptureService) {
                        (this@ScreenCaptureService as Object).notifyAll()
                    }
                }
            }
            override fun onAddStream(stream: MediaStream?) {}
            override fun onAddTrack(rtpReceiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        }) ?: run {
            broadcastState("error")
            stopSelf()
            return
        }

        peerConnection = pc
        pc.addTrack(track, listOf("stream1"))

        val constraints = MediaConstraints()
        val sdpObserver = object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(d: SessionDescription?) {}
                    override fun onSetSuccess() {
                        synchronized(this@ScreenCaptureService) {
                            (this@ScreenCaptureService as Object).wait(ICE_GATHER_TIMEOUT_MS)
                        }
                        val localSdp = pc.localDescription ?: return
                        doWhipPublish(localSdp.description)
                    }
                    override fun onCreateFailure(msg: String?) {}
                    override fun onSetFailure(msg: String?) {}
                }, desc)
            }

            override fun onSetSuccess() {}

            override fun onCreateFailure(msg: String?) {
                Log.e(TAG, "createOffer failed: $msg")
                broadcastState("error")
            }

            override fun onSetFailure(msg: String?) {
                Log.e(TAG, "setLocalDescription failed: $msg")
                broadcastState("error")
            }
        }

        Log.d(TAG, "Creating offer...")
        pc.createOffer(sdpObserver, constraints)
    }

    private fun doWhipPublish(offerSdp: String) {
        var retries = 0
        while (retries < MAX_WHIP_RETRIES) {
            Log.d(TAG, "WHIP publish attempt ${retries + 1}/$MAX_WHIP_RETRIES")
            val answer = whipClient?.publish(offerSdp)
            if (answer != null) {
                Log.d(TAG, "WHIP publish success, answer: $answer")
                broadcastState("live")
                return
            }
            Log.e(TAG, "WHIP publish attempt ${retries + 1} failed")
            retries++
            if (retries < MAX_WHIP_RETRIES) {
                try { Thread.sleep(RETRY_DELAY_MS) } catch (_: InterruptedException) { break }
            }
        }
        Log.e(TAG, "WHIP publish failed after $MAX_WHIP_RETRIES retries")
        broadcastState("error")
    }

    private fun broadcastState(state: String) {
        Log.d(TAG, "State: $state")
        sendBroadcast(Intent(ACTION_STATE).putExtra(EXTRA_STATE, state))
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
        whipClient?.delete()
        peerConnection?.close()
        capturer?.stopCapture()
        capturer?.dispose()
        surfaceTextureHelper?.dispose()
        videoSource?.dispose()
        peerConnectionFactory?.dispose()
        eglBase?.release()
        if (!manualStop) {
            broadcastState("disconnected")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "stream_channel",
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getBroadcast(
            this, 0, Intent("com.example.screenwhip.ACTION_STOP"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, "stream_channel")
            .setContentTitle("GabutPoC")
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.stop_action), stopIntent)
            .setOngoing(true)
            .build()
    }
}
