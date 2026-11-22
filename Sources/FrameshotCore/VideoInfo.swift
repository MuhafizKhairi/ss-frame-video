import Foundation

/// Informasi video hasil ffprobe.
public struct VideoInfo: Sendable, Equatable {
    public var duration: Double          // detik
    public var width: Int                // lebar tampilan (sudah memperhitungkan rotasi)
    public var height: Int
    public var fps: Double
    public var codec: String
    public var frameCount: Int           // jumlah frame (dari container, atau perkiraan)
    public var fileSize: Int64
    public var bitRate: Int64
    public var subtitleStreams: Int
    public var isTenBit: Bool

    public var frameDuration: Double { fps > 0 ? 1 / fps : 1 / 24 }

    public var codecDisplay: String {
        switch codec.lowercased() {
        case "h264": return "H.264"
        case "hevc": return "HEVC"
        case "av1": return "AV1"
        case "vp9": return "VP9"
        case "vp8": return "VP8"
        case "mpeg4": return "MPEG-4"
        case "mpeg2video": return "MPEG-2"
        case "prores": return "ProRes"
        default: return codec.uppercased()
        }
    }

    public var fpsDisplay: String {
        let rounded = (fps * 1000).rounded() / 1000
        if rounded == rounded.rounded() { return String(format: "%.0f fps", rounded) }
        return String(format: "%.3f fps", rounded).replacingOccurrences(of: "0 fps", with: " fps")
    }
}

public enum VideoProbe {
    public static func probe(_ url: URL, toolchain: FFmpegToolchain) throws -> VideoInfo {
        let data = try ProcessRunner.data(toolchain.ffprobe, [
            "-v", "error", "-print_format", "json", "-show_format", "-show_streams", url.path,
        ])
        return try parse(data, fileURL: url)
    }

    static func parse(_ data: Data, fileURL: URL) throws -> VideoInfo {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ProbeError.unreadable
        }
        let streams = root["streams"] as? [[String: Any]] ?? []
        let format = root["format"] as? [String: Any] ?? [:]
        // Lewati stream "attached_pic" (cover art) yang sering ada di mkv/mp4.
        guard let v = streams.first(where: {
            ($0["codec_type"] as? String) == "video" &&
            ((($0["disposition"] as? [String: Any])?["attached_pic"] as? Int) ?? 0) == 0
        }) else { throw ProbeError.noVideo }

        func num(_ any: Any?) -> Double? {
            if let d = any as? Double { return d }
            if let i = any as? Int { return Double(i) }
            if let s = any as? String { return Double(s) }
            return nil
        }
        func rate(_ any: Any?) -> Double? {
            guard let s = any as? String else { return nil }
            let parts = s.split(separator: "/")
            if parts.count == 2, let a = Double(parts[0]), let b = Double(parts[1]), b != 0, a != 0 { return a / b }
            return Double(s)
        }

        var fps = rate(v["avg_frame_rate"]) ?? 0
        let rFps = rate(v["r_frame_rate"]) ?? 0
        if fps <= 0 || fps > 1000 { fps = rFps }
        if fps <= 0 || fps > 1000 { fps = 24 }

        var duration = num(format["duration"]) ?? num(v["duration"]) ?? 0
        if duration <= 0, let tags = v["tags"] as? [String: Any], let d = tags["DURATION"] as? String {
            duration = parseTimecode(d) ?? 0
        }

        var w = (v["width"] as? Int) ?? 0
        var h = (v["height"] as? Int) ?? 0
        var rotation = 0.0
        if let side = v["side_data_list"] as? [[String: Any]] {
            for s in side { if let r = num(s["rotation"]) { rotation = r } }
        }
        if let tags = v["tags"] as? [String: Any], let r = num(tags["rotate"]) { rotation = r }
        if Int(abs(rotation).rounded()) % 180 == 90 { swap(&w, &h) }

        var frames = Int(num(v["nb_frames"]) ?? 0)
        if frames <= 0 { frames = Int((duration * fps).rounded()) }

        let size = Int64(num(format["size"]) ?? 0)
        let fileSize = size > 0 ? size :
            ((try? FileManager.default.attributesOfItem(atPath: fileURL.path)[.size] as? Int64) ?? 0) ?? 0
        let pixFmt = (v["pix_fmt"] as? String) ?? ""

        return VideoInfo(
            duration: duration, width: w, height: h, fps: fps,
            codec: (v["codec_name"] as? String) ?? "?",
            frameCount: frames, fileSize: fileSize,
            bitRate: Int64(num(format["bit_rate"]) ?? 0),
            subtitleStreams: streams.filter { ($0["codec_type"] as? String) == "subtitle" }.count,
            isTenBit: pixFmt.contains("10") || pixFmt.contains("12")
        )
    }

    public enum ProbeError: LocalizedError {
        case unreadable, noVideo
        public var errorDescription: String? {
            switch self {
            case .unreadable: return "File tidak bisa dibaca sebagai video."
            case .noVideo: return "Tidak ada stream video di file ini."
            }
        }
    }
}

/// "01:02:03.500" / "02:03" / "75.5" → detik.
public func parseTimecode(_ text: String) -> Double? {
    let t = text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
    if t.isEmpty { return nil }
    let parts = t.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
    guard parts.count <= 3 else { return nil }
    var total = 0.0
    for p in parts {
        guard let v = Double(p), v >= 0 else { return nil }
        total = total * 60 + v
    }
    return total
}

/// detik → "1:02:03.500" atau "02:03.500".
public func formatTimecode(_ seconds: Double, millis: Bool = true, forceHours: Bool = false) -> String {
    let s = max(0, seconds)
    let totalMs = Int((s * 1000).rounded())
    let h = totalMs / 3_600_000
    let m = (totalMs / 60_000) % 60
    let sec = (totalMs / 1000) % 60
    let ms = totalMs % 1000
    var out = (h > 0 || forceHours) ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%02d:%02d", m, sec)
    if millis { out += String(format: ".%03d", ms) }
    return out
}

public func formatBytes(_ bytes: Double) -> String {
    let f = ByteCountFormatter()
    f.countStyle = .file
    f.allowedUnits = [.useKB, .useMB, .useGB, .useTB]
    return f.string(fromByteCount: Int64(max(0, bytes)))
}

public func formatCount(_ n: Int) -> String {
    let f = NumberFormatter()
    f.numberStyle = .decimal
    f.locale = Locale(identifier: "id_ID")
    return f.string(from: NSNumber(value: n)) ?? "\(n)"
}
