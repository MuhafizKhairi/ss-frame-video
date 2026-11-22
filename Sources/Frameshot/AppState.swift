import AppKit
import Observation
import SwiftUI
import UniformTypeIdentifiers
import FrameshotCore

// MARK: - Model video di antrean

@Observable
final class VideoItem: Identifiable {
    enum Status: Equatable { case ready, running, done(Int), failed(String), cancelled }

    let id = UUID()
    let url: URL
    var info: VideoInfo?
    var loadError: String?
    var thumbnail: NSImage?
    var included = true
    var customRange = false
    var rangeStart: Double = 0
    var rangeEnd: Double = 0
    var status: Status = .ready
    var lastTime: Double = 0   // posisi pratinjau terakhir

    init(url: URL) { self.url = url }

    var name: String { url.deletingPathExtension().lastPathComponent }
    var duration: Double { info?.duration ?? 0 }
    var effectiveStart: Double { customRange ? rangeStart : 0 }
    var effectiveEnd: Double { customRange ? rangeEnd : duration }
    var effectiveDuration: Double { max(0, effectiveEnd - effectiveStart) }
}

struct RunSummary {
    var frames = 0
    var bytes: Int64 = 0
    var elapsed: TimeInterval = 0
    var folders: [URL] = []
    var samples: [URL] = []
    var errors: [(String, String)] = []
    var cancelled = false
    var videos = 0
}

enum Phase: Equatable {
    case idle, running, finished
}

struct Toast: Identifiable, Equatable {
    let id = UUID()
    var message: String
    var symbol: String = "checkmark.circle.fill"
    var isError = false
    var revealURL: URL?
}

// MARK: - State aplikasi

@MainActor
@Observable
final class AppState {
    static let videoExtensions: Set<String> = [
        "mp4", "m4v", "mov", "mkv", "webm", "avi", "flv", "wmv", "ts", "m2ts", "mts", "mpg", "mpeg",
        "3gp", "ogv", "vob", "divx", "rmvb", "f4v", "asf",
    ]

    var toolchain: FFmpegToolchain?
    var toolchainChecked = false

    var videos: [VideoItem] = []
    var selectedID: UUID?

    var settings: ExtractionSettings {
        didSet {
            guard settings != oldValue else { return }
            saveSettings()
            scheduleSampleUpdate()
        }
    }

    // Pratinjau
    var currentTime: Double = 0
    var previewImage: NSImage?
    var previewLoading = false

    // Perkiraan ukuran
    var sampleBytes: Double?
    var sampleLoading = false

    // Proses
    var phase: Phase = .idle
    var progress = ExtractionProgress()
    var runIndex = 0
    var runTotal = 0
    var runName = ""
    var summary: RunSummary?
    var showSummary = false

    var toast: Toast?

    private var previewRequest: (URL, Double)?
    private var previewTask: Task<Void, Never>?
    private var sampleTask: Task<Void, Never>?
    private var sampleKey = ""
    private var runTask: Task<Void, Never>?
    private var currentRunner: ExtractionRunner?
    private var cancelRequested = false
    private var toastTask: Task<Void, Never>?

    init() {
        if let data = UserDefaults.standard.data(forKey: "settings.v1"),
           let s = try? JSONDecoder().decode(ExtractionSettings.self, from: data) {
            settings = s
        } else {
            settings = ExtractionSettings()
        }
        Task { await checkToolchain() }
    }

    // MARK: ffmpeg

    func checkToolchain() async {
        let tc = await Task.detached { FFmpegToolchain.locate() }.value
        toolchain = tc
        toolchainChecked = true
        if let tc {
            if settings.format == .webp && !tc.supportsWebP { settings.format = .jpg }
            if settings.burnSubtitles && !tc.supportsSubtitles { settings.burnSubtitles = false }
            for item in videos where item.info == nil { item.loadError = nil; load(item) }
        }
    }

    func isAvailable(_ f: ImageFormat) -> Bool {
        f == .webp ? (toolchain?.supportsWebP ?? false) : true
    }

    // MARK: Video

    var selected: VideoItem? { videos.first { $0.id == selectedID } }

    func select(_ item: VideoItem) {
        guard item.id != selectedID else { return }
        selected?.lastTime = currentTime
        selectedID = item.id
        currentTime = item.lastTime
        previewImage = item.thumbnail
        requestPreview()
        scheduleSampleUpdate()
    }

