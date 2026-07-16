package org.webrtc

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Log

/**
 * A VideoEncoderFactory that FORCES an H.264 encoder, including the software
 * codec `OMX.google.h264.encoder` that the stock [HardwareVideoEncoderFactory]
 * filters out.
 *
 * Why this exists: SRS only accepts H.264 for WebRTC publish, but the Android
 * emulator has no hardware H.264 encoder — its only AVC encoder is the Google
 * software codec, which WebRTC normally refuses to use. This factory bypasses
 * that filter so the emulator can still publish. It lives in package
 * `org.webrtc` to reach the library's package-private helpers.
 *
 * Uses byte-buffer (YUV) input mode (no shared EGL context) because software
 * codecs generally don't support Surface input.
 */
class ForcedH264EncoderFactory : VideoEncoderFactory {

    private companion object {
        const val TAG = "ForcedH264Factory"
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        // Constrained-baseline, level 3.1 — the most widely interoperable profile.
        val params = mapOf(
            "profile-level-id" to "42e01f",
            "level-asymmetry-allowed" to "1",
            "packetization-mode" to "1"
        )
        return arrayOf(VideoCodecInfo("H264", params, emptyList()))
    }

    override fun createEncoder(input: VideoCodecInfo): VideoEncoder? {
        if (!input.name.equals("H264", ignoreCase = true)) return null

        val type = VideoCodecMimeType.H264
        val codecInfo = findAvcEncoder() ?: run {
            Log.e(TAG, "No video/avc encoder found on this device")
            return null
        }
        val codecName = codecInfo.name
        val caps = codecInfo.getCapabilitiesForType(type.mimeType())
        val yuvColorFormat = MediaCodecUtils.selectColorFormat(MediaCodecUtils.ENCODER_COLOR_FORMATS, caps)
        if (yuvColorFormat == null) {
            Log.e(TAG, "No supported YUV color format for $codecName")
            return null
        }
        Log.i(TAG, "Forcing H264 encoder: $codecName (yuvColorFormat=$yuvColorFormat)")

        val outParams = HashMap<String, String>().apply {
            put("profile-level-id", input.params["profile-level-id"] ?: "42e01f")
            put("level-asymmetry-allowed", "1")
            put("packetization-mode", "1")
        }

        return HardwareVideoEncoder(
            MediaCodecWrapperFactoryImpl(),
            codecName,
            type,
            /* surfaceColorFormat = */ null,   // byte-buffer mode
            yuvColorFormat,
            outParams,
            // Emit periodic keyframes so a viewer joining mid-stream can start
            // decoding quickly (software codecs may ignore PLI keyframe requests).
            /* keyFrameIntervalSec = */ 2,
            /* forceKeyFrameIntervalMs = */ 2000,
            DynamicBitrateAdjuster(),
            /* sharedContext = */ null
        )
    }

    private fun findAvcEncoder(): MediaCodecInfo? {
        val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        // Prefer a hardware encoder if present; fall back to any (software) AVC encoder.
        var fallback: MediaCodecInfo? = null
        for (info in codecs) {
            if (!info.isEncoder) continue
            if (info.supportedTypes.none { it.equals("video/avc", ignoreCase = true) }) continue
            val name = info.name
            val isSoftware = name.startsWith("OMX.google.") || name.startsWith("c2.android.")
            if (!isSoftware) return info
            if (fallback == null) fallback = info
        }
        return fallback
    }
}
