---
name: gabutpoc
description: Use when working on GabutPoC — Android screen recording → SRS via WHIP → browser via WHEP. Triggers on keywords: deploy.sh, SRS, SRS_HOST, WHIP, WHEP, ScreenCaptureService, WhipClient, KioskControlService, MainActivity, ForcedH264Encoder, ForcedH264EncoderFactory, DeviceId, BootReceiver, dashboard.html, docker-compose.yml, srs.conf, relay/server.js, config.js, gradle.properties, WebRTC, media server, screen capture, kiosk, accessibility, remote control, video-only, GabutPoC, control relay.
---

# GabutPoC — WebRTC Android Screen Record → SRS → Browser

## Architecture

```
Android app  ──WHIP (HTTP)──►  SRS server  ──WHEP (HTTP)──►  Browser
(MediaProjection)              (WebRTC media)                (<video>)

Dashboard ──ws──► Control Relay (:8092) ◄──ws── Kiosk (KioskControlService)
```

Signaling runs over HTTP (WHIP/WHEP) — no separate WebSocket server needed; SRS handles it.

**Single source of truth**: `SRS_HOST` in `Lab-1/android/gradle.properties` drives both the APK default server field (via `resValue`) and `Lab-1/web-viewer/config.js` (via Gradle task `generateWebConfig`).

**Multi-device**: Each device publishes with a unique stream name derived from `DeviceId.get()` (`Build.MODEL` + last 4 chars of `ANDROID_ID`; `emulator-xxxx` on emulators). WHIP URL: `http://<server>/rtc/v1/whip/?app=live&stream=<device-id>`.

## Directory structure

| Folder | Purpose |
|--------|---------|
| `Lab-1/docker/` | Docker Compose deployment: SRS, control relay, nginx dashboard |
| `Lab-1/web-viewer/` | Browser viewer (`index.html` for single stream, `dashboard.html` for multi-device) |
| `Lab-1/android/` | Android app in Kotlin (Android Studio project) |
| `Lab-1/deploy.sh` | One-command deploy: config SRS_HOST, start Docker, build APK |

---

## Deployment (`deploy.sh`, `docker/`)

### Core command

```bash
Lab-1/deploy.sh                  # prompts for server IP (deploy containers + build APK)
Lab-1/deploy.sh 192.168.100.22   # non-interactive
SKIP_APK=1 Lab-1/deploy.sh 192.168.100.22  # deploy server only, skip APK build
Lab-1/deploy.sh install [serial] # adb install to connected kiosk (bypasses Play Protect)
Lab-1/deploy.sh down             # stop & remove all containers
```

### What deploy.sh does

1. Sets `SRS_HOST=<IP>:1985` in `Lab-1/android/gradle.properties`
2. Generates `Lab-1/web-viewer/config.js` with `window.SRS_HOST = '<IP>:1985'`
3. Runs `docker compose up -d --build` with `CANDIDATE=<IP>`
4. Builds Android APK via `./gradlew assembleDebug`

### Docker services (`docker/docker-compose.yml`)

| Service | Container | Port | Purpose |
|---------|-----------|------|---------|
| SRS (ossrs/srs:5) | `srs-webrtc` | 1935 TCP (RTMP), 1985 TCP (API+WHIP/WHEP), 8080 TCP (HTTP server), 8000 UDP (WebRTC media) | Media server |
| Relay (Node + ws) | `control-relay` | 8092 TCP (WebSocket) | Routes dashboard commands to kiosk devices |
| Dashboard (nginx:alpine) | `gabutpoc-dashboard` | 8088 TCP | Serves `web-viewer/` (dashboard, viewer, config.js) |

### SRS config (`docker/srs.conf`)

- `CANDIDATE` env var (injected by compose) sets the ICE candidate IP
- `rtc_server` on UDP 8000
- `http_api` on TCP 1985 (WHIP/WHEP endpoints + REST API)
- `http_server` on TCP 8080
- H.264 required for WebRTC publish; enables `rtmp_to_rtc` / `rtc_to_rtmp` bridges

