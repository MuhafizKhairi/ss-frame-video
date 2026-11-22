import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Konversi gambar lewat ImageIO bawaan macOS (untuk HEIC & AVIF yang tidak ada di ffmpeg standar).
public enum ImageConverter {
    public static func convert(_ src: URL, to dst: URL, format: ImageFormat, quality: Int) throws {
        guard let source = CGImageSourceCreateWithURL(src as CFURL, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
            throw ProcessRunner.Failure(status: 1, stderr: "Gagal membaca frame sementara.")
        }
        let type: UTType = format == .avif ? (UTType("public.avif") ?? .heic) : .heic
        guard let dest = CGImageDestinationCreateWithURL(dst as CFURL, type.identifier as CFString, 1, nil) else {
            throw ProcessRunner.Failure(status: 1, stderr: "Format \(format.label) tidak didukung macOS ini.")
        }
        let q = Double(min(100, max(1, quality))) / 100
        CGImageDestinationAddImage(dest, image, [kCGImageDestinationLossyCompressionQuality: q] as CFDictionary)
        guard CGImageDestinationFinalize(dest) else {
            throw ProcessRunner.Failure(status: 1, stderr: "Gagal menyimpan \(format.label).")
        }
    }
}

/// Mengonversi frame sementara (BMP dari ffmpeg) ke HEIC/AVIF secara paralel selagi ffmpeg masih berjalan.
/// ffmpeg dijeda (SIGSTOP) jika antrean terlalu panjang supaya disk tidak penuh file sementara.
final class StreamingConverter: @unchecked Sendable {
    let tempDir: URL
    let outputFolder: URL
    let format: ImageFormat
    let quality: Int
    let nameFor: (Int) -> String   // nomor file sementara → nama file akhir (tanpa folder)

    private let queue: OperationQueue
    private let lock = NSLock()
    private var scheduled = Set<Int>()
    private var done: [(Int, URL)] = []
    private var pending = 0
    private var paused = false
    private(set) var firstError: String?
    weak var process: Process?

    init(tempDir: URL, outputFolder: URL, format: ImageFormat, quality: Int, nameFor: @escaping (Int) -> String) {
        self.tempDir = tempDir
        self.outputFolder = outputFolder
        self.format = format
        self.quality = quality
        self.nameFor = nameFor
        queue = OperationQueue()
        queue.maxConcurrentOperationCount = max(2, ProcessInfo.processInfo.activeProcessorCount - 1)
        queue.qualityOfService = .userInitiated
    }

    /// Periksa folder sementara. `final` = ffmpeg sudah selesai, jadi semua file dianggap lengkap.
    func scan(final: Bool) {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: tempDir.path)) ?? []
        var nums = names.compactMap { n -> Int? in
            guard n.hasSuffix(".bmp") else { return nil }
            return Int(n.dropLast(4))
        }.sorted()
        if !final, !nums.isEmpty { nums.removeLast() } // file terakhir mungkin masih ditulis
        for n in nums { schedule(n) }
        throttle()
    }

    private func schedule(_ n: Int) {
        lock.lock()
        guard !scheduled.contains(n) else { lock.unlock(); return }
        scheduled.insert(n)
        pending += 1
        lock.unlock()
        queue.addOperation { [self] in
            let src = tempDir.appendingPathComponent("\(n).bmp")
            let dst = outputFolder.appendingPathComponent(nameFor(n))
            do {
                try ImageConverter.convert(src, to: dst, format: format, quality: quality)
                lock.withLock { done.append((n, dst)) }
            } catch {
                lock.withLock { if firstError == nil { firstError = error.localizedDescription } }
            }
            try? FileManager.default.removeItem(at: src)
            lock.withLock { pending -= 1 }
            throttle()
        }
    }

    private func throttle() {
        lock.lock()
        let p = pending
        var action: Int32 = 0
        if !paused && p > 48 { paused = true; action = SIGSTOP }
        else if paused && p < 12 { paused = false; action = SIGCONT }
        lock.unlock()
        if action != 0, let proc = process, proc.isRunning { kill(proc.processIdentifier, action) }
    }

    func resumeProcess() {
        lock.withLock { paused = false }
        if let proc = process, proc.isRunning { kill(proc.processIdentifier, SIGCONT) }
    }

    func finish() -> [URL] {
        scan(final: true)
        queue.waitUntilAllOperationsAreFinished()
        try? FileManager.default.removeItem(at: tempDir)
        return lock.withLock { done.sorted { $0.0 < $1.0 }.map(\.1) }
    }
}
