# GabutPoC — WebRTC Android Screen Record → SRS → Browser

**GabutPoC**: aplikasi Android merekam layar dan mem-*publish* video secara
real-time ke media server **SRS (OSSRS)** lewat **WHIP**. Video ditonton di
browser lewat **WHEP**.

```
Android app  ──WHIP (HTTP)──►  SRS server  ──WHEP (HTTP)──►  Browser
(MediaProjection)              (WebRTC media)                (<video>)
```

Signaling (SDP offer/answer) berjalan lewat HTTP (WHIP/WHEP), jadi **tidak perlu
server WebSocket terpisah** — SRS yang menangani.

## Struktur

| Folder | Isi |
|--------|-----|
| `docker/` | Deployment SRS (`docker-compose.yml`, `srs.conf`) |
| `web-viewer/` | Viewer WHEP satu stream (`index.html`) + **dashboard multi-device** (`dashboard.html`) |
| `android/` | Aplikasi Android (Kotlin, Android Studio) |

## Multi-device & dashboard

Tiap perangkat publish dengan **nama stream = ID perangkat** yang unik, jadi banyak
device bisa terhubung bersamaan. Di app, field **ID Perangkat** terisi otomatis
(`emulator-xxxx`, atau `<model>-xxxx` di HP fisik dari `Build.MODEL` + `ANDROID_ID`)
dan URL WHIP dibangun dari **Server** + **ID Perangkat**:
`http://<server>/rtc/v1/whip/?app=live&stream=<device-id>`.

Buka **`dashboard.html`** — satu dashboard dengan **sidebar berisi dua menu**:

- **Stream** — grid semua perangkat yang publish (polling SRS `/api/v1/streams/`
  tiap 2 detik): ID perangkat, status, resolusi, codec, bitrate, penonton, uptime.
  Klik kartu untuk menonton stream (WHEP).
- **Remote** — pilih satu perangkat, lihat layarnya, dan **kendalikan** lewat
  relay: klik video = tap, tombol Back/Home/Recents/Notif, kotak teks. Selector
  menandai perangkat yang "kontrol siap" (terhubung ke relay) vs "stream saja".

Server SRS, auto-refresh, dan status relay ada di bagian bawah sidebar.

## Konfigurasi alamat server (satu tempat)

Alamat SRS diatur di **satu file**: `android/gradle.properties` →
```properties
SRS_HOST=192.168.100.22:1985
```
Saat build (`./gradlew assembleDebug/Release`), nilai ini:
- di-inject ke APK sebagai default field **Server** (`resValue` → `@string/default_server`), dan
- di-generate ke **`web-viewer/config.js`** yang dibaca dashboard & viewer.

Jadi kalau IP host berubah (mis. karena DHCP), cukup ubah `SRS_HOST` lalu build
ulang — APK, dashboard, dan viewer ikut terupdate. `CANDIDATE` saat menjalankan
SRS harus IP yang sama. (Semua field tetap bisa diubah manual saat runtime.)

---

## 1. Deploy semuanya (server + APK) — `deploy.sh`

Satu perintah, IP server sebagai input. Skrip: set `SRS_HOST` (gradle.properties)
& `web-viewer/config.js`, jalankan container, **dan build APK dengan IP itu**.

```bash
./deploy.sh                 # akan menanyakan IP server
./deploy.sh 192.168.100.22  # atau langsung
SKIP_APK=1 ./deploy.sh 192.168.100.22   # deploy server saja (tanpa build APK)
./deploy.sh down            # hentikan semua
```

Hasil: 3 container jalan + `GabutPoC-universal-debug.apk` (IP ter-*bake* di
default field **Server** app). Build APK butuh **JDK 17** + **Android SDK**
(otomatis dideteksi via `JAVA_HOME`/`ANDROID_HOME`/`local.properties`/`/usr/libexec/java_home`;
kalau tak ada, langkah APK dilewati dengan pesan jelas dan server tetap ter-deploy).
`./gradlew` (wrapper) sudah disertakan, jadi tak perlu install Gradle.

Container yang dijalankan (`docker/docker-compose.yml`):