### adb install (`./deploy.sh install`)

Disables the adb install verifier on the device before installing (since Play Protect flags screen-capture + accessibility as spyware). For devices you own and reach via adb. Walkaround: Play Store → Play Protect → Settings → disable "Scan apps".

---

## Android app (`android/`)

### Build system

- **Gradle Kotlin DSL** (`app/build.gradle.kts`): `compileSdk=34`, `minSdk=24`, `targetSdk=34`
- **JDK 17** target (`jvmTarget = "17"`)
- **ABI splits**: one APK per architecture (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) + universal APK
- **R8 minify + shrinkResources** on release (ProGuard keep rule for `org.webrtc.**`)
- **viewBinding** enabled
- **`SRS_HOST`** from `gradle.properties` injected as `@string/default_server` via `resValue`
- **`generateWebConfig`** Gradle task writes `web-viewer/config.js` before every build

### Dependencies

- `io.getstream:stream-webrtc-android:1.3.8` (WebRTC `org.webrtc` API)
- `com.squareup.okhttp3:okhttp:4.12.0` (HTTP for WHIP signaling)
- Standard AndroidX: `core-ktx`, `appcompat`, `material`
- `kotlinx-coroutines-android`

### Key classes

#### `MainActivity.kt` (`com.example.screenwhip`)
Two-menu UI:

1. **"Aktifkan Stream"** — triggers `MediaProjection` screen capture consent dialog (forced entire-screen on Android 14+ via `MediaProjectionConfig.createConfigForDefaultDisplay()`), then starts `ScreenCaptureService` as foreground service. WHIP URL built from server + device ID. Asks `POST_NOTIFICATIONS` permission on Android 13+. Receives state broadcasts (`ACTION_STATE`) to update status indicator (connecting=amber, live=green, error=red).

2. **"Aktifkan Full Control"** — shows explainer dialog, then deep-links to Android Accessibility settings for GabutPoC (direct toggle page on Android 11+, fallback to list). Checks `isAccessibilityEnabled()` by reading `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. Shows relay address when active.

#### `ScreenCaptureService.kt` (`com.example.screenwhip`)
Foreground service (`foregroundServiceType=mediaProjection`) that:

- Initializes `PeerConnectionFactory` with `ForcedH264EncoderFactory` (must be H.264 for SRS)
- Captures screen via `ScreenCapturerAndroid` + `VideoSource` + `SurfaceTextureHelper`
- Caps long edge at 1280px (even dimensions), target 24fps
- Creates `PeerConnection` (STUN: `stun.l.google.com:19302`) — video track send-only
- **WHIP is non-trickle**: offer sent after ICE gathering COMPLETE, with 2.5s fallback timeout
- **Auto-retry**: WHIP publish retries up to 6x with 2s delay on transient failures
- Sends `ACTION_STATE` broadcasts to `MainActivity` ("connecting" → "live" / "error")
- Notification with "Hentikan" action that sets `manualStop = true` and calls `stopSelf()`
- `onDestroy()`: tears down WHIP session (DELETE), capturer, tracks, peer connection, factory, EGL

Constants: `TARGET_FPS=24`, `MAX_DIMENSION=1280`, `ICE_GATHER_TIMEOUT_MS=2500`, `MAX_WHIP_RETRIES=6`, `RETRY_DELAY_MS=2000`.

#### `WhipClient.kt` (`com.example.screenwhip`)
Minimal WHIP client:
- `publish(offerSdp)` — POST with `Content-Type: application/sdp`, returns SDP answer
- Stores `resourceUrl` from `Location` header for teardown
- `delete()` — best-effort DELETE of the session resource
- OkHttp timeouts: connect 10s, read 15s

#### `KioskControlService.kt` (`com.example.screenwhip`)
AccessibilityService (`canPerformGestures`) providing full remote control:

**Dual connectivity**:
- **HTTP server** on port 8091 (direct LAN, optional): `/tap`, `/swipe`, `/key`, `/text`, `/typechar`, `/backspace`, `/launch`, `/open`, `/close`, `/ping`. `X-Token` header for auth (empty by default).
- **WebSocket relay** on `ws://<server>:8092/?role=device&id=<deviceId>`: connects OUT to the relay server. Auto-reconnect every 3s. Receives JSON commands from dashboard.

