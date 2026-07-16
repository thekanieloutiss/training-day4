package com.example.screenwhip

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import okhttp3.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class KioskControlService : AccessibilityService() {

    private val executor = Executors.newCachedThreadPool()
    private val handler = Handler(Looper.getMainLooper())
    private var httpServer: ServerSocket? = null
    private var relayWs: WebSocket? = null
    private var deviceId: String = ""

    override fun onServiceConnected() {
        deviceId = DeviceId.get(this)
        startHttpServer()
        startRelayWs()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    private fun startHttpServer() {
        executor.submit {
            try {
                val server = ServerSocket(8091)
                httpServer = server
                while (!server.isClosed) {
                    val socket = server.accept()
                    executor.submit { handleHttpConnection(socket) }
                }
            } catch (_: Exception) {}
        }
    }

    private fun handleHttpConnection(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val path = parts[1]
            val body = if (parts[0] == "POST") {
                var contentLength = 0
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: break
                    if (l.startsWith("Content-Length", true)) {
                        contentLength = l.split(":").getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                    }
                    if (l.isEmpty()) break
                }
                if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    reader.read(buf, 0, contentLength)
                    String(buf)
                } else ""
            } else ""
            val response = handleCommand(path, body)
            val writer = OutputStreamWriter(socket.getOutputStream())
            writer.write("HTTP/1.1 200 OK\r\nContent-Length: ${response.length}\r\n\r\n$response")
            writer.flush()
            socket.close()
        } catch (_: Exception) {}
    }

    private fun handleCommand(path: String, body: String): String {
        return when {
            path == "/ping" -> "pong"
            path.startsWith("/tap") -> {
                val params = parseQuery(path)
                val nx = params["nx"]?.toFloatOrNull() ?: 0.5f
                val ny = params["ny"]?.toFloatOrNull() ?: 0.5f
                performTap(nx, ny)
                "ok"
            }
            path.startsWith("/swipe") -> {
                val params = parseQuery(path)
                val nx1 = params["nx1"]?.toFloatOrNull() ?: 0.5f
                val ny1 = params["ny1"]?.toFloatOrNull() ?: 0.5f
                val nx2 = params["nx2"]?.toFloatOrNull() ?: 0.5f
                val ny2 = params["ny2"]?.toFloatOrNull() ?: 0.5f
                val ms = params["ms"]?.toLongOrNull() ?: 300L
                performSwipe(nx1, ny1, nx2, ny2, ms)
                "ok"
            }
            path.startsWith("/key") -> {
                val params = parseQuery(path)
                val name = params["name"] ?: "back"
                performKey(name)
                "ok"
            }
            else -> "unknown"
        }
    }

    private fun performTap(nx: Float, ny: Float) {
        val display = resources.displayMetrics
        val x = (nx * display.widthPixels).toInt()
        val y = (ny * display.heightPixels).toInt()
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun performSwipe(nx1: Float, ny1: Float, nx2: Float, ny2: Float, ms: Long) {
        val display = resources.displayMetrics
        val x1 = (nx1 * display.widthPixels).toInt()
        val y1 = (ny1 * display.heightPixels).toInt()
        val x2 = (nx2 * display.widthPixels).toInt()
        val y2 = (ny2 * display.heightPixels).toInt()
        val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun performKey(name: String) {
        val action = when (name.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return
        }
        performGlobalAction(action)
    }

    private fun parseQuery(path: String): Map<String, String> {
        val idx = path.indexOf("?")
        if (idx == -1) return emptyMap()
        return path.substring(idx + 1).split("&").mapNotNull {
            val eq = it.indexOf("=")
            if (eq == -1) null else it.substring(0, eq) to it.substring(eq + 1)
        }.toMap()
    }

    private fun startRelayWs() {
        val server = getString(R.string.default_server)
        val url = "ws://$server/?role=device&id=$deviceId"
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()

        relayWs = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                handler.post { handleRelayCommand(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handler.postDelayed({ startRelayWs() }, 3000)
            }
        })
    }

    private fun handleRelayCommand(json: String) {
        try {
            val obj = JSONObject(json)
            val action = obj.optString("action")
            when (action) {
                "tap" -> {
                    val nx = obj.optDouble("nx", 0.5).toFloat()
                    val ny = obj.optDouble("ny", 0.5).toFloat()
                    performTap(nx, ny)
                }
                "longpress" -> {
                    val nx = obj.optDouble("nx", 0.5).toFloat()
                    val ny = obj.optDouble("ny", 0.5).toFloat()
                    val display = resources.displayMetrics
                    val x = (nx * display.widthPixels).toInt()
                    val y = (ny * display.heightPixels).toInt()
                    val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                    val gesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0, 700))
                        .build()
                    dispatchGesture(gesture, null, null)
                }
                "swipe" -> {
                    val nx1 = obj.optDouble("nx1", 0.5).toFloat()
                    val ny1 = obj.optDouble("ny1", 0.5).toFloat()
                    val nx2 = obj.optDouble("nx2", 0.5).toFloat()
                    val ny2 = obj.optDouble("ny2", 0.5).toFloat()
                    val ms = obj.optLong("ms", 300)
                    performSwipe(nx1, ny1, nx2, ny2, ms)
                }
                "key" -> performKey(obj.optString("name", "back"))
                "text" -> performText(obj.optString("value", ""))
                "launch" -> performLaunch(obj.optString("pkg", ""))
            }
        } catch (_: Exception) {}
    }

    private fun performText(value: String) {
        val root = rootInActiveWindow ?: return
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            focused.recycle()
        }
        root.recycle()
    }

    private fun performLaunch(pkg: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try { httpServer?.close() } catch (_: Exception) {}
        relayWs?.close(1000, "Service destroyed")
        executor.shutdown()
    }
}
