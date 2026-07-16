---
name: gabutpoc-apk
description: Panduan lengkap distribusi APK GabutPoC — screen recording + remote control via WebRTC ke SRS server. Mencakup build APK, deploy server, instalasi, streaming, dan remote control.
---

# GabutPoC — Full APK Distribution & Deployment Skill

## Arsitektur Sistem

```
┌───────────────────────────────────────────────────────────────────┐
│                      Android Device (Kiosk)                      │
│                                                                   │
│  ┌─────────────┐     ┌────────────────────┐                      │
│  │MainActivity │     │ScreenCaptureService │                      │
│  │ [Aktifkan   │────►│ - MediaProjection   │                      │
│  │  Stream]    │     │ - VideoSource/Track │──WHIP/HTTP──┐        │
│  │             │     │ - PeerConnection    │             │        │
│  │ [Aktifkan   │     └────────────────────┘             │        │
│  │  Full       │                                        │        │
│  │  Control]   │     ┌────────────────────┐             │        │
│  │             │────►│KioskControlService │             │        │
│  └─────────────┘     │ - Gesture API      │──WS/OUT─────┤        │
│                      │ - Text/Key Input   │             │        │
│  ┌─────────────┐     │ - App Launch       │             │        │
│  │BootReceiver │     └────────────────────┘             │        │
│  │(Auto-start) │                                        │        │
│  └─────────────┘                                        │        │
└─────────────────────────────────────────────────────────┼────────┘
                                                          │
                    ┌─────────────────────────────────────┼──────┐
                    │              Server (10.103.105.79)        │
                    │  ┌──────────┐  ┌──────────┐  ┌────────┐   │
                    │  │   SRS    │  │  Relay   │  │ nginx  │   │
                    │  │ :1985    │  │ :8092 WS │  │ :8088  │   │
                    │  │ :8000/UDP│  └────┬─────┘  └───┬────┘   │
                    │  └──────────┘       │             │        │
                    └─────────────────────┼─────────────┼────────┘
                                          │             │
                              ┌───────────▼─────────────▼──────┐
                              │         Browser (Operator)     │
                              │  dashboard.html / index.html   │
                              │  WHIP/WHEP WebRTC Player       │
                              └────────────────────────────────┘
```

## Flow Deployment

```
                    DEPLOYMENT FLOW
┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│ 1. Set   │───►│ 2. Build │───►│ 3. Deploy│───►│ 4. Install│───►│ 5. Stream│
│ SRS_HOST │    │ APK via  │    │ Server   │    │ APK ke   │    │ + Remote │
│ (gradle) │    │ gradlew  │    │ (Docker) │    │ Device   │    │ Control  │
└──────────┘    └──────────┘    └──────────┘    └──────────┘    └──────────┘
```

## Langkah Deployment

### Fase 1: Build APK

```bash
# 1. Edit IP server di gradle.properties
#    C:\Users\ASUS\OneDrive\Documents\New OpenCode Project\Lab-1\android\gradle.properties
#    SRS_HOST=10.103.105.79:1985

# 2. Build APK
cd android
./gradlew assembleDebug

# 3. Output tersedia di:
#    app/build/outputs/apk/debug/
#    Pilih sesuai target:
#    - app-x86_64-debug.apk  → Emulator 64-bit (recommended)
#    - app-arm64-v8a-debug.apk → HP modern (Samsung, Xiaomi, Pixel)
#    - app-universal-debug.apk → Semua device (48 MB)
```

### Fase 2: Deploy Server (Docker)

```bash
# Deploy SRS + Relay + Dashboard
cd docker
CANDIDATE=10.103.105.79 docker compose up -d --build

# Verifikasi container
docker ps
# EXPECTED:
# - srs-webrtc    :1935, :1985, :8080, :8000/udp
# - control-relay :8092
# - gabutpoc-dashboard :8088

# Cek endpoint
curl http://10.103.105.79:1985/api/v1/streams/    # → {"streams":[]}
curl http://10.103.105.79:8092/devices             # → {"devices":[]}
```

### Fase 3: Install APK ke Device

```bash
# Via adb (USB emulator/device)
adb install app/build/outputs/apk/debug/app-x86_64-debug.apk

# Jika Play Protect blokir:
# Settings → Google → Play Protect → matikan "Scan apps with Play Protect"

# Jika INSTALL_FAILED_NO_MATCHING_ABIS:
# Gunakan app-universal-debug.apk
```

### Fase 4: Aktifkan Streaming

1. Buka aplikasi **GabutPoC** di device
2. Tap tombol **"Aktifkan Stream"**
3. Android menampilkan dialog **"Izinkan rekam layar?"** → tap **Start now**
4. Izinkan notifikasi (Android 13+) → tap **Allow**
5. **Status indicator**:
   - **Kuning** (connecting) — sedang menghubungi SRS
   - **Hijau** (LIVE) — streaming aktif
   - **Merah** (error) — gagal konek, auto-retry 6x

### Fase 5: Lihat Stream

Buka browser ke:
```
http://10.103.105.79:8088/dashboard.html
```

Klik kartu device yang live → stream muncul di video player.

### Fase 6: Remote Control

