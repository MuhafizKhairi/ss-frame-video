import Foundation

public struct ExtractionProgress: Sendable, Equatable {
    public var frames: Int = 0
    public var fraction: Double = 0
    public var fps: Double = 0
    public var elapsed: TimeInterval = 0
    public var eta: TimeInterval? = nil
    public init() {}
}

public struct ExtractionResult: Sendable {
    public var outputFolder: URL
    public var frames: Int
    public var totalBytes: Int64
    public var elapsed: TimeInterval
    public var sampleFiles: [URL]
    public var cancelled: Bool
    public var errorMessage: String?
}

/// Menjalankan ffmpeg untuk satu ExtractionJob, melaporkan progres, dan merapikan nama file.
public final class ExtractionRunner: @unchecked Sendable {
    public let job: ExtractionJob
    let toolchain: FFmpegToolchain
    private let process = Process()
    private let lock = NSLock()
    private var cancelled = false
    private var startDate = Date()
    private var buffer = ""
    private var progress = ExtractionProgress()

    public private(set) var outputFolder: URL
    private var pattern: String = ""
    private var tempToken: String = ""
    private var finalPrefix: String = ""
    private var converter: StreamingConverter?

    public init(job: ExtractionJob, toolchain: FFmpegToolchain) {
        self.job = job
        self.toolchain = toolchain
        self.outputFolder = ExtractionRunner.resolveOutputFolder(for: job)
    }

    /// Folder tujuan: folder pilihan (atau folder video) + subfolder unik opsional.
    public static func resolveOutputFolder(for job: ExtractionJob) -> URL {
        let s = job.settings
        let base = s.outputFolderPath.isEmpty
            ? job.videoURL.deletingLastPathComponent()
            : URL(fileURLWithPath: s.outputFolderPath, isDirectory: true)
        guard s.createSubfolder else { return base }
        let name = sanitizeFilename(job.videoURL.deletingPathExtension().lastPathComponent) + " — frames"
        return uniqueURL(base.appendingPathComponent(name, isDirectory: true))
    }

    static func uniqueURL(_ url: URL) -> URL {
        let fm = FileManager.default
        if !fm.fileExists(atPath: url.path) { return url }
        let parent = url.deletingLastPathComponent()
        let name = url.lastPathComponent
        var i = 2
        while true {
            let c = parent.appendingPathComponent("\(name) \(i)", isDirectory: true)
            if !fm.fileExists(atPath: c.path) { return c }
            i += 1
        }
    }

