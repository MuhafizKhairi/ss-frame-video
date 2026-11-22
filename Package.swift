// swift-tools-version:5.10
import PackageDescription

let package = Package(
    name: "Frameshot",
    platforms: [.macOS(.v14)],
    products: [
        .executable(name: "Frameshot", targets: ["Frameshot"]),
    ],
    targets: [
        // Logika inti: membaca info video, menyusun perintah ffmpeg, menjalankan ekstraksi.
        .target(name: "FrameshotCore"),
        // Aplikasi SwiftUI.
        .executableTarget(name: "Frameshot", dependencies: ["FrameshotCore"]),
        // Alat uji kecil untuk mengecek ekstraksi dari terminal (tidak dibutuhkan pengguna).
        .executableTarget(name: "frameshot-check", dependencies: ["FrameshotCore"]),
    ]
)
