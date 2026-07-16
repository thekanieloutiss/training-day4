# Alur Manual GabutPoC (APK Distribution)

> **Catatan**: Server SRS + relay + dashboard SUDAH berjalan di laptop lain.
> Hanya perlu install APK di device target.

## 1. Set IP Server di APK

Edit `android/gradle.properties`:

```properties
SRS_HOST=10.103.105.79:1985
```

Build APK:

```bash
cd android
gradlew.bat assembleDebug
```

## 2. Install APK ke Device

Via adb (USB):

```bash
adb install app\build\outputs\apk\debug\app-universal-debug.apk
```

Atau kirim file APK ke device → buka file manager → tap APK → izinkan unknown apps.

## 3. Streaming Layar

1. Buka aplikasi **GabutPoC**
2. Tap **"Aktifkan Stream"**
3. Izinkan **screen recording**
4. Izinkan **notifikasi** (Android 13+)
5. Status: **kuning** (connecting) → **hijau** (live)

## 4. Lihat Stream

Browser: `https://10.103.105.79/dashboard.html`
Klik card device yang live.

## 5. Remote Control

1. Tap **"Aktifkan Full Control"**
2. Buka Settings → Accessibility → GabutPoC → aktifkan
3. Dashboard → menu Remote → pilih device
4. Klik = tap, drag = swipe, tombol = key

## 6. Hentikan Stream

- Notifikasi → **Hentikan**
- Atau tutup aplikasi

## Port yang Digunakan

| Port | Fungsi | Protokol |
|------|--------|----------|
| 1985 | WHIP/WHEP + API | TCP |
| 8000 | WebRTC media | UDP |
| 8088 | Dashboard web | TCP |
| 8092 | WebSocket relay | TCP |
