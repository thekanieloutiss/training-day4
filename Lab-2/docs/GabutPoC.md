# Dokumentasi GabutPoC — WebRTC Screen Recording + Remote Control

## Arsitektur

```
┌──────────────────────────────────────────────────────────────────┐
│                       Android Device (Kiosk)                     │
│                                                                  │
│  ┌─────────────────┐    ┌──────────────────────────────┐        │
│  │  MainActivity   │    │     ScreenCaptureService      │        │
│  │                 │    │  ┌──────────────────────────┐ │        │
│  │  [Aktifkan      │    │  │  MediaProjection API    │ │        │
│  │   Stream]       │───►│  │  ScreenCapturerAndroid  │ │        │
│  │                 │    │  │  VideoSource + Track     │ │        │
│  │  [Aktifkan      │    │  │  PeerConnection (WHIP)  │ │        │
│  │   Full Control] │    │  └──────────┬───────────────┘ │        │
│  └─────────────────┘    │             │ WHIP/HTTP        │        │
│                          │             ▼                  │        │
│  ┌─────────────────┐    │     ┌──────────────┐          │        │
│  │KioskControl     │    │     │ WhipClient   │          │        │
│  │Service (Accessi-│    │     └──────────────┘          │        │
│  │bilityService)   │    └──────────────────────────────┘        │
│  │                 │                                            │
│  │ - Gesture       │    ┌──────────────────────────────┐        │
│  │ - Tap/Swipe     │    │     KioskControlService       │        │
│  │ - Key Events    │    │  ┌──────────────────────────┐ │        │
│  │ - Text Input    │    │  │  HTTP Server (:8091)     │ │        │
│  │ - Launch App    │    │  │  WebSocket Relay Client  │ │        │
│  └─────────────────┘    │  └──────────┬───────────────┘ │        │
│                          │             │ WS/outbound      │        │
│  ┌─────────────────┐    └─────────────┼────────────────┘        │
│  │  BootReceiver   │                   │                        │
│  │  (Auto-start)   │                   │                        │
│  └─────────────────┘                   │                        │
└────────────────────────────────────────┼────────────────────────┘
                                         │
                    ┌────────────────────┼────────────────────┐
                    │                    │                     │
                    ▼                    ▼                     ▼
           ┌────────────────┐  ┌──────────────┐  ┌────────────────┐
           │   SRS Server   │  │    Relay     │  │   Dashboard    │
           │   (ossrs/srs:5)│  │(Node + ws)   │  │  (nginx +      │
           │                │  │              │  │   dashboard    │
           │ :1985 API      │  │ :8092 WS     │  │   .html)       │
           │ :8000/UDP media│  │              │  │                │
           └────────────────┘  └──────────────┘  └────────────────┘
                  │                                        │
                  └──────────── WHEP (HTTP) ───────────────┘
                                       │
                                       ▼
                              ┌────────────────┐
                              │    Browser     │
                              │  (Operator)    │
                              │                │
                              │ - View Stream  │
                              │ - Tap/Swipe    │
                              │ - Key Commands │
                              └────────────────┘
```

## Komponen APK

### 1. MainActivity.kt
**Path:** `android/app/src/main/java/com/example/screenwhip/MainActivity.kt`

**Fungsi:** UI utama dengan 2 tombol:
- **"Aktifkan Stream"** — trigger MediaProjection consent → start ScreenCaptureService
- **"Aktifkan Full Control"** — buka Accessibility Settings → enable KioskControlService

**Alur:**
```
User tap "Aktifkan Stream"
  → Request POST_NOTIFICATIONS (Android 13+)
  → Dialog MediaProjection (screen capture consent)
  → onActivityResult → startForegroundService(ScreenCaptureService)
  → Status: connecting → live / error (via BroadcastReceiver)

User tap "Aktifkan Full Control"
  → Cek isAccessibilityEnabled()
  → Jika belum: AlertDialog → Settings.ACTION_ACCESSIBILITY_SETTINGS
  → Jika sudah: show control info
```

### 2. ScreenCaptureService.kt
**Path:** `android/app/src/main/java/com/example/screenwhip/ScreenCaptureService.kt`

**Fungsi:** Foreground service untuk screen capture + WHIP publish.

**Alur Detail:**
```
onStartCommand()
  → Dapatkan MediaProjection dari intent
  → buildNotification() → startForeground()
  → startStream()
      → Init EGL, PeerConnectionFactory (dengan ForcedH264EncoderFactory)
      → Init ScreenCapturerAndroid (1280px max, 24fps)
      → Create VideoTrack "screen_track"
      → Build WHIP URL: http://{server}/rtc/v1/whip/?app=live&stream={deviceId}
      → Create PeerConnection (STUN: stun.l.google.com:19302)
      → Add track, createOffer
      → Wait ICE gathering COMPLETE (max 2.5s)
      → doWhipPublish(offerSdp) — POST ke SRS, retry 6x
      → broadcastState("live")

onDestroy()
  → DELETE WHIP session
  → Close PeerConnection, capturer, EGL
```