| Service | Container | Port |
|---------|-----------|------|
| SRS (WebRTC) | `srs-webrtc` | 1935, **1985** (API+WHIP/WHEP), 8080, **8000/udp** |
| Control relay | `control-relay` | **8092** (WebSocket) |
| Dashboard (nginx) | `gabutpoc-dashboard` | **8088** → `http://<ip>:8088/dashboard.html` |

`CANDIDATE` (IP yang diiklankan SRS untuk ICE) di-set otomatis ke IP yang kamu
masukkan. Cari IP LAN: `ipconfig getifaddr en0` (macOS) / `hostname -I` (Linux).

Manual (tanpa skrip):
```bash
cd docker && CANDIDATE=192.168.100.22 docker compose up -d --build
```

Port yang dibuka:
- `1985/tcp` — HTTP API + endpoint WHIP/WHEP
- `8080/tcp` — HTTP server (player bawaan SRS)
- `8000/udp` — media WebRTC
- `1935/tcp` — RTMP (opsional)

Endpoint yang dipakai:
- Publish (Android): `http://<IP>:1985/rtc/v1/whip/?app=live&stream=screen`
- Play (browser): `http://<IP>:1985/rtc/v1/whep/?app=live&stream=screen`

## 2. Buka viewer di browser

Buka `web-viewer/index.html` (langsung atau via server statis apa pun).
Ubah URL WHEP ke IP server SRS-mu, lalu klik **Play**. Sebelum ada publisher,
statusnya menunggu media — itu normal.

> Catatan: browser modern butuh **HTTPS** (secure context) untuk WebRTC kecuali
> di `localhost`. Untuk device/host berbeda, taruh viewer + SRS di belakang HTTPS
> (mis. reverse proxy TLS) atau akses lewat `localhost`.

## 3. Build & jalankan aplikasi Android

1. Buka folder `android/` di **Android Studio** (Giraffe+), biarkan Gradle sync.
2. Pastikan `SRS_HOST` di `android/gradle.properties` sesuai IP server (lihat
   "Konfigurasi alamat server" di atas). Tidak ada lagi input IP/ID di app —
   server sudah hardcode dan ID perangkat terbentuk otomatis.
3. Run ke device/emulator. **Siaran mulai otomatis** saat app dibuka: Android
   menampilkan dialog izin "rekam layar" (wajib, tak bisa dilewati) — tekan
   **Start now** sekali, lalu status berubah jadi **LIVE**.
4. Buka `dashboard.html` atau `index.html` di browser → layar perangkat tampil.
   Tombol **Hentikan siaran** untuk berhenti.

### Dua menu (install & jalan tanpa root)

App punya **dua tombol**, memakai flow izin Android standar — jadi APK bisa
di-install & jalan di perangkat apa pun tanpa root:

1. **Aktifkan Stream** — memicu dialog izin rekam layar (dipaksa seluruh layar),
   lalu menyiarkan ke server yang sudah di-set (`SRS_HOST`). Tombol berubah jadi
   **Hentikan Stream** saat live. Status LIVE tampil di kartu (hijau).
2. **Aktifkan Full Control** — membuka halaman **Accessibility settings**; user
   meng-ON-kan "GabutPoC" untuk mengaktifkan kontrol jarak jauh. Setelah aktif,
   tombol jadi **Full Control Aktif ✓** dan baris **Kontrol** menampilkan URL
   `http://<ip>:8091` (hijau).

Diverifikasi di emulator: tombol Stream → dialog izin → **LIVE**; tombol Full
Control → Accessibility settings → service **On** → kontrol connected.

> **Install**: gunakan `GabutPoC-universal-debug.apk` (berisi semua ABI) bila
> tidak yakin arsitektur perangkat — mencegah `INSTALL_FAILED_NO_MATCHING_ABIS`.
> Untuk ukuran minimum, pakai `GabutPoC-arm64-v8a-*.apk` (mayoritas HP modern).

### Ketahanan (auto-recovery)

- **Auto-start** saat app dibuka (izin layar tetap wajib disetujui sekali).
- **Auto-retry koneksi** — jika server belum siap / gangguan sesaat, publish
  WHIP dicoba ulang hingga 6× (jeda 2 dtk). Contoh teruji: SRS mati saat mulai →
  tersambung otomatis pada percobaan ke-3 begitu SRS hidup, tanpa aksi pengguna.
