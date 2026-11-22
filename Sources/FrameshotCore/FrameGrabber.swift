import Foundation

/// Mengambil satu frame: untuk pratinjau, tombol "Ambil frame ini", dan perkiraan ukuran file.
public enum FrameGrabber {
    /// JPEG kecil untuk pratinjau.
    public static func preview(video: URL, at time: Double, width: Int, toolchain: FFmpegToolchain) throws -> Data {
        let args = CommandBuilder.singleFrameArguments(video: video, at: time, settings: nil,
                                                       previewWidth: width, output: "pipe:1", toolchain: toolchain)
        let data = try ProcessRunner.data(toolchain.ffmpeg, args)
        if data.isEmpty, time > 0.5 {
            // Di ujung video kadang tidak ada frame setelah titik seek; mundur sedikit.
            return try preview(video: video, at: max(0, time - 0.5), width: width, toolchain: toolchain)
        }
        return data
    }

    /// Simpan frame resolusi penuh dengan format pilihan.
    public static func snapshot(video: URL, at time: Double, settings: ExtractionSettings,
                                to url: URL, toolchain: FFmpegToolchain) throws {
        if settings.format.usesImageIO {
            let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("frameshot-\(UUID().uuidString).bmp")
            defer { try? FileManager.default.removeItem(at: tmp) }
            var bmp = settings
            bmp.format = .bmp
            try snapshot(video: video, at: time, settings: bmp, to: tmp, toolchain: toolchain)
            try ImageConverter.convert(tmp, to: url, format: settings.format, quality: settings.quality)
            return
        }
        let args = CommandBuilder.singleFrameArguments(video: video, at: time, settings: settings,
                                                       previewWidth: nil, output: url.path, toolchain: toolchain)
        _ = try ProcessRunner.data(toolchain.ffmpeg, args)
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw ProcessRunner.Failure(status: 1, stderr: "Frame tidak berhasil disimpan.")
        }
    }

    /// Ukuran rata-rata satu gambar (byte), dari beberapa sampel di sepanjang rentang.
    public static func sampleFrameBytes(video: URL, start: Double, end: Double, settings: ExtractionSettings,
                                        toolchain: FFmpegToolchain) -> Double? {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("frameshot-sample-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        var sizes: [Double] = []
        let span = max(0, end - start)
        for (i, p) in [0.25, 0.5, 0.75].enumerated() {
            if Task.isCancelled { return nil }
            let url = dir.appendingPathComponent("s\(i).\(settings.format.fileExtension)")
            if (try? snapshot(video: video, at: start + span * p, settings: settings, to: url, toolchain: toolchain)) != nil,
               let sz = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize {
                sizes.append(Double(sz))
            }
        }
        guard !sizes.isEmpty else { return nil }
        return sizes.reduce(0, +) / Double(sizes.count)
    }
}
