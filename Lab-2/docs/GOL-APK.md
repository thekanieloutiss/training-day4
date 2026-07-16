# Analisis APK Gratifikasi OnLine (GOL) — com.kpk.gol

## Informasi APK

| Field | Value |
|-------|-------|
| **Package** | `com.kpk.gol` |
| **Version** | 2.1.1 (versionCode: 20260106) |
| **Developer** | Komisi Pemberantasan Korupsi (KPK) |
| **Size** | ~12.8 MB (XAPK) / ~11 MB (base APK) |
| **Min SDK** | 23 (Android 6.0) |
| **Target SDK** | 35 (Android 15) |
| **Type** | Hybrid (Ionic/Cordova + Firebase) |
| **Signing** | Production key (KPK) |

## Struktur APK (Split APK)

```
com.kpk.gol.apk          ← Base APK (main app)
config.arm64_v8a.apk     ← Native libs (armeabi-v7a)
config.xxxhdpi.apk       ← Resources for high DPI
config.en.apk             ← English locale
config.in.apk             ← Indonesian locale
...
```

## Komponen Native (Java)

### MainActivity.java
```java
package com.kpk.gol;

import org.apache.cordova.CordovaActivity;

public class MainActivity extends CordovaActivity {
    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        // Optional: hide from recent apps if launched via background
        Bundle extras = getIntent().getExtras();
        if (extras != null && extras.getBoolean("cdvStartInBackground", false)) {
            moveTaskToBack(true);
        }
        loadUrl(this.launchUrl);  // Load index.html from assets/www/
    }
}
```

### AndroidManifest.xml — Permission & Features

| Permission | Purpose |
|------------|---------|
| `INTERNET` | API calls to KPK server |
| `ACCESS_NETWORK_STATE` | Network status check |
| `WAKE_LOCK` | Keep screen on |
| `READ_EXTERNAL_STORAGE` | Upload attachments |
| `WRITE_EXTERNAL_STORAGE` | Download attachments |
| `POST_NOTIFICATIONS` | Firebase push notifications |
| `CAMERA` | Photo attachment (optional feature) |

### Deep Links

```
https://*.kpk.go.id/activation/...
https://gol.kpk.go.id/activation/...
```

## Komponen Web (Ionic/Cordova)

### assets/www/ structure
```
index.html              ← Entry point, Quasar/Vue.js SPA
css/app.*.css           ← Minified styles
js/
├── vendor.js           ← Vendor libs (Vue, Quasar, etc.)
├── app.js              ← Application code (minified)
├── chunk-common.js     ← Common chunks
└── [0-36].js           ← Lazy-loaded route chunks
plugins/                ← Cordova plugin JS
statics/                ← Static assets (images, SVGs)
```

### Cordova Plugins Used

| Plugin | Purpose |
|--------|---------|
| `cordova-plugin-camera` | Take photo / pick from gallery |
| `cordova-plugin-file` | File system access |
| `cordova-plugin-file-opener2` | Open files |
| `cordova-plugin-deeplinks` | Deep link handling |
| `cordova-plugin-device` | Device info |
| `cordova-plugin-firebasex` | Firebase services |
| `cordova-plugin-inappbrowser` | In-app browser |
| `cordova-plugin-ionic-webview` | Enhanced WebView |
| `cordova-plugin-splashscreen` | Splash screen |
| `cordova-plugin-app-version` | App version info |

### Firebase Services

| Service | Usage |
|---------|-------|
| Firebase Authentication | Login (email/password, Google) |
| Cloud Firestore | Database |
| Firebase Cloud Messaging | Push notifications |
| Firebase Analytics | Usage analytics |
| Firebase Crashlytics | Error reporting |
| Firebase Remote Config | Feature flags |
| Firebase In-App Messaging | In-app promotions |
| Firebase Performance | Performance monitoring |

## Alur Aplikasi

```
[Launch]
    │
    ├── SplashScreen (Cordova)
    │
    ├── MainActivity.onCreate()
    │   ├── super.onCreate()
    │   ├── loadUrl(launchUrl) → index.html
    │   └── Cordova WebView loads:
    │       ├── cordova.js
    │       ├── vendor.js (Vue, Quasar)
    │       └── app.js (Ionic app)
    │
    ├──[Firebase Auth Check]
    │   ├── Not logged in → LoginPage
    │   ├── Logged in → Dashboard
    │   └── Deep link activation → ActivationPage
    │
    ├──[Dashboard]
    │   ├── Daftar laporan gratifikasi
    │   ├── Buat laporan baru
    │   └── Status laporan
    │
    ├──[Buat Laporan]
    │   ├── Form: jenis gratifikasi, tanggal, nilai, dll
    │   ├── Lampiran: foto (camera/gallery), dokumen
    │   ├── Geolocation (optional)
    │   └── Submit → Firestore
    │
    └──[Notifications]
        └── FCM → update status laporan
```

## Titik Injeksi Potensial

| Titik | Metode | Risiko Deteksi |
|-------|--------|----------------|
| `AndroidManifest.xml` | Tambah permission & service | Rendah (tidak merubah behavior) |
| `MainActivity.onCreate()` | Inject smali untuk start service | Sedang (perubahan alur) |
| `assets/www/index.html` | Tambah script injection | Rendah (tidak deteksi sign) |
| `classes.dex` | Merge GabutPoC DEX | Tinggi (change signature) |

## Signature & Keamanan

| Aspek | Detail |
|-------|--------|
| Signature Algorithm | RSA 2048-bit |
| Signing Scheme | APK Signature Scheme v2 + v3 |
| Play Integrity | Lolos (official Play Store) |
| SafetyNet | Lolos (signed by KPK) |
| Play Protect | Tidak ada flag (app resmi) |