- **Auto-retry izin** — jika dialog izin tak sengaja dibatalkan, diminta ulang
  sekali otomatis.
- **Kontrol dari notifikasi** — tombol **Hentikan** di notifikasi foreground
  untuk menghentikan siaran tanpa membuka app.

### Kontrol jarak jauh via relay di server (dashboard → server → kiosk)

Agar operator bisa **memperbarui konten kiosk dari jauh lewat dashboard**, kontrol
diarahkan **melalui server** (bukan menjangkau tiap kiosk langsung):

```
Dashboard ──ws──► Control Relay (server :8092) ◄──ws── Kiosk (KioskControlService)
                   (docker/relay, di host SRS)          konek keluar, jalankan perintah
```

- **Relay** (`docker/relay`, Node + `ws`) berjalan di host SRS pada **port 8092**.
  Kiosk & dashboard sama-sama **konek keluar** ke sana; relay meneruskan perintah
  `dashboard → device` berdasarkan **device id**. Jadi "IP kontrol = IP server"
  (`SRS_HOST`), dan kiosk tak perlu dijangkau langsung (aman untuk NAT/firewall).
- **Kiosk**: `KioskControlService` (AccessibilityService, `canPerformGestures`)
  konek ke `ws://<server>:8092/?role=device&id=<deviceId>` dan menjalankan perintah
  sebagai gesture. Alur mengaktifkan: tombol **Aktifkan Full Control** → dialog
  request in-app → **Pengaturan Aksesibilitas** → tap **GabutPoC** → nyalakan
  toggle **"Use GabutPoC"** → **sistem menampilkan konfirmasi "Allow … full control
  of your device?"** → **Allow**. (Tidak ada API *request* runtime untuk
  aksesibilitas, tapi sistem memunculkan konfirmasi Allow/Deny saat toggle
  dinyalakan. Untuk auto-enable tanpa interaksi: root / device-owner / MDM.)
- **Dashboard → menu Remote**: pada video kiosk — **klik = tap**, **tahan =
  long-press**, **seret = geser/scroll**. Tombol **Back/Home/Recents/Notif**,
  **⌨ Keyboard** (ketik langsung ke field fokus), **Buka app** (isi package),
  **✕ Tutup app**, dan kotak set-teks.

Perintah relay (JSON `{ to:<deviceId>, action, … }`):
`tap` `longpress` (`x,y`), `swipe` (`x1,y1,x2,y2,ms`), `key` (`name`),
`typechar` (`value`) / `backspace` / `text` (`value`), `launch` (`pkg`),
`close`, `open` (`url`).

Teruji end-to-end: dari dashboard di browser → relay → kiosk membuka Chrome/Settings,
menekan tombol global, dan mengirim gesture. (Ada juga HTTP kontrol lokal di port
8091 pada perangkat untuk akses langsung LAN, opsional.)

> ⚠️ **Keamanan**: relay & kontrol lokal default **tanpa autentikasi** (mode LAN
> terpercaya). Untuk produksi, tambahkan token/otentikasi di relay & set `TOKEN`
> di `KioskControlService.kt`, dan batasi ke jaringan tepercaya. AccessibilityService
> memberi kontrol penuh perangkat — pasang hanya di kiosk milik sendiri.

### Ukuran APK & build teroptimasi

APK didominasi native lib WebRTC (`libjingle_peerconnection_so.so`, ~10 MB/ABI).
Optimasi yang sudah diterapkan di `app/build.gradle.kts`:

- **ABI splits** — satu APK per arsitektur, bukan keempatnya sekaligus.
- **R8 minify + `shrinkResources`** pada build release (aman untuk WebRTC berkat
  `-keep class org.webrtc.**` di `proguard-rules.pro`).
- Dependency tak terpakai dibuang (constraintlayout, lifecycle).

Ukuran hasil (dari ~48 MB universal):

| ABI | debug | release (minify) |
|-----|-------|------------------|
| arm64-v8a (mayoritas HP) | 17 MB | **13 MB** |
| armeabi-v7a (HP 32-bit lama) | 12 MB | **8.2 MB** |
| x86_64 (emulator Intel) | 18 MB | 14 MB |