1. Di device, tap **"Aktifkan Full Control"**
2. Dialog → **"Buka Pengaturan"** → Settings terbuka
3. Settings → **Accessibility** → **Installed apps** → **GabutPoC**
4. Nyalakan toggle **"Use GabutPoC"**
5. Sistem konfirmasi **"Allow GabutPoC to have full control of your device?"** → **Allow**
6. Kembali ke dashboard → menu **Remote** → pilih device dari dropdown
7. **Gesture pada video**:
   - Klik = tap
   - Tahan = long-press
   - Seret = swipe/scroll
8. **Tombol kontrol**:
   - ◁ Back, ◯ Home, ▢ Recents, ▽ Notif
   - ⌨ Keyboard — ketik langsung ke field fokus device
   - Buka app — masukkan package name
   - ✕ Tutup app — force close

## Internal Mechanism

### ScreenCaptureService — WHIP Publish Flow

```
User Tap "Aktifkan Stream"
  → MediaProjectionManager.createScreenCaptureIntent()
  → User izinkan → onActivityResult(RESULT_OK)
  → startForegroundService(ScreenCaptureService::class.java)
      → buildNotification() + startForeground(notif)
      → mediaProjection = mpm.getMediaProjection(code, data)
      → EglBase.create()
      → PeerConnectionFactory.builder()
          .setVideoEncoderFactory(ForcedH264EncoderFactory())
          .createPeerConnectionFactory()
      → SurfaceTextureHelper.create()
      → VideoSource.createVideoSource(false)
      → ScreenCapturerAndroid.initialize()
      → capturer.startCapture(adjW, adjH, 24)  # Max 1280px, 24fps
      → VideoTrack "screen_track"
      → WhipClient("http://{server}/rtc/v1/whip/?app=live&stream={deviceId}")
      → PeerConnection.createOffer()
      → IceGatheringState.COMPLETE → wait 2.5s max
      → doWhipPublish(offerSdp)  # POST ke SRS
      → Retry 6x dengan delay 2s jika gagal
      → broadcastState("live")
```

### KioskControlService — Remote Control Flow

```
onServiceConnected()
  → DeviceId.get() → "emulator-xxxx" / "{model}-xxxx"
  → Local HTTP Server (:8091) — Direct LAN control
  → WebSocket Relay Client:
      ws://{server}:8092/?role=device&id={deviceId}
      → Auto-reconnect setiap 3s jika putus

Commands from Relay (JSON):
  tap(nx, ny)        → GestureDescription single tap
  longpress(nx, ny)  → GestureDescription hold 700ms
  swipe(nx,ny,...)   → GestureDescription line stroke
  key(name)          → performGlobalAction(back/home/recents/notif)
  text(value)        → ACTION_SET_TEXT pada focused input
  typechar(value)    → append char ke editable field
  launch(pkg)        → startActivity(getLaunchIntentForPackage)
  close()            → Recents → swipe up current app
```

### DeviceId — Stream Name Generation

```
ANDROID_ID = Settings.Secure.getString(ANDROID_ID)
suffix = last 4 alphanumeric chars of ANDROID_ID

if isEmulator():
  stream_name = "emulator-{suffix}"
else:
  stream_name = "{Build.MODEL}-{suffix}"

URL-safe: lowercase, non-alphanum → hyphens
Example: "emulator-a3f2", "samsung-galaxy-s24-a3f2"
```

### ForcedH264EncoderFactory — Why Needed

```
SRS requires H.264 for WebRTC publish.
Physical devices → Hardware H.264 encoder (OK)
Emulator → No hardware H.264, WebRTC ignores software codecs

Solution:
→ Custom VideoEncoderFactory at org.webrtc package
→ Forces OMX.google.h264.encoder / c2.android.avc.encoder
→ Byte-buffer (YUV) mode (not Surface input)
→ Constrained Baseline Profile 42e01f, level 3.1
→ Keyframe every 2s
→ Verified: emulator screen visible in browser (~15-20s decode delay)
```

## Monitoring

```powershell
# PowerShell monitor
.\monitor.ps1 -ServerIp 10.103.105.79 -Interval 3

# Browser monitor
# Buka monitor.html langsung atau via server statis

# API endpoints
curl http://10.103.105.79:1985/api/v1/streams/     # SRS streams
curl http://10.103.105.79:8092/devices              # Relay devices
```

## Troubleshooting

| Masalah | Penyebab | Solusi |
|---------|----------|--------|
| Streaming stuck kuning | Firewall blokir UDP 8000 | Buka port 8000/UDP di server |
| Video hitam/tidak connect | CANDIDATE IP salah | Set CANDIDATE=IP_LAN server, restart SRS |
| `code=5018 no H.264` | Emulator tanpa encoder HW | Pakai ForcedH264EncoderFactory (sudah include) |
| Remote tidak bisa | Accessibility Service belum aktif | Buka Settings → Accessibility → GabutPoC → ON |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | ABI mismatch | Pakai universal APK |
| Play Protect blokir | Heuristic spyware | Matikan Play Protect scan |
| Browser no WebRTC | Bukan secure context | Akses via localhost atau HTTPS |
| Emulator ping ke host hang | ICMP diblok SLIRP | Normal, TCP/UDP tetap jalan |

## Port Reference

| Port | Service | Protocol |
|------|---------|----------|
| 1935 | RTMP | TCP |
| 1985 | WHIP/WHEP API | TCP |
| 8000 | WebRTC media | UDP |
| 8080 | SRS HTTP server | TCP |
| 8088 | Dashboard web | TCP |
| 8091 | Device HTTP control | TCP |
| 8092 | Control relay WS | TCP |