**Relay commands** (JSON with normalized coords `nx/ny` 0..1, scaled to physical display):
- `tap`, `longpress` — `GestureDescription` single-point stroke
- `swipe` — line stroke with configurable duration (20-5000ms range)
- `key` — `performGlobalAction` for `back`, `home`, `recents`, `notifications`
- `text` — `ACTION_SET_TEXT` on focused input node
- `typechar` / `backspace` — append/remove from editable field text
- `launch` — `packageManager.getLaunchIntentForPackage()` + `NEW_TASK`
- `open` — `ACTION_VIEW` intent for URLs
- `close` — opens Recents then swipes front card up

#### `ForcedH264EncoderFactory.kt` (`org.webrtc`)
Lives in `org.webrtc` package to access package-private WebRTC internals.

- Forces an H.264 encoder (including software `OMX.google.h264.encoder` / `c2.android.avc.encoder` that stock WebRTC filters out)
- Prefers hardware encoder, falls back to software
- Uses byte-buffer (YUV) mode (no shared EGL context) — software codecs don't support Surface input
- Constrained baseline profile `42e01f`, level 3.1
- Periodic keyframes every 2s (helps viewers joining mid-stream)

#### `DeviceId.kt` (`com.example.screenwhip`)
Stable device identifier for SRS stream name:
- Emulator detection via `Build.FINGERPRINT`, `Build.MODEL`, `Build.PRODUCT`, `Build.HARDWARE`
- Emulator: `"emulator-xxxx"`, physical: `"<model>-xxxx"`
- URL-safe: lowercase, non-alphanum → hyphens, trailing hyphens stripped
- Suffix: last 4 alphanum chars of `Settings.Secure.ANDROID_ID`

#### `BootReceiver.kt` (`com.example.screenwhip`)
Listens for `BOOT_COMPLETED` and `LOCKED_BOOT_COMPLETED` to auto-start the app on device boot.

### AndroidManifest.xml

Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`.

Application: `usesCleartextTraffic=true` (for LAN HTTP WHIP). 

Services: `ScreenCaptureService` (`foregroundServiceType=mediaProjection`), `KioskControlService` (`BIND_ACCESSIBILITY_SERVICE`).

### Codec note

SRS requires H.264 for WebRTC publish. Physical Android devices have hardware H.264 encoders. Android emulator does not — `ForcedH264EncoderFactory` forces the software AVC encoder to make emulator publishing work. Verified end-to-end (emulator screen visible in browser, ~15-20s initial decode delay due to waiting for keyframe).

---

## Web viewer (`web-viewer/`)

### `config.js`
Auto-generated by Gradle (`generateWebConfig` task in `app/build.gradle.kts`) and `deploy.sh`:
```js
window.SRS_HOST = '<ip>:1985';
```
Read by both `dashboard.html` and `index.html` for the SRS server address. The config file is mounted into the nginx dashboard container (`docker-compose.yml`).

### `dashboard.html`
Multi-device dashboard served by nginx on port 8088:
- **Sidebar** with two menus: **Stream** (grid of publishing devices) and **Remote** (control selected device)
- **Stream menu**: polls SRS `/api/v1/streams/` every 2s, shows device ID, status, resolution, codec, bitrate, viewers, uptime. Click a card to watch the stream via WHEP.
- **Remote menu**: select a device, watch its stream, send control commands via WebSocket to the relay. Click video = tap, hold = long-press, drag = swipe. Buttons for Back/Home/Recents/Notif, keyboard input, app launch, close, text set.
- **Bottom sidebar**: server status, auto-refresh toggle, relay connection status.
- Connects to relay via `ws://<server>:8092/?role=dashboard`