**Constants:**
| Name | Value |
|------|-------|
| TARGET_FPS | 24 |
| MAX_DIMENSION | 1280 |
| ICE_GATHER_TIMEOUT_MS | 2500 |
| MAX_WHIP_RETRIES | 6 |
| RETRY_DELAY_MS | 2000 |

### 3. KioskControlService.kt
**Path:** `android/app/src/main/java/com/example/screenwhip/KioskControlService.kt`

**Fungsi:** AccessibilityService untuk remote control via WebSocket relay.

**Alur:**
```
onServiceConnected()
  → startHttpServer() — local HTTP control (:8091)
  → startRelayWs() — connect ke ws://{server}:8092/?role=device&id={deviceId}

Relay Commands (JSON via WebSocket):
  tap      → performGesture (single tap)
  longpress → performGesture (700ms hold)
  swipe    → performGesture (line stroke)
  key      → performGlobalAction (back/home/recents/notifications)
  text     → set text on focused input
  typechar → append char to focused field
  backspace → delete last char
  launch   → startActivity by package
  close    → open recents + swipe up
```

### 4. WhipClient.kt
**Path:** `android/app/src/main/java/com/example/screenwhip/WhipClient.kt`

**Fungsi:** Minimal HTTP client untuk WHIP signaling.

```kotlin
publish(offerSdp): String?  // POST → SRS, return SDP answer
delete()                    // DELETE → resource URL (teardown)
```

### 5. DeviceId.kt
**Path:** `android/app/src/main/java/com/example/screenwhip/DeviceId.kt`

**Fungsi:** Generate stream name unik per device.

```
Emulator: "emulator-{last4 ANDROID_ID}"
Physical: "{Build.MODEL}-{last4 ANDROID_ID}"
```

### 6. BootReceiver.kt
**Path:** `android/app/src/main/java/com/example/screenwhip/BootReceiver.kt`

**Fungsi:** Auto-launch app setelah device reboot.

### 7. ForcedH264EncoderFactory.kt
**Path:** `android/app/src/main/java/org/webrtc/ForcedH264EncoderFactory.kt`

**Fungsi:** Force H.264 encoder (wajib untuk SRS), terutama di emulator yang hanya punya software codec.

## Server Components

### SRS (Simple Realtime Server)
- **Image:** `ossrs/srs:5`
- **Config:** `docker/srs.conf`
- **Ports:** 1935(RTMP), 1985(API+WHIP/WHEP), 8080(HTTP), 8000/UDP(WebRTC)
- **CANDIDATE:** IP server yang diiklankan untuk ICE

### Control Relay
- **Image:** Custom Node.js (`docker/relay/`)
- **Port:** 8092 (WebSocket)
- **Library:** `ws` (WebSocket)
- **Logic:** Route commands from dashboard to device by device ID

### Dashboard
- **Image:** `nginx:alpine`
- **Port:** 8088
- **Content:** `web-viewer/` (dashboard.html, config.js, index.html)

## Endpoints Summary

| Endpoint | Port | Protocol | Direction |
|----------|------|----------|-----------|
| WHIP Publish | 1985 | HTTP POST | Device → SRS |
| WHEP Play | 1985 | HTTP POST | Browser → SRS |
| SRS API | 1985 | HTTP GET | Poll streams |
| WebRTC Media | 8000 | UDP | Device ↔ SRS |
| Relay WS | 8092 | WebSocket | Device ↔ Relay ↔ Dashboard |
| Dashboard | 8088 | HTTP | Browser → nginx |
| Device HTTP | 8091 | HTTP | Direct LAN control (optional) |

## WHIP Signaling Flow

```
Device                              SRS
  │                                  │
  │── POST /rtc/v1/whip/?app=live & ──►
  │   stream={deviceId}               │
  │   Content-Type: application/sdp  │
  │   (SDP offer with H.264 video)   │
  │                                  │
  │◄── 201 Created                    │
  │   Location: /rtc/v1/whip/session/ │
  │   Content-Type: application/sdp  │
  │   (SDP answer)                   │
  │                                  │
  │── WebRTC media (UDP) ────────────►
  │                                  │
  │── DELETE /rtc/v1/whip/session/ ──► (teardown)
```

## Emulator Notes

- Android emulator **tidak punya H.264 hardware encoder**
- `ForcedH264EncoderFactory` memaksa software `c2.android.avc.encoder`
- Byte-buffer mode (YUV), bukan Surface input
- **~15-20 detik delay** decode awal (menunggu keyframe)
- Ping ke host IP hang (ICMP diblok SLIRP) — TCP/UDP tetap jalan
