package com.example.screenwhip

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal WHIP (WebRTC-HTTP Ingestion Protocol) client.
 *
 * Publish flow:
 *   1. POST the local SDP offer (Content-Type: application/sdp) to the WHIP URL.
 *   2. The server (SRS) replies with the SDP answer in the body.
 *   3. Optionally it returns a Location header pointing at the session resource,
 *      which we DELETE on teardown.
 */
class WhipClient(private val whipUrl: String) {

    private val sdpMediaType = "application/sdp".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** The absolute URL of the created resource (for DELETE), if the server provided one. */
    @Volatile
    var resourceUrl: String? = null
        private set

    /** Sends the offer and returns the SDP answer. Blocking — call off the main thread. */
    @Throws(IOException::class)
    fun publish(offerSdp: String): String {
        val request = Request.Builder()
            .url(whipUrl)
            .post(offerSdp.toRequestBody(sdpMediaType))
            .header("Content-Type", "application/sdp")
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("WHIP failed: HTTP ${response.code} — $body")
            }
            // Resolve the (possibly relative) Location header against the request URL.
            response.header("Location")?.let { loc ->
                resourceUrl = response.request.url.resolve(loc)?.toString()
            }
            if (body.isBlank()) throw IOException("WHIP returned an empty SDP answer")
            return body
        }
    }

    /** Best-effort teardown of the server-side session. */
    fun delete() {
        val url = resourceUrl ?: return
        runCatching {
            val request = Request.Builder().url(url).delete().build()
            http.newCall(request).execute().close()
        }
    }
}
