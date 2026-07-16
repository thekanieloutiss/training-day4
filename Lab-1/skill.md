---
name: gabutpoc-apk
description: Gunakan untuk distribusi dan instalasi APK GabutPoC — screen recording + remote control via WebRTC ke SRS server. Server SRS + relay + dashboard sudah berjalan. Hanya perlu build APK, install ke device target, dan deploy server via Docker.

trigger:
  patterns:
    - gabutpoc
    - deploy gabutpoc
    - screen recording
    - remote control kiosk
    - whip whep
    - srs streaming
    - build apk gabutpoc
    - install gabutpoc
  keywords:
    - SRS_HOST
    - MediaProjection
    - AccessibilityService
    - WHIP
    - WHEP
    - WebRTC
    - KioskControlService
    - ScreenCaptureService
    - ForcedH264Encoder
    - BootReceiver
    - dashboard.html
    - control relay
---

# GabutPoC — APK Distribution & Deployment

## Context
APK Android untuk streaming layar real-time ke browser via WebRTC + SRS.
Server (SRS + relay + dashboard) SUDAH berjalan di laptop/server lain.
Tugas: build APK → deploy server → install ke device → streaming + remote control.

## Struktur Direktori
| Folder | Isi |
|--------|-----|
| `android/` | Android app (Kotlin, Gradle) |
| `docker/` | SRS relay dashboard containers |
| `web-viewer/` | dashboard.html, index.html, config.js |

## Quick Start

```bash
# 1. Build APK
cd android
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-x86_64-debug.apk

# 2. Deploy server
cd ../docker
CANDIDATE=10.103.105.79 docker compose up -d --build

# 3. Install ke device
adb install ../android/app/build/outputs/apk/debug/app-x86_64-debug.apk

# 4. Di device: Buka GabutPoC → Aktifkan Stream → izinkan screen recording
# 5. Browser: http://10.103.105.79:8088/dashboard.html
# 6. Remote: Aktifkan Full Control → Accessibility → enable → Dashboard → Remote
```

## Key Ports
- 1985 TCP: WHIP/WHEP API
- 8000 UDP: WebRTC media
- 8088 TCP: Dashboard web
- 8092 TCP: Relay WebSocket

## Key Classes (Android)
| Class | Function |
|-------|----------|
| `MainActivity` | UI 2 tombol: Stream + Control |
| `ScreenCaptureService` | Foreground service, MediaProjection + WHIP |
| `KioskControlService` | AccessibilityService, remote gesture via WS |
| `WhipClient` | HTTP signaling untuk WHIP protocol |
| `DeviceId` | Generate unique stream name |
| `BootReceiver` | Auto-start on boot |
| `ForcedH264EncoderFactory` | Force H.264 codec (required by SRS) |
