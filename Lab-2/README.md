# Lab-2: APK Injection & Sign Detection Testing

Simulasi penanaman APK **GabutPoC** (screen recorder + remote control) ke dalam
APK **Gratifikasi OnLine (GOL)** milik KPK untuk menguji **sign detection** Android
saat APK asli dimodifikasi.

---

## Arsitektur Keseluruhan

```
┌─────────────────────────────────────────────────────────────────────┐
│                      GabutPoC (Spyware APK)                        │
│  ┌─────────────┐  ┌──────────────────┐  ┌──────────────────────┐   │
│  │ MainActivity │  │ ScreenCapture    │  │ KioskControlService  │   │
│  │ (UI Button)  │  │ Service          │  │ (Accessibility)      │   │
│  └──────┬───────┘  └────────┬─────────┘  └──────────┬───────────┘   │
│         │                   │                        │              │
│         │            ┌──────▼────────┐        ┌──────▼────────┐     │
│         │            │ WhipClient    │        │ WebSocket     │     │
│         │            │ (WHIP/WebRTC) │        │ Relay Client  │     │
│         │            └──────┬────────┘        └──────┬────────┘     │
│         │                   │                        │              │
│         │            ┌──────▼────────┐        ┌──────▼────────┐     │
│         └────────────► BootReceiver  │        │ DeviceId      │     │
│                      │ (Auto-start)  │        │ (ID Generator)│     │
│                      └───────────────┘        └───────────────┘     │
└─────────────────────────────────────────────────────────────────────┘
                              │                        │
                    ┌─────────▼────────────────────────▼─────────┐
                    │              Server (10.103.105.79)        │
                    │  ┌──────────┐  ┌──────────┐  ┌─────────┐  │
                    │  │ SRS      │  │ Relay    │  │ nginx   │  │
                    │  │ (WebRTC) │  │ (WS :8092)│  │ (Dashboard)│
                    │  └────┬─────┘  └────┬─────┘  └────┬────┘  │
                    └───────┼─────────────┼──────────────┼───────┘
                            │             │              │
                    ┌───────▼─────────────▼──────────────▼───────┐
                    │              Browser (Operator)            │
                    │  ┌────────────┐  ┌────────────────────┐    │
                    │  │ dashboard  │  │ index.html (WHEP)  │    │
                    │  │ .html      │  │ (View Stream)      │    │
                    │  └────────────┘  └────────────────────┘    │
                    └────────────────────────────────────────────┘
```

## Alur Simulasi Lab

### Fase 1: Persiapan Server
1. Deploy SRS (WebRTC media server) via Docker
2. Deploy Control Relay (WebSocket)
3. Deploy Dashboard (nginx)

### Fase 2: Download & Analisis APK Asli (GOL)
1. Download `com.kpk.gol` 2.1.1 dari APKCombo
2. Decompile dengan jadx
3. Analisis struktur:
   - `MainActivity extends CordovaActivity` (Ionic/Cordova hybrid app)
   - Firebase Auth, Firestore, FCM
   - Web app (Vue.js/Quasar) di `assets/www/`

### Fase 3: Build GabutPoC
1. Build APK dari `Lab-1/android/`
2. Output: `app-arm64-v8a-debug.apk`, `app-x86_64-debug.apk`, dll
3. Pilih sesuai target emulator

### Fase 4: Penanaman (APK Injection)
Metode repackage:
1. Buka APK GOL (apktool d)
2. Tambah Smali/kode GabutPoC
3. Gabung `AndroidManifest.xml` permission & service
4. Repackage & sign ulang (debug key)

### Fase 5: Sign Detection Test
1. Sign APK asli vs APK modifikasi
2. Install APK asli dulu → then APK modifikasi
3. Amati response Android:
   - `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
   - Signature mismatch warning
   - Play Protect detection

---

## Struktur Lab-2

```
Lab-2/
├── README.md           ← Dokumen ini (arsitektur & alur)
├── ALUR.md             ← Step-by-step eksekusi lab
├── SKILL-APK.md        ← Skill untuk APK injection
├── docs/
│   ├── GOL-APK.md      ← Hasil analisis GOL APK
│   ├── GabutPoC.md     ← Dokumentasi GabutPoC
│   └── injection.md    ← Teknik penanaman APK
├── skills/
│   ├── apk-inject.yaml ← Automation skill untuk inject
│   └── sign-check.yaml ← Automation skill untuk cek signature
└── tools/
    ├── extract-apk.ps1  ← Script extract XAPK → APK
    └── sign-check.ps1   ← Script verifikasi signature
```

## Referensi

| Komponen | Path |
|----------|------|
| GabutPoC source | `../Lab-1/android/` |
| Server infra | `../Lab-1/docker/` |
| Dashboard | `../Lab-1/web-viewer/` |