    func openVideoPanel() {
        let panel = NSOpenPanel()
        panel.title = "Pilih Video"
        panel.prompt = "Tambahkan"
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.allowedContentTypes = [.movie, .video, .audiovisualContent] +
            Self.videoExtensions.compactMap { UTType(filenameExtension: $0) }
        if panel.runModal() == .OK { add(urls: panel.urls) }
    }

    func openFolderPanel() {
        let panel = NSOpenPanel()
        panel.title = "Pilih Folder Berisi Video"
        panel.prompt = "Tambahkan Semua"
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        if panel.runModal() == .OK, let url = panel.url { add(urls: [url]) }
    }

    func chooseOutputFolder() {
        let panel = NSOpenPanel()
        panel.title = "Simpan Hasil Screenshot ke…"
        panel.prompt = "Pilih Folder"
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.canCreateDirectories = true
        if !settings.outputFolderPath.isEmpty {
            panel.directoryURL = URL(fileURLWithPath: settings.outputFolderPath)
        }
        if panel.runModal() == .OK, let url = panel.url { settings.outputFolderPath = url.path }
    }

    /// Tambah file video atau seluruh isi folder (tidak rekursif ke dalam paket).
    func add(urls: [URL]) {
        var files: [URL] = []
        let fm = FileManager.default
        for url in urls {
            var isDir: ObjCBool = false
            guard fm.fileExists(atPath: url.path, isDirectory: &isDir) else { continue }
            if isDir.boolValue {
                let items = (try? fm.contentsOfDirectory(at: url, includingPropertiesForKeys: nil,
                                                         options: [.skipsHiddenFiles])) ?? []
                files += items.filter { Self.videoExtensions.contains($0.pathExtension.lowercased()) }
                    .sorted { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending }
            } else if Self.videoExtensions.contains(url.pathExtension.lowercased()) {
                files.append(url)
            }
        }
        let existing = Set(videos.map { $0.url.standardizedFileURL })
        let newItems = files.filter { !existing.contains($0.standardizedFileURL) }.map(VideoItem.init)
        if files.isEmpty {
            showToast("Tidak ada file video yang dikenali.", symbol: "exclamationmark.triangle.fill", isError: true)
            return
        }
        videos += newItems
        if selectedID == nil, let first = newItems.first {
            selectedID = first.id
            currentTime = 0
            previewImage = nil
        }
        for item in newItems { load(item) }
    }

    func remove(_ item: VideoItem) {
        guard phase != .running else { return }
        let idx = videos.firstIndex { $0.id == item.id }
        videos.removeAll { $0.id == item.id }
        if selectedID == item.id {
            selectedID = nil
            previewImage = nil
            if let idx, !videos.isEmpty { select(videos[min(idx, videos.count - 1)]) }
        }
    }

    func clearAll() {
        guard phase != .running else { return }
        videos.removeAll()
        selectedID = nil
        previewImage = nil
    }

    private func load(_ item: VideoItem) {
        guard let tc = toolchain else { return }
        let url = item.url
        Task {
            do {
                let info = try await Task.detached { try VideoProbe.probe(url, toolchain: tc) }.value
                item.info = info
                item.rangeEnd = info.duration
                if item.id == selectedID {
                    requestPreview()
                    scheduleSampleUpdate()
                }
                let t = info.duration * 0.1
                if let data = try? await Task.detached(operation: {
                    try FrameGrabber.preview(video: url, at: t, width: 360, toolchain: tc)
                }).value {
                    item.thumbnail = NSImage(data: data)
                    if item.id == selectedID && previewImage == nil { previewImage = item.thumbnail }
                }
            } catch {
                item.loadError = error.localizedDescription
                item.included = false
            }
        }
    }

    // MARK: Pratinjau & navigasi

    func requestPreview() {
        guard let item = selected, item.info != nil, let tc = toolchain else { return }
        previewRequest = (item.url, currentTime)
        guard previewTask == nil else { return }
        previewLoading = true
        previewTask = Task {
            while let (url, t) = previewRequest {
                previewRequest = nil
                let data = try? await Task.detached {
                    try FrameGrabber.preview(video: url, at: t, width: 1280, toolchain: tc)
                }.value
                if let data, let img = NSImage(data: data), selected?.url == url {
                    previewImage = img
                }
            }
            previewTask = nil
            previewLoading = false
        }
    }

