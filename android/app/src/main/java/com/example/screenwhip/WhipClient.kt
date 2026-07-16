package com.example.screenwhip

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class WhipClient(private val serverUrl: String) {

    private val TAG = "WhipClient"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    var resourceUrl: String? = null
        private set

    fun publish(offerSdp: String): String? {
        val body = offerSdp.toRequestBody("application/sdp".toMediaType())
        val request = Request.Builder()
            .url(serverUrl)
            .post(body)
            .addHeader("Content-Type", "application/sdp")
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP ${response.code}: ${response.message}")
                response.close()
                return null
            }
            resourceUrl = response.header("Location")
            Log.d(TAG, "Location header: $resourceUrl")
            val bodyStr = response.body?.string()
            response.close()
            bodyStr
        } catch (e: Exception) {
            Log.e(TAG, "HTTP error", e)
            null
        }
    }

    fun delete() {
        val url = resourceUrl ?: return
        try {
            val request = Request.Builder()
                .url(url)
                .delete()
                .build()
            client.newCall(request).execute().close()
        } catch (e: Exception) {
            Log.e(TAG, "DELETE error", e)
        }
    }
}
