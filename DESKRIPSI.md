# GabutPoC

GabutPoC adalah **APK Android** untuk streaming layar real-time ke browser via WebRTC + SRS.

## Cara Kerja

```
Android app (APK) ──WHIP──► SRS Server (laptop lain) ──WHEP──► Browser
                   ──ws──► Control Relay ◄──ws── Dashboard
```

APK ini hanya **client** — server (SRS + relay + dashboard) sudah berjalan di laptop lain.

## Fitur

- **Streaming layar** — MediaProjection screen capture, publish via WHIP ke SRS
- **Remote control** — AccessibilityService untuk tap/swipe/keyboard remote via dashboard
- **Auto-start** — BootReceiver untuk mulai otomatis setelah restart device
- **Multi-device** — Stream name unik per device (model + ANDROID_ID)
- **H.264** — Encoder H.264 (hardware/software) untuk kompatibilitas SRS

## Komponen APK

| Package | Fungsi |
|---------|--------|
| `MainActivity` | UI: tombol start stream + enable remote control |
| `ScreenCaptureService` | Foreground service: capture layar + WHIP publish |
| `KioskControlService` | AccessibilityService: remote control via WebSocket relay |
| `WhipClient` | HTTP client untuk WHIP signaling |
| `DeviceId` | Generate stream name unik per device |
| `BootReceiver` | Auto-launch setelah boot |
| `ForcedH264EncoderFactory` | Force H.264 encoder (penting untuk emulator) |