    func seek(to t: Double) {
        guard let info = selected?.info else { return }
        currentTime = min(max(0, t), max(0, info.duration - info.frameDuration))
        requestPreview()
    }

    func step(frames: Int) {
        guard let info = selected?.info else { return }
        let n = (currentTime * info.fps).rounded() + Double(frames)
        seek(to: n / info.fps + 0.0001)
    }

    func step(seconds: Double) { seek(to: currentTime + seconds) }

    var currentFrameNumber: Int {
        guard let info = selected?.info else { return 0 }
        return Int((currentTime * info.fps).rounded())
    }

    func setRangeStart() {
        guard let item = selected else { return }
        item.customRange = true
        item.rangeStart = min(currentTime, max(0, item.rangeEnd - (item.info?.frameDuration ?? 0.04)))
        if item.rangeEnd <= item.rangeStart { item.rangeEnd = item.duration }
    }

    func setRangeEnd() {
        guard let item = selected else { return }
        item.customRange = true
        let end = min(item.duration, currentTime + (item.info?.frameDuration ?? 0))
        item.rangeEnd = max(end, item.rangeStart + (item.info?.frameDuration ?? 0.04))
    }

    /// Simpan frame yang sedang dipratinjau, resolusi penuh.
    func captureCurrentFrame() {
        guard let item = selected, item.info != nil, let tc = toolchain else { return }
        let s = settings
        let base = s.outputFolderPath.isEmpty ? item.url.deletingLastPathComponent()
                                              : URL(fileURLWithPath: s.outputFolderPath)
        let p = s.customPrefix.trimmingCharacters(in: .whitespaces)
        let prefix = sanitizeFilename(p.isEmpty ? item.name : p)
        let ms = Int((currentTime * 1000).rounded())
        let stamp = String(format: "%02d-%02d-%02d-%03d", ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000)
        var url = base.appendingPathComponent("\(prefix)_\(stamp).\(s.format.fileExtension)")
        var k = 2
        while FileManager.default.fileExists(atPath: url.path) {
            url = base.appendingPathComponent("\(prefix)_\(stamp) (\(k)).\(s.format.fileExtension)")
            k += 1
        }
        let t = currentTime, video = item.url, target = url
        Task {
            do {
                try await Task.detached {
                    try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
                    try FrameGrabber.snapshot(video: video, at: t, settings: s, to: target, toolchain: tc)
                }.value
                NSSound(named: "Tink")?.play()
                showToast("Tersimpan: \(target.lastPathComponent)", symbol: "camera.fill", revealURL: target)
            } catch {
                showToast("Gagal menyimpan: \(error.localizedDescription)", symbol: "xmark.octagon.fill", isError: true)
            }
        }
    }

    // MARK: Perkiraan

    var includedVideos: [VideoItem] { videos.filter { $0.included && $0.info != nil } }

    var estimatedFrames: Int? {
        let items = includedVideos
        guard !items.isEmpty else { return 0 }
        var total = 0
        for v in items {
            guard let info = v.info, let n = settings.estimatedFrames(duration: v.effectiveDuration, fps: info.fps) else { return nil }
            total += n
        }
        return total
    }

    var estimatedBytes: Double? {
        guard let n = estimatedFrames, let b = sampleBytes else { return nil }
        return Double(n) * b
    }

    var outputBaseURL: URL? {
        if !settings.outputFolderPath.isEmpty { return URL(fileURLWithPath: settings.outputFolderPath) }
        return selected?.url.deletingLastPathComponent()
    }

    var freeDiskBytes: Double? {
        guard let url = outputBaseURL else { return nil }
        let v = try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return v?.volumeAvailableCapacityForImportantUsage.map(Double.init)
    }

    func scheduleSampleUpdate() {
        guard let item = selected, let info = item.info, let tc = toolchain else { return }
        let s = settings
        let key = "\(item.url.path)|\(s.format)|\(s.quality)|\(s.resolution)|\(s.customWidth)"
        guard key != sampleKey else { return }
        sampleKey = key
        sampleTask?.cancel()
        sampleLoading = true
        let url = item.url, start = item.effectiveStart, end = item.effectiveEnd > 0 ? item.effectiveEnd : info.duration
        sampleTask = Task {
            try? await Task.sleep(nanoseconds: 350_000_000)
            if Task.isCancelled { return }
            let bytes = await Task.detached {
                FrameGrabber.sampleFrameBytes(video: url, start: start, end: end, settings: s, toolchain: tc)
            }.value
            if Task.isCancelled { return }
            sampleBytes = bytes
            sampleLoading = false
        }
    }