### `index.html`
Single-stream WHEP viewer. Change the WHEP URL to the SRS IP, click Play. Video-only (no audio m-line). Note: browser needs secure context for WebRTC except on localhost.

---

## Control relay (`docker/relay/`)

### `docker/relay/server.js`
Node.js WebSocket relay server on port 8092:
- **Device connection**: `ws://<server>:8092/?role=device&id=<deviceId>` — stored in `devices` Map
- **Dashboard connection**: `ws://<server>:8092/?role=dashboard` — stored in `dashboards` Set
- **Command routing**: dashboard sends `{ to: "<deviceId>", action: "...", ... }` → relayed to matching device
- **Device broadcasts**: any message from a device is relayed to ALL dashboards
- **Device list push**: `{ type: "devices", devices: [...] }` sent to all dashboards on connect/disconnect
- **HTTP endpoint**: `GET /devices` returns JSON list of connected device IDs
- Replaces stale connection on same device ID (sends `{ type: "replaced" }` to old connection)

### Relay command protocol

All coordinates in `nx`/`ny` normalized (0..1), scaled to physical display by `KioskControlService`:

| Action | Parameters | Description |
|--------|-----------|-------------|
| `tap` | `nx`, `ny` | Single tap |
| `longpress` | `nx`, `ny` | Long press (~700ms) |
| `swipe` | `nx1`, `ny1`, `nx2`, `ny2`, `ms` | Swipe/scroll gesture |
| `key` | `name` | Global key: `back`, `home`, `recents`, `notifications` |
| `text` | `value` | Set text in focused input field |
| `typechar` | `value` | Append character to focused field |
| `backspace` | — | Delete last character from focused field |
| `launch` | `pkg` | Open app by package name |
| `open` | `url` | Open URL in browser |
| `close` | — | Open Recents then swipe current app away |

### `docker/relay/Dockerfile`
Multi-stage or simple Node image. Uses `node` base, copies `package.json` + `server.js`, runs `npm install` (production only — `ws` dependency).

### `docker/relay/package.json`
Single dependency: `ws`. Script: `"start": "node server.js"`.

---

## Endpoints summary

| Endpoint | Port | Protocol | Purpose |
|----------|------|----------|---------|
| WHIP publish | 1985 | HTTP | Android → SRS |
| WHEP play | 1985 | HTTP | Browser → SRS |
| SRS HTTP API | 1985 | HTTP | `/api/v1/streams/` for polling |
| SRS HTTP server | 8080 | HTTP | Built-in players |
| WebRTC media | 8000 | UDP | PeerConnection media |
| RTMP | 1935 | TCP | Optional RTMP ingest |
| Dashboard | 8088 | HTTP | nginx serving `web-viewer/` |
| Control relay | 8092 | WebSocket | Dashboard ↔ device commands |
| Device HTTP (local) | 8091 | HTTP | Direct LAN control (optional) |

## Troubleshooting quick reference

| Symptom | Cause / fix |
|---------|-------------|
| SRS `no found valid H.264 payload type` | Emulator has no hardware H.264 — use physical device or `ForcedH264EncoderFactory` |
| Video black / no connect | `CANDIDATE` wrong — set to LAN IP clients can reach, restart SRS |
| `cleartext HTTP not permitted` | Handled by `usesCleartextTraffic=true`; for production use HTTPS |
| Browser no WebRTC | Not secure context — use `localhost` or HTTPS |
| Firewall | Open UDP 8000 and TCP 1985 |
| Emulator: ping to host IP hangs | Normal (ICMP blocked by SLIRP); TCP/UDP still work |