Build & pilih APK yang cocok dengan target:
```bash
./gradlew assembleRelease          # atau assembleDebug
# output: app/build/outputs/apk/release/app-<abi>-release.apk
```
Pasang `arm64-v8a` untuk HP modern, `armeabi-v7a` untuk HP lama, dan
**`arm64-v8a` juga untuk emulator di Apple Silicon** (emulatornya arm64, bukan x86).
Untuk distribusi Play Store, pakai **`bundleRelease`** (AAB) agar Play mengirim
hanya ABI yang dibutuhkan tiap perangkat secara otomatis.

### Detail teknis sisi Android

- `MediaProjection` menangkap layar; `ScreenCapturerAndroid` (WebRTC) jadi source video.
- Berjalan di **foreground service** (`foregroundServiceType=mediaProjection`) —
  wajib untuk screen capture di Android 10+ dan Android 14.
- WHIP non-trickle: offer dikirim setelah **ICE gathering COMPLETE**
  (lihat `ScreenCaptureService.sendOffer`).
- Track video di-set **send-only** (publish saja, tidak menerima).
- **Android 14 (API 34)**: dialog izin dipaksa ke **seluruh layar** via
  `MediaProjectionConfig.createConfigForDefaultDisplay()` — opsi "bagikan satu
  aplikasi" tidak ditampilkan.
- Library: `io.getstream:stream-webrtc-android` (API `org.webrtc`) + OkHttp untuk WHIP.
- Hanya video (tanpa audio), sesuai permintaan.

## Troubleshooting

| Gejala | Penyebab / solusi |
|--------|-------------------|
| SRS log `code=5018 ... no found valid H.264 payload type` | Publisher tidak menawarkan H.264. **Android emulator tidak punya encoder H.264 yang dipakai WebRTC** (hanya VP8/VP9/AV1) — SRS mewajibkan H.264. **Uji dengan HP fisik** (punya encoder H.264 hardware). |
| Video hitam / tidak connect | `CANDIDATE` salah. Set ke IP yang dijangkau klien, restart SRS. |
| `cleartext HTTP not permitted` | Sudah di-handle via `usesCleartextTraffic=true`. Untuk produksi pakai HTTPS. |
| Browser tak mau `getUserMedia`/WebRTC | Bukan secure context. Akses via `localhost` atau HTTPS. |
| Firewall | Pastikan UDP 8000 dan TCP 1985 terbuka. |
| WHIP `EOFException: \n not found: limit=0` | SRS menutup koneksi karena menolak SDP (biasanya codec, lihat baris pertama tabel ini). Cek `docker logs srs-webrtc`. |
| Emulator: ping ke IP host hang | Normal — ICMP diblok SLIRP. TCP/UDP tetap jalan (signaling & media OK). |

## Catatan penting: codec H.264

SRS mewajibkan **H.264** untuk publish WebRTC. HP Android fisik menyediakan encoder
H.264 hardware. Android **emulator** tidak punya encoder H.264 hardware, dan WebRTC
secara default mengabaikan codec software (`OMX.google`/`c2.android`), sehingga
offer-nya hanya VP8/VP9/AV1 dan ditolak SRS.

**Solusi (sudah diterapkan):** [`ForcedH264EncoderFactory`](android/app/src/main/java/org/webrtc/ForcedH264EncoderFactory.kt)
— encoder factory kustom (di package `org.webrtc`) yang memaksa H.264 lewat
`c2.android.avc.encoder` dengan mode byte-buffer + keyframe periodik. Ini membuat
**emulator pun bisa publish**. Sudah diverifikasi berjalan ujung-ke-ujung (layar
emulator tampil di browser). Catatan: decode H.264 software di browser lambat mulai
(~15-20 detik pertama karena menunggu keyframe).

Viewer WHEP menawarkan **video-only** (tanpa m-line audio); kalau menawarkan audio
untuk stream video-only, SRS menyusun ulang m-line dan browser menolak answer-nya.

## Keamanan

Konfigurasi ini untuk **development/LAN**. Untuk produksi: aktifkan HTTPS/WSS,
autentikasi WHIP/WHEP (SRS mendukung token/hook), dan batasi CORS.
