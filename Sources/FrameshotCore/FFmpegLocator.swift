import Foundation

/// Lokasi ffmpeg/ffprobe dan kemampuan build ffmpeg yang terpasang.
public struct FFmpegToolchain: Sendable {
    public let ffmpeg: URL
    public let ffprobe: URL
    public let version: String
    public let encoders: Set<String>
    public let filters: Set<String>
    public let hwaccels: Set<String>

    public var supportsWebP: Bool { encoders.contains("libwebp") }
    public var supportsSubtitles: Bool { filters.contains("subtitles") }
    public var supportsVideoToolbox: Bool { hwaccels.contains("videotoolbox") }

    /// `-fps_mode` ada sejak ffmpeg 5.1. Build git ("N-12345") dianggap baru.
    public var supportsFpsMode: Bool {
        let digits = version.split(whereSeparator: { !$0.isNumber && $0 != "." }).first.map(String.init) ?? ""
        let parts = digits.split(separator: ".").compactMap { Int($0) }
        guard let major = parts.first else { return true }
        if major != 5 { return major > 5 }
        return (parts.count > 1 ? parts[1] : 0) >= 1
    }

    public static func locate() -> FFmpegToolchain? {
        guard let ffmpeg = findTool("ffmpeg"), let ffprobe = findTool("ffprobe") else { return nil }
        let versionOut = (try? ProcessRunner.output(ffmpeg, ["-hide_banner", "-version"])) ?? ""
        var version = "?"
        if let line = versionOut.split(separator: "\n").first {
            let words = line.split(separator: " ")
            if words.count >= 3 { version = String(words[2]) }
        }
        let encoders = parseList((try? ProcessRunner.output(ffmpeg, ["-hide_banner", "-encoders"])) ?? "", column: 1)
        let filters = parseList((try? ProcessRunner.output(ffmpeg, ["-hide_banner", "-filters"])) ?? "", column: 1)
        let hw = (try? ProcessRunner.output(ffmpeg, ["-hide_banner", "-hwaccels"])) ?? ""
        let hwaccels = Set(hw.split(separator: "\n").dropFirst().map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty })
        return FFmpegToolchain(ffmpeg: ffmpeg, ffprobe: ffprobe, version: version,
                               encoders: encoders, filters: filters, hwaccels: hwaccels)
    }

    private static func parseList(_ text: String, column: Int) -> Set<String> {
        var out = Set<String>()
        for line in text.split(separator: "\n") {
            let cols = line.split(separator: " ", omittingEmptySubsequences: true)
            if cols.count > column { out.insert(String(cols[column])) }
        }
        return out
    }

    /// Cari di dalam bundle aplikasi dulu, lalu lokasi Homebrew/MacPorts umum, lalu PATH.
    static func findTool(_ name: String) -> URL? {
        var candidates: [String] = []
        if let res = Bundle.main.resourceURL?.appendingPathComponent("bin/\(name)").path { candidates.append(res) }
        if let exe = Bundle.main.executableURL?.deletingLastPathComponent().appendingPathComponent(name).path {
            candidates.append(exe)
        }
        candidates += ["/opt/homebrew/bin/\(name)", "/usr/local/bin/\(name)", "/opt/local/bin/\(name)", "/usr/bin/\(name)"]
        let path = ProcessInfo.processInfo.environment["PATH"] ?? ""
        candidates += path.split(separator: ":").map { "\($0)/\(name)" }
        let fm = FileManager.default
        for c in candidates where fm.isExecutableFile(atPath: c) {
            return URL(fileURLWithPath: c)
        }
        return nil
    }
}

/// Utilitas menjalankan proses dan mengambil outputnya.
public enum ProcessRunner {
    public struct Failure: LocalizedError {
        public let status: Int32
        public let stderr: String
        public var errorDescription: String? { stderr.isEmpty ? "Proses gagal (kode \(status))" : stderr }
    }

    public static func output(_ tool: URL, _ args: [String]) throws -> String {
        String(decoding: try data(tool, args), as: UTF8.self)
    }

    public static func data(_ tool: URL, _ args: [String]) throws -> Data {
        let p = Process()
        p.executableURL = tool
        p.arguments = args
        let out = Pipe(), err = Pipe()
        p.standardOutput = out
        p.standardError = err
        p.standardInput = FileHandle.nullDevice
        try p.run()
        // Baca stderr di thread lain agar pipe tidak penuh dan proses macet.
        var errData = Data()
        let group = DispatchGroup()
        group.enter()
        DispatchQueue.global().async {
            errData = err.fileHandleForReading.readDataToEndOfFile()
            group.leave()
        }
        let outData = out.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        group.wait()
        if p.terminationStatus != 0 {
            throw Failure(status: p.terminationStatus,
                          stderr: String(decoding: errData, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines))
        }
        return outData
    }
}
