package com.example.screenwhip

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Accessibility service that gives an operator full remote control of the kiosk
 * so its on-screen content can be driven/updated from another machine. It exposes
 * a tiny HTTP control API (LAN) whose commands are executed as accessibility
 * gestures / global actions:
 *
 *   GET/POST /ping
 *            /tap?x=&y=
 *            /swipe?x1=&y1=&x2=&y2=&ms=
 *            /key?name=back|home|recents|notifications
 *            /text?value=...            (into the focused input field)
 *            /launch?pkg=...            (open another app)
 *            /open?url=...              (open a URL — update displayed content)
 *
 * SECURITY: TOKEN below is empty by default (open on the LAN, matching the
 * local-demo setup). Set it to a secret and send it as the `X-Token` header for
 * any real deployment; also bind the kiosk to a trusted network.
 *
 * Enabling an accessibility service requires the user, adb, or a device-owner —
 * an app cannot enable itself. See provision.sh.
 */
class KioskControlService : AccessibilityService() {

    companion object {
        private const val TAG = "KioskControl"
        const val PORT = 8091
        const val RELAY_PORT = 8092
        private const val TOKEN = ""   // set a secret for production
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var server: ServerSocket? = null

    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    @Volatile private var relay: WebSocket? = null
    @Volatile private var relayClosed = false

    override fun onServiceConnected() {
        startHttpServer()          // direct LAN control (optional)
        startRelay()               // control via the server relay (dashboard)
        Log.i(TAG, "Kiosk control connected — local HTTP :$PORT, relay :$RELAY_PORT")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* control-only */ }
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        stopHttpServer()
        stopRelay()
        return super.onUnbind(intent)
    }

    // ---- Control via server relay (dashboard -> server -> this device) ----

