import Foundation

/// Satu pekerjaan ekstraksi untuk satu video.
public struct ExtractionJob: Sendable {
    public var videoURL: URL
    public var info: VideoInfo
    public var start: Double
    public var end: Double
    public var settings: ExtractionSettings

    public init(videoURL: URL, info: VideoInfo, start: Double, end: Double, settings: ExtractionSettings) {
        self.videoURL = videoURL
        self.info = info
        self.start = max(0, start)
        self.end = min(max(end, self.start), info.duration > 0 ? info.duration : end)
        self.settings = settings
    }

    public var rangeDuration: Double { max(0, end - start) }
    public var isFullRange: Bool { start <= 0.0005 && (info.duration <= 0 || end >= info.duration - 0.0005) }

    public var prefix: String {
        let p = settings.customPrefix.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = p.isEmpty ? videoURL.deletingPathExtension().lastPathComponent : p
        return sanitizeFilename(base)
    }

    public var expectedFrames: Int? { settings.estimatedFrames(duration: rangeDuration, fps: info.fps) }
}

public func sanitizeFilename(_ s: String) -> String {
    let bad = CharacterSet(charactersIn: "/\\:?*\"<>|").union(.controlCharacters)
    let cleaned = s.unicodeScalars.map { bad.contains($0) ? "_" : String($0) }.joined()
    let trimmed = cleaned.trimmingCharacters(in: .whitespaces)
    return trimmed.isEmpty ? "frame" : trimmed
}

public enum CommandBuilder {
    /// Nama file sementara untuk mode timestamp (ffmpeg menulis pts milidetik, lalu kita ganti nama).
    public static let tempMarker = ".frameshot-tmp-"

    /// Susun argumen ffmpeg. `outputPattern` berisi %d / %06d.
    public static func arguments(for job: ExtractionJob, toolchain: FFmpegToolchain,
                                 outputPattern: String, progress: Bool = true) -> [String] {
        let s = job.settings
        var a = ["-hide_banner", "-nostdin", "-y", "-loglevel", "error"]
        if progress { a += ["-progress", "pipe:1", "-nostats"] }
        // Subtitle burn butuh decode software agar filter teks bisa bekerja dengan baik.
        if s.hardwareDecode && toolchain.supportsVideoToolbox && !(s.burnSubtitles && toolchain.supportsSubtitles) {
            a += ["-hwaccel", "videotoolbox"]
        }
        if job.start > 0.0005 { a += ["-ss", seconds(job.start)] }
        a += ["-i", job.videoURL.path]
        if !job.isFullRange { a += ["-t", seconds(job.rangeDuration)] }
        a += ["-map", "0:v:0", "-an", "-sn", "-dn"]

        let filters = filterChain(for: job, toolchain: toolchain)
        if !filters.isEmpty { a += ["-vf", filters.joined(separator: ",")] }

        let passthrough = s.mode == .allFrames && !s.skipDuplicates
        if toolchain.supportsFpsMode {
            a += ["-fps_mode", passthrough ? "passthrough" : "vfr"]
        } else {
            a += ["-vsync", passthrough ? "passthrough" : "vfr"]
        }
        if s.mode == .fixedCount { a += ["-frames:v", String(max(1, s.fixedCount))] }

        a += codecArguments(s.format, quality: s.quality)

        if s.naming == .timestamp {
            a += ["-enc_time_base", "1:1000", "-frame_pts", "1"]
        } else {
            a += ["-start_number", String(s.startNumber)]
        }
        a += ["-f", "image2", outputPattern]
        return a
    }

    public static func filterChain(for job: ExtractionJob, toolchain: FFmpegToolchain) -> [String] {
        let s = job.settings
        var f: [String] = []

        if s.burnSubtitles && toolchain.supportsSubtitles && job.info.subtitleStreams > 0 {
            // Setelah -ss, timestamp dimulai dari 0. Geser balik agar waktu subtitle cocok.
            let off = seconds(job.start)
            if job.start > 0.0005 { f.append("setpts=PTS+\(off)/TB") }
            f.append("subtitles=filename=\(escapeFilterValue(job.videoURL.path)):si=0")
            if job.start > 0.0005 { f.append("setpts=PTS-\(off)/TB") }
        }

        if s.skipDuplicates { f.append("mpdecimate") }

        switch s.mode {
        case .allFrames:
            break
        case .everyNFrames:
            f.append("select='not(mod(n,\(max(1, s.everyN))))'")
        case .interval:
            f.append(intervalSelect(max(0.001, s.intervalSeconds)))
        case .fixedCount:
            let n = max(1, s.fixedCount)
            let step = job.rangeDuration / Double(n)
            if step > job.info.frameDuration {
                f.append(intervalSelect(step))
            } // kalau jumlah ≥ jumlah frame, ambil semua frame lalu dibatasi -frames:v
        case .sceneChange:
            let t = min(0.95, max(0.01, s.sceneThreshold))
            f.append("select='eq(n,0)+gt(scene,\(String(format: "%.3f", t)))'")
        }

        if let scale = scaleFilter(s) { f.append(scale) }
        return f
    }