    // MARK: Ekstraksi

    var canStart: Bool { phase != .running && toolchain != nil && !includedVideos.isEmpty }

    var overallFraction: Double {
        guard runTotal > 0 else { return 0 }
        return min(1, (Double(runIndex) + progress.fraction) / Double(runTotal))
    }

    func startExtraction() {
        guard canStart, let tc = toolchain else { return }
        let items = includedVideos
        let s = settings
        selected?.lastTime = currentTime
        cancelRequested = false
        phase = .running
        runTotal = items.count
        runIndex = 0
        progress = ExtractionProgress()
        for v in items { v.status = .ready }

        runTask = Task {
            var sum = RunSummary()
            let started = Date()
            for (i, item) in items.enumerated() {
                if cancelRequested { break }
                guard let info = item.info else { continue }
                runIndex = i
                runName = item.name
                progress = ExtractionProgress()
                item.status = .running
                let job = ExtractionJob(videoURL: item.url, info: info, start: item.effectiveStart,
                                        end: item.effectiveEnd, settings: s)
                let runner = ExtractionRunner(job: job, toolchain: tc)
                currentRunner = runner
                let result = await Task.detached {
                    await runner.run { p in
                        Task { @MainActor [weak self] in self?.progress = p }
                    }
                }.value
                currentRunner = nil
                sum.videos += 1
                sum.frames += result.frames
                sum.bytes += result.totalBytes
                if result.frames > 0 || result.errorMessage == nil { sum.folders.append(result.outputFolder) }
                if sum.samples.count < 12 { sum.samples += result.sampleFiles.prefix(12 - sum.samples.count) }
                if let e = result.errorMessage {
                    item.status = .failed(e)
                    sum.errors.append((item.name, e))
                } else if result.cancelled {
                    item.status = .cancelled
                    sum.cancelled = true
                    break
                } else {
                    item.status = .done(result.frames)
                }
            }
            sum.elapsed = Date().timeIntervalSince(started)
            sum.cancelled = sum.cancelled || cancelRequested
            if terminateAfterRun {
                NSApp.reply(toApplicationShouldTerminate: true)
                return
            }
            summary = sum
            phase = .finished
            showSummary = true
            NSSound(named: sum.errors.isEmpty ? "Glass" : "Basso")?.play()
            NSApp.requestUserAttention(.informationalRequest)
        }
    }

    /// Dipanggil saat pengguna keluar ketika ekstraksi berjalan: hentikan rapi, lalu keluar.
    var terminateAfterRun = false
    func cancelForTermination() {
        terminateAfterRun = true
        cancelExtraction()
    }

    func cancelExtraction() {
        cancelRequested = true
        currentRunner?.cancel()
    }

    func dismissSummary() {
        showSummary = false
        phase = .idle
    }

    // MARK: Lain-lain

    func reveal(_ url: URL) {
        var isDir: ObjCBool = false
        if FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir), isDir.boolValue {
            NSWorkspace.shared.open(url)
        } else {
            NSWorkspace.shared.activateFileViewerSelecting([url])
        }
    }

    func showToast(_ message: String, symbol: String = "checkmark.circle.fill", isError: Bool = false, revealURL: URL? = nil) {
        withAnimation(.spring(duration: 0.35)) {
            toast = Toast(message: message, symbol: symbol, isError: isError, revealURL: revealURL)
        }
        toastTask?.cancel()
        toastTask = Task {
            try? await Task.sleep(nanoseconds: 3_500_000_000)
            if Task.isCancelled { return }
            withAnimation(.easeOut(duration: 0.25)) { toast = nil }
        }
    }

    func resetSettings() {
        let folder = settings.outputFolderPath
        settings = ExtractionSettings()
        settings.outputFolderPath = folder
    }

    private func saveSettings() {
        if let data = try? JSONEncoder().encode(settings) {
            UserDefaults.standard.set(data, forKey: "settings.v1")
        }
    }
}
