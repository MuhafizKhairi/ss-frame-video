# Frameshot

Aplikasi macOS untuk men-screenshot video per frame. Cocok untuk anime, film, atau video apa pun.

## Cara menjalankan

```bash
./build.sh            # membuat Frameshot.app di folder ini
./build.sh --install  # sekaligus menyalin ke /Applications
```

Lalu buka **Frameshot.app** (klik dua kali). Butuh **ffmpeg** (`brew install ffmpeg`). Kalau ffmpeg belum ada, aplikasi akan menampilkan petunjuknya.

> Pertama kali dibuka, macOS mungkin memperingatkan bahwa aplikasi ini dari "pengembang tak dikenal" (karena dibangun sendiri). Klik kanan → **Buka** → **Buka**.

## Fitur

**Memilih video**
- Klik **Pilih Video**, **Pilih Folder** (semua video di folder itu), atau seret file/folder ke jendela.
- Bisa banyak video sekaligus (antrean). Centang/hapus centang "Ikut diekstrak" per video.
- Mendukung MP4, MKV, MOV, WEBM, AVI, TS, dan format lain yang bisa dibaca ffmpeg.

**Mode screenshot**
| Mode | Fungsinya |
|---|---|
| Semua Frame | Setiap frame dari awal sampai akhir (atau dalam rentang) |
| Tiap N Frame | 1 gambar setiap N frame |
| Interval Waktu | 1 gambar setiap X detik |
| Jumlah Tetap | Tentukan jumlah gambar, dibagi rata sepanjang video |
| Ganti Adegan | Otomatis ambil frame saat adegan berganti (atur sensitivitas) |

**Rentang waktu ("dari menit ke menit")** — pilih *Seluruh video* atau *Pilih rentang*, lalu ketik waktunya (`12:30`, `1:02:03.500`), seret penanda di timeline, atau pakai tombol **Set Awal / Set Akhir** (tombol pintas `I` / `O`).

**Format gambar** — JPG, PNG, HEIC, AVIF, TIFF, BMP (+ WebP jika ffmpeg kamu punya libwebp), dengan pengaturan kualitas.

**Lainnya**
- Pratinjau frame dengan navigasi per frame (`←` `→`, `Shift` untuk ±1 detik).
- **Ambil frame ini** (`⌘S`): simpan satu frame yang sedang dilihat, resolusi penuh.
- Resolusi keluaran: asli, 4K, 1440p, 1080p, 720p, 480p, 360p, atau lebar kustom.
- Nama file: nomor urut (`Episode 01_000001.jpg`) atau timestamp (`Episode 01_00-12-30-500.jpg`), dengan awalan nama sendiri.
- Folder output bebas dipilih, opsi subfolder otomatis per video (tidak pernah menimpa hasil lama).
- Perkiraan jumlah gambar, ukuran total, dan sisa ruang disk sebelum mulai.
- Progres dengan kecepatan & sisa waktu, bisa dibatalkan kapan saja.
- Lewati frame duplikat (berguna untuk anime yang sering mengulang frame).
- Akselerasi hardware Apple (VideoToolbox).
- Tempel subtitle ke gambar (butuh ffmpeg dengan libass; build Homebrew standar tidak menyertakannya).
- Mode terang & gelap mengikuti sistem. Pengaturan terakhir diingat.

## Struktur kode

```
Sources/FrameshotCore/   logika inti: baca info video, susun perintah ffmpeg, jalankan & konversi
Sources/Frameshot/       antarmuka SwiftUI
Sources/frameshot-check/ alat uji dari terminal (swift run frameshot-check <video> <folder> [mode] [format])
scripts/make-icon.swift  pembuat ikon aplikasi
build.sh                 build & susun Frameshot.app
android/                 versi Android (lihat di bawah)
```

---

# Frameshot untuk Android

Fitur dan teksnya sama dengan versi macOS, disesuaikan untuk HP & tablet. Tidak butuh ffmpeg: video
di-decode oleh decoder bawaan perangkat (hardware) lewat MediaCodec, lalu frame diolah di GPU.

## Cara build & pasang

Butuh Android SDK (sudah ada kalau Android Studio terpasang). Dari folder `android/`:

```bash
./gradlew assembleRelease      # APK siap pasang: app/build/outputs/apk/release/app-release.apk
./gradlew installDebug         # langsung pasang ke HP yang tersambung (USB debugging aktif)
```

Atau buka folder `android/` di Android Studio lalu klik ▶ Run. Minimal Android 10.

> APK release ditandatangani dengan kunci debug supaya bisa langsung dipasang. Untuk Play Store,
> ganti dengan kunci rilis sendiri di `app/build.gradle.kts`.

## Bedanya dengan versi macOS

| | macOS | Android |
|---|---|---|
| Menambah video | Pilih Video / Folder, seret ke jendela | Pilih Video / Folder, **Bagikan** atau **Buka dengan** Frameshot dari Galeri/File |
| Lokasi hasil bawaan | Folder yang sama dengan video | **Galeri** (`Pictures/Frameshot`), atau folder pilihan sendiri |
| Format video | Semua yang bisa dibaca ffmpeg | Yang didukung perangkat: MP4, MKV, MOV, WEBM, 3GP, TS (AVI/FLV/WMV umumnya tidak) |
| HEIC / AVIF | Lewat ImageIO macOS | Lewat encoder perangkat; tombolnya nonaktif jika HP tidak punya encodernya (AVIF butuh Android 14+) |
| TIFF | Kompresi LZW | Tanpa kompresi |
| Tempel subtitle | Butuh ffmpeg + libass | Belum tersedia |
| Proses di latar | — | Tetap jalan saat aplikasi ditinggal, progres tampil di notifikasi |
| Putar video | — | Tombol ▶ di pratinjau (atau ketuk videonya), dengan suara; saat dijeda tampil frame tepat di posisi itu |
| Pintasan keyboard | `←` `→` `I` `O` `⌘S` | Sama (`Ctrl+S`, `Ctrl+Enter`, `Spasi` = putar/jeda) jika pakai keyboard fisik |

Semua mode (Semua Frame, Tiap N Frame, Interval, Jumlah Tetap, Ganti Adegan), rentang waktu, resolusi,
penamaan (nomor urut/timestamp, tidak pernah menimpa), lewati duplikat, perkiraan jumlah & ukuran, dan
"Ambil frame ini" bekerja sama seperti di macOS. Di layar lebar (tablet/landscape) tampilannya dua kolom
seperti versi Mac.

## Struktur kode Android

```
android/app/src/main/java/local/frameshot/
  core/      logika murni (pengaturan, pemilihan frame, deteksi adegan/duplikat, penamaan) + unit test
  media/     decode MediaCodec → GPU (OpenGL), encode gambar, tulis ke Galeri / folder pilihan
  ui/        antarmuka Jetpack Compose
  AppState.kt, MainActivity.kt, ExtractionService.kt
```

Unit test: `./gradlew testDebugUnitTest`.