    private fun startRelay() {
        val host = getString(R.string.default_server).substringBefore(':').trim()
        val id = DeviceId.get(this)
        val url = "ws://$host:$RELAY_PORT/?role=device&id=$id"
        relay = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "relay connected as $id")
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handleRelay(text)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = scheduleReconnect()
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = scheduleReconnect()
        })
    }

    private fun scheduleReconnect() {
        if (relayClosed) return
        main.postDelayed({ if (!relayClosed) startRelay() }, 3000)
    }

    private fun stopRelay() {
        relayClosed = true
        runCatching { relay?.close(1000, null) }
        relay = null
    }

    private fun handleRelay(text: String) {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return
        // Dashboard sends NORMALIZED coords (0..1); scale to the REAL display
        // (full physical size incl. system bars) so gesture coords are accurate.
        val (dw, dh) = displaySize()
        when (o.optString("action")) {
            "tap" -> tap((o.optDouble("nx") * dw).toFloat(), (o.optDouble("ny") * dh).toFloat())
            "longpress" -> longpress((o.optDouble("nx") * dw).toFloat(), (o.optDouble("ny") * dh).toFloat())
            "swipe" -> swipe(
                (o.optDouble("nx1") * dw).toFloat(), (o.optDouble("ny1") * dh).toFloat(),
                (o.optDouble("nx2") * dw).toFloat(), (o.optDouble("ny2") * dh).toFloat(), o.optLong("ms", 300)
            )
            "key" -> global(o.optString("name"))
            "text" -> typeText(o.optString("value"))
            "typechar" -> typeChar(o.optString("value"))
            "backspace" -> backspace()
            "launch" -> launchApp(o.optString("pkg"))
            "open" -> openUrl(o.optString("url"))
            "close" -> closeApp()
        }
    }

    // ---- Control primitives (run on the main thread) ----

    private fun tap(x: Float, y: Float) = main.post {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(),
            null, null
        )
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) = main.post {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, ms.coerceIn(20, 5000))).build(),
            null, null
        )
    }

    private fun global(name: String) = main.post {
        val action = when (name.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return@post
        }
        performGlobalAction(action)
    }

    private fun typeText(text: String) = main.post {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return@post
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun launchApp(pkg: String) = main.post {
        packageManager.getLaunchIntentForPackage(pkg)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun openUrl(url: String) = main.post {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun longpress(x: Float, y: Float) = main.post {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 700)).build(),
            null, null
        )
    }

    /** Rewrites the text of the focused editable field (for keyboard input). */
    private fun applyText(transform: (String) -> String) = main.post {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return@post
        val next = transform(node.text?.toString() ?: "")
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next) }
        )
        runCatching {
            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, next.length)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, next.length)
                }
            )
        }
    }

    private fun typeChar(s: String) = applyText { it + s }
    private fun backspace() = applyText { if (it.isEmpty()) it else it.dropLast(1) }

    /** Full physical display size (incl. system bars) — the gesture coord space. */
    private fun displaySize(): Pair<Float, Float> {
        val m = DisplayMetrics()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        return m.widthPixels.toFloat() to m.heightPixels.toFloat()
    }

    /** Close the current app: open Recents, then swipe the front card up. */
    private fun closeApp() {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        main.postDelayed({
            val (w, h) = displaySize()
            val path = Path().apply { moveTo(w / 2f, h * 0.6f); lineTo(w / 2f, h * 0.1f) }
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 250)).build(),
                null, null
            )
        }, 600)
    }

    // ---- Minimal LAN HTTP control server ----

    private fun startHttpServer() {
        if (server != null) return
        thread(name = "KioskControlHttp", isDaemon = true) {
            try {
                val s = ServerSocket(PORT)
                server = s
                while (!s.isClosed) {
                    val client = s.accept()
                    thread(isDaemon = true) { handle(client) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "HTTP control server stopped: ${e.message}")
            }
        }
    }

    private fun stopHttpServer() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(client: Socket) {
        client.use {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) { respond(client.getOutputStream(), 400, "bad request"); return }
            val target = parts[1]
            val path = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))
            var token: String? = null
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
                if (h.startsWith("X-Token:", ignoreCase = true)) token = h.substringAfter(':').trim()
            }
            if (TOKEN.isNotEmpty() && token != TOKEN) {
                respond(client.getOutputStream(), 401, "unauthorized"); return
            }
            val body = runCatching { execute(path, query) }.getOrElse { "error: ${it.message}" }
            respond(client.getOutputStream(), 200, body)
        }
    }

    private fun execute(path: String, q: Map<String, String>): String {
        fun f(k: String) = q[k]?.toFloatOrNull() ?: 0f
        return when (path) {
            "/ping" -> "ok"
            "/tap" -> { tap(f("x"), f("y")); "tap ${f("x")},${f("y")}" }
            "/longpress" -> { longpress(f("x"), f("y")); "longpress" }
            "/swipe" -> { swipe(f("x1"), f("y1"), f("x2"), f("y2"), q["ms"]?.toLongOrNull() ?: 300); "swipe" }
            "/key" -> { global(q["name"] ?: ""); "key ${q["name"]}" }
            "/text" -> { typeText(q["value"] ?: ""); "text" }
            "/typechar" -> { typeChar(q["value"] ?: ""); "typechar" }
            "/backspace" -> { backspace(); "backspace" }
            "/launch" -> { launchApp(q["pkg"] ?: ""); "launch ${q["pkg"]}" }
            "/open" -> { openUrl(q["url"] ?: ""); "open ${q["url"]}" }
            "/close" -> { closeApp(); "close" }
            else -> "unknown: $path"
        }
    }

    private fun parseQuery(raw: String): Map<String, String> =
        if (raw.isBlank()) emptyMap()
        else raw.split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else dec(it.substring(0, i)) to dec(it.substring(i + 1))
        }.toMap()

    private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun respond(out: OutputStream, code: Int, body: String) {
        val payload = body.toByteArray()
        val header = "HTTP/1.1 $code OK\r\n" +
            "Content-Length: ${payload.size}\r\n" +
            "Content-Type: text/plain\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(payload)
        out.flush()
    }
}
