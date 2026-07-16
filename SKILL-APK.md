---
name: gabutpoc-apk
description: Panduan distribusi dan instalasi APK GabutPoC. Server di 10.103.105.79.
---

# GabutPoC — APK Distribution Guide

## Server Info

**IP Server**: `10.103.105.79`

| Layanan | URL |
|---------|-----|
| Dashboard | `https://10.103.105.79/dashboard.html` |
| WHIP/WHEP | `http://10.103.105.79:1985` |
| WebSocket Relay | `ws://10.103.105.79:8092` |

## Build APK

`android/gradle.properties` sudah diset:

```properties
SRS_HOST=10.103.105.79:1985
```

Build:

```bash
cd android
gradlew.bat assembleDebug
```

## Output APK

`android/app/build/outputs/apk/debug/`

| APK | Ukuran | Target |
|-----|--------|--------|
| `app-arm64-v8a-debug.apk` | 17 MB | Samsung, Xiaomi, Pixel, HP modern |
| `app-armeabi-v7a-debug.apk` | 12 MB | HP lama (2015-2018) |
| `app-x86_64-debug.apk` | 18 MB | Emulator 64-bit |
| `app-x86-debug.apk` | 18 MB | Emulator 32-bit |
| `app-universal-debug.apk` | 48 MB | Semua device (kirim ini jika ragu) |

## Install

**Via adb**:

```bash
adb install app-x86_64-debug.apk          # emulator
adb install app-universal-debug.apk       # HP fisik
```

**Manual**: kirim APK ke device → buka file manager → tap APK → izinkan unknown apps.

## Cara Pakai

### Streaming

1. Buka **GabutPoC**
2. Tap **"Aktifkan Stream"**
3. Izinkan screen recording + notifikasi
4. Status: kuning → hijau = live

### Lihat Stream

```
https://10.103.105.79/dashboard.html
```

Klik card device.

### Remote Control

1. Tap **"Aktifkan Full Control"**
2. Settings → Accessibility → GabutPoC → aktifkan
3. Dashboard → Remote → pilih device
4. Klik = tap, drag = swipe

### Hentikan

Notifikasi → **Hentikan**

## Monitoring

Pantau device yang terhubung ke server:

**PowerShell** (jalankan di laptop mana saja):

```powershell
.\monitor.ps1
# atau dengan IP berbeda
.\monitor.ps1 -ServerIp 10.103.105.79 -Interval 3
```

**Browser**: buka `monitor.html` (bisa langsung dari file atau di-host)

**API langsung**:
- SRS streams: `http://10.103.105.79:1985/api/v1/streams/`
- Relay devices: `http://10.103.105.79:8092/devices`

## Troubleshooting

| Masalah | Solusi |
|---------|--------|
| Streaming stuck kuning | Cek firewall server: UDP 8000 terbuka |
| Video hitam | CANDIDATE IP server harus benar |
| Remote tidak bisa | Accessibility Service belum aktif |
| Gagal install | Settings → Unknown apps → izinkan |
