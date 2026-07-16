package org.webrtc

import android.media.MediaCodecList

class ForcedH264EncoderFactory : VideoEncoderFactory {

    private val hardwareFactory = HardwareVideoEncoderFactory(
        EglBase.create().eglBaseContext, false, false
    )
    private val softwareFactory = SoftwareVideoEncoderFactory()

    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? {
        if (info.name.equals("H264", ignoreCase = true)) {
            val hw = hardwareFactory.createEncoder(info)
            if (hw != null) return hw
            return findSoftwareH264Encoder()
        }
        return hardwareFactory.createEncoder(info)
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        val h264 = VideoCodecInfo(0, "H264", mapOf("profile-level-id" to "42e01f"))
        return arrayOf(h264) + hardwareFactory.getSupportedCodecs()
    }

    private fun findSoftwareH264Encoder(): VideoEncoder? {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (codec in codecList.codecInfos) {
            if (!codec.isEncoder) continue
            val name = codec.name.lowercase()
            if ((name.contains("h264") || name.contains("avc")) &&
                (name.contains("google") || name.contains("c2.android.avc"))) {
                val info = VideoCodecInfo(0, "H264", mapOf(
                    "profile-level-id" to "42e01f",
                    "key-frame-interval-ms" to "2000"
                ))
                return softwareFactory.createEncoder(info)
            }
        }
        return null
    }
}