    /// Jalankan sampai selesai. Callback progres dipanggil dari thread latar.
    public func run(onProgress: @escaping @Sendable (ExtractionProgress) -> Void) async -> ExtractionResult {
        let fm = FileManager.default
        let s = job.settings
        do {
            try fm.createDirectory(at: outputFolder, withIntermediateDirectories: true)
        } catch {
            return fail("Tidak bisa membuat folder output: \(error.localizedDescription)")
        }
        guard fm.isWritableFile(atPath: outputFolder.path) else {
            return fail("Folder output tidak bisa ditulisi. Pilih folder lain.")
        }

        let ext = s.format.fileExtension
        finalPrefix = availablePrefix(job.prefix, ext: ext)
        var converter: StreamingConverter? = nil
        if s.format.usesImageIO {
            // ffmpeg → BMP sementara → HEIC/AVIF via ImageIO.
            let tmp = outputFolder.appendingPathComponent(CommandBuilder.tempMarker + String(UUID().uuidString.prefix(8)), isDirectory: true)
            do { try fm.createDirectory(at: tmp, withIntermediateDirectories: true) }
            catch { return fail("Tidak bisa membuat folder sementara: \(error.localizedDescription)") }
            pattern = tmp.appendingPathComponent("%d.bmp").path
            let prefix = finalPrefix, digits = max(1, s.digits), timestamp = s.naming == .timestamp
            let startMs = Int((job.start * 1000).rounded())
            converter = StreamingConverter(tempDir: tmp, outputFolder: outputFolder, format: s.format, quality: s.quality) { [weak self] n in
                if timestamp { return "\(prefix)_\(self?.timestampName(ms: startMs + n) ?? String(n)).\(ext)" }
                return "\(prefix)_\(String(format: "%0\(digits)d", n)).\(ext)"
            }
            converter?.process = process
            self.converter = converter
        } else if s.naming == .timestamp {
            tempToken = CommandBuilder.tempMarker + String(UUID().uuidString.prefix(8)) + "_"
            pattern = outputFolder.appendingPathComponent("\(tempToken)%d.\(ext)").path
        } else {
            let safe = finalPrefix.replacingOccurrences(of: "%", with: "%%")
            pattern = outputFolder.appendingPathComponent("\(safe)_%0\(max(1, s.digits))d.\(ext)").path
        }

        let args = CommandBuilder.arguments(for: job, toolchain: toolchain, outputPattern: pattern)
        process.executableURL = toolchain.ffmpeg
        process.arguments = args
        let out = Pipe(), err = Pipe()
        process.standardOutput = out
        process.standardError = err
        process.standardInput = FileHandle.nullDevice

        let errCollector = DataCollector()
        err.fileHandleForReading.readabilityHandler = { h in errCollector.append(h.availableData) }
        out.fileHandleForReading.readabilityHandler = { [weak self] h in
            let d = h.availableData
            guard !d.isEmpty, let self else { return }
            self.consume(String(decoding: d, as: UTF8.self), onProgress: onProgress)
        }

        startDate = Date()
        let pollTask: Task<Void, Never>? = converter.map { conv in
            Task.detached(priority: .utility) {
                while !Task.isCancelled {
                    conv.scan(final: false)
                    try? await Task.sleep(nanoseconds: 200_000_000)
                }
            }
        }
        let status: Int32 = await withCheckedContinuation { cont in
            process.terminationHandler = { p in cont.resume(returning: p.terminationStatus) }
            do {
                if isCancelled { cont.resume(returning: -1); return }
                try process.run()
            } catch {
                errCollector.append(Data("Gagal menjalankan ffmpeg: \(error.localizedDescription)".utf8))
                cont.resume(returning: -2)
            }
        }
        out.fileHandleForReading.readabilityHandler = nil
        err.fileHandleForReading.readabilityHandler = nil

        let wasCancelled = isCancelled
        let elapsed = Date().timeIntervalSince(startDate)

        // Kumpulkan file hasil (dan ganti nama untuk mode timestamp).
        pollTask?.cancel()
        let files = converter?.finish() ?? collectOutputs()
        var bytes: Int64 = 0
        for f in files {
            bytes += Int64((try? f.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
        var message: String? = nil
        if status != 0 && !wasCancelled {
            let e = errCollector.string.trimmingCharacters(in: .whitespacesAndNewlines)
            message = e.isEmpty ? "ffmpeg berhenti dengan kode \(status)." : String(e.suffix(800))
        }
        if message == nil, let convErr = converter?.firstError { message = convErr }
        return ExtractionResult(outputFolder: outputFolder, frames: files.count, totalBytes: bytes,
                                elapsed: elapsed, sampleFiles: Array(files.prefix(12)),
                                cancelled: wasCancelled, errorMessage: message)
    }

    private var isCancelled: Bool { lock.withLock { cancelled } }

    public func cancel() {
        lock.lock()
        cancelled = true
        let conv = converter
        lock.unlock()
        conv?.resumeProcess() // proses yang dijeda harus dilanjutkan dulu agar bisa berhenti
        if process.isRunning { process.terminate() }
    }

    private func fail(_ msg: String) -> ExtractionResult {
        ExtractionResult(outputFolder: outputFolder, frames: 0, totalBytes: 0, elapsed: 0,
                         sampleFiles: [], cancelled: false, errorMessage: msg)
    }

    // MARK: Progres

    private func consume(_ text: String, onProgress: @Sendable (ExtractionProgress) -> Void) {
        lock.lock()
        buffer += text
        var lines = buffer.components(separatedBy: "\n")
        buffer = lines.removeLast()
        var emit = false
        for line in lines {
            let kv = line.split(separator: "=", maxSplits: 1).map(String.init)
            guard kv.count == 2 else { continue }
            switch kv[0] {
            case "frame": progress.frames = Int(kv[1]) ?? progress.frames
            case "out_time_us", "out_time_ms":
                // Keduanya dalam mikrodetik pada ffmpeg modern.
                if let us = Double(kv[1]), us > 0, job.rangeDuration > 0 {
                    progress.fraction = max(progress.fraction, min(1, us / 1_000_000 / job.rangeDuration))
                }
            case "progress":
                emit = true
                if kv[1] == "end" { progress.fraction = 1 }
            default: break
            }
        }
        if emit {
            if job.settings.mode == .fixedCount, let n = job.expectedFrames, n > 0 {
                progress.fraction = max(progress.fraction, min(1, Double(progress.frames) / Double(n)))
            }
            progress.elapsed = Date().timeIntervalSince(startDate)
            progress.fps = progress.elapsed > 0 ? Double(progress.frames) / progress.elapsed : 0
            if progress.fraction > 0.01 && progress.fraction < 1 {
                progress.eta = progress.elapsed * (1 - progress.fraction) / progress.fraction
            } else {
                progress.eta = nil
            }
        }
        let snapshot = progress
        lock.unlock()
        if emit { onProgress(snapshot) }
    }

    // MARK: Penamaan file

    /// Jika sudah ada file dengan prefix yang sama di folder (tanpa subfolder), tambahkan akhiran agar tidak tertimpa.
    private func availablePrefix(_ prefix: String, ext: String) -> String {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: outputFolder.path)) ?? []
        func taken(_ p: String) -> Bool {
            names.contains { $0.hasPrefix(p + "_") && $0.lowercased().hasSuffix("." + ext) }
        }
        if !taken(prefix) { return prefix }
        var i = 2
        while taken("\(prefix) (\(i))") { i += 1 }
        return "\(prefix) (\(i))"
    }

    private func collectOutputs() -> [URL] {
        let fm = FileManager.default
        let names = (try? fm.contentsOfDirectory(atPath: outputFolder.path)) ?? []
        let ext = "." + job.settings.format.fileExtension

        if job.settings.naming == .timestamp {
            var items: [(Int, String)] = []
            for n in names where n.hasPrefix(tempToken) && n.hasSuffix(ext) {
                let num = n.dropFirst(tempToken.count).dropLast(ext.count)
                if let ms = Int(num) { items.append((ms, n)) }
            }
            items.sort { $0.0 < $1.0 }
            let startMs = Int((job.start * 1000).rounded())
            var used = Set(names)
            var result: [URL] = []
            for (ms, name) in items {
                let stamp = timestampName(ms: startMs + ms)
                var target = "\(finalPrefix)_\(stamp)\(ext)"
                var k = 2
                while used.contains(target) { target = "\(finalPrefix)_\(stamp) (\(k))\(ext)"; k += 1 }
                used.insert(target)
                let src = outputFolder.appendingPathComponent(name)
                let dst = outputFolder.appendingPathComponent(target)
                if (try? fm.moveItem(at: src, to: dst)) != nil { result.append(dst) } else { result.append(src) }
            }
            return result
        }

        let head = finalPrefix + "_"
        let digits = CharacterSet.decimalDigits
        return names.filter { n in
            guard n.hasPrefix(head), n.hasSuffix(ext) else { return false }
            let mid = n.dropFirst(head.count).dropLast(ext.count)
            return !mid.isEmpty && mid.unicodeScalars.allSatisfy { digits.contains($0) }
        }.sorted().map { outputFolder.appendingPathComponent($0) }
    }

    func timestampName(ms: Int) -> String {
        let h = ms / 3_600_000, m = (ms / 60_000) % 60, s = (ms / 1000) % 60, r = ms % 1000
        return String(format: "%02d-%02d-%02d-%03d", h, m, s, r)
    }
}

/// Contoh nama file untuk ditampilkan di UI.
public func exampleFilename(settings: ExtractionSettings, videoName: String) -> String {
    let p = settings.customPrefix.trimmingCharacters(in: .whitespaces)
    let prefix = sanitizeFilename(p.isEmpty ? videoName : p)
    let ext = settings.format.fileExtension
    if settings.naming == .timestamp { return "\(prefix)_00-12-30-500.\(ext)" }
    return "\(prefix)_\(String(format: "%0\(max(1, settings.digits))d", settings.startNumber)).\(ext)"
}

final class DataCollector: @unchecked Sendable {
    private var data = Data()
    private let lock = NSLock()
    func append(_ d: Data) { lock.lock(); data.append(d); lock.unlock() }
    var string: String { lock.lock(); defer { lock.unlock() }; return String(decoding: data, as: UTF8.self) }
}