    /// Pilih frame pertama di tiap "ember" waktu, tanpa drift.
    static func intervalSelect(_ step: Double) -> String {
        let st = seconds(step)
        return "select='isnan(prev_selected_t)+gt(floor(t/\(st)),floor(prev_selected_t/\(st)))'"
    }

    static func scaleFilter(_ s: ExtractionSettings) -> String? {
        switch s.resolution {
        case .original: return nil
        case .custom:
            let w = max(16, s.customWidth)
            return "scale='min(\(w),iw)':-2:flags=lanczos"
        default:
            guard let h = s.resolution.height else { return nil }
            return "scale=-2:'min(\(h),ih)':flags=lanczos"
        }
    }

    public static func codecArguments(_ format: ImageFormat, quality: Int) -> [String] {
        let q = min(100, max(1, quality))
        switch format {
        case .jpg:
            // ffmpeg mjpeg: -q:v 1 (terbaik) … 31 (terburuk)
            let qv = Int((31.0 - Double(q - 1) * 30.0 / 99.0).rounded())
            return ["-c:v", "mjpeg", "-q:v", String(max(1, min(31, qv))), "-pix_fmt", "yuvj420p"]
        case .png:
            return ["-c:v", "png", "-pix_fmt", "rgb24"]
        case .webp:
            return ["-c:v", "libwebp", "-quality", String(q), "-pix_fmt", "yuv420p"]
        case .tiff:
            return ["-c:v", "tiff", "-pix_fmt", "rgb24", "-compression_algo", "lzw"]
        case .bmp, .heic, .avif:
            // HEIC/AVIF: ffmpeg menulis BMP sementara, lalu dikonversi ImageIO.
            return ["-c:v", "bmp", "-pix_fmt", "bgr24"]
        }
    }

    /// Argumen untuk mengambil satu frame (pratinjau/snapshot/sampel ukuran).
    public static func singleFrameArguments(video: URL, at time: Double, settings: ExtractionSettings?,
                                            previewWidth: Int?, output: String,
                                            toolchain: FFmpegToolchain) -> [String] {
        var a = ["-hide_banner", "-nostdin", "-y", "-loglevel", "error"]
        if time > 0 { a += ["-ss", seconds(time)] }
        a += ["-i", video.path, "-map", "0:v:0", "-an", "-sn", "-dn", "-frames:v", "1"]
        if let pw = previewWidth {
            a += ["-vf", "scale='min(\(pw),iw)':-2:flags=bilinear", "-c:v", "mjpeg", "-q:v", "4",
                  "-pix_fmt", "yuvj420p", "-f", "image2pipe", output]
        } else if let s = settings {
            if let sc = scaleFilter(s) { a += ["-vf", sc] }
            a += codecArguments(s.format, quality: s.quality)
            a += ["-f", "image2", "-update", "1", output]
        }
        return a
    }

    static func seconds(_ v: Double) -> String {
        var s = String(format: "%.6f", v)
        while s.hasSuffix("0") { s.removeLast() }
        if s.hasSuffix(".") { s.removeLast() }
        return s
    }

    /// Escape nilai untuk filtergraph ffmpeg (dua tingkat: opsi filter lalu graph).
    static func escapeFilterValue(_ v: String) -> String {
        var level1 = ""
        for ch in v {
            if ch == "\\" || ch == ":" || ch == "'" { level1.append("\\") }
            level1.append(ch)
        }
        var level2 = ""
        for ch in level1 {
            if "\\'[],;".contains(ch) { level2.append("\\") }
            level2.append(ch)
        }
        return level2
    }
}
