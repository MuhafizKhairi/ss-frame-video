import Foundation

public enum ExtractionMode: String, Codable, CaseIterable, Sendable, Identifiable {
    case allFrames, everyNFrames, interval, fixedCount, sceneChange
    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .allFrames: return "Semua Frame"
        case .everyNFrames: return "Tiap N Frame"
        case .interval: return "Interval Waktu"
        case .fixedCount: return "Jumlah Tetap"
        case .sceneChange: return "Ganti Adegan"
        }
    }

    public var symbol: String {
        switch self {
        case .allFrames: return "square.stack.3d.down.right.fill"
        case .everyNFrames: return "number"
        case .interval: return "timer"
        case .fixedCount: return "square.grid.3x3.fill"
        case .sceneChange: return "sparkles.tv.fill"
        }
    }

    public var explanation: String {
        switch self {
        case .allFrames: return "Ambil setiap frame tanpa terlewat. Hasilnya banyak — cocok untuk analisis frame-per-frame."
        case .everyNFrames: return "Ambil 1 frame setiap N frame. Misal N = 24 di video 24 fps ≈ 1 gambar per detik."
        case .interval: return "Ambil 1 frame setiap beberapa detik sekali."
        case .fixedCount: return "Tentukan jumlah gambar; frame dibagi rata sepanjang rentang."
        case .sceneChange: return "Otomatis ambil frame saat adegan berganti. Pas untuk anime & cuplikan film."
        }
    }
}

public enum ImageFormat: String, Codable, CaseIterable, Sendable, Identifiable {
    case jpg, png, heic, webp, avif, tiff, bmp
    public var id: String { rawValue }
    public var label: String { self == .webp ? "WebP" : rawValue.uppercased() }
    public var fileExtension: String { rawValue }
    public var hasQuality: Bool { [.jpg, .webp, .heic, .avif].contains(self) }
    /// HEIC & AVIF dikodekan oleh ImageIO macOS, bukan ffmpeg.
    public var usesImageIO: Bool { self == .heic || self == .avif }

    public var hint: String {
        switch self {
        case .jpg: return "Ukuran kecil, kualitas bagus. Pilihan paling umum."
        case .png: return "Lossless (tanpa kompresi rusak). File lebih besar."
        case .heic: return "Format Apple: kualitas setara JPG dengan ukuran ±50% lebih kecil."
        case .webp: return "Modern & hemat ruang, kualitas tinggi."
        case .avif: return "Paling hemat ruang, tapi proses lebih lambat."
        case .tiff: return "Lossless, untuk editing profesional. Sangat besar."
        case .bmp: return "Tanpa kompresi sama sekali. Paling besar."
        }
    }
}

public enum ResolutionPreset: String, Codable, CaseIterable, Sendable, Identifiable {
    case original, p2160, p1440, p1080, p720, p480, p360, custom
    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .original: return "Asli"
        case .p2160: return "4K (2160p)"
        case .p1440: return "1440p"
        case .p1080: return "1080p"
        case .p720: return "720p"
        case .p480: return "480p"
        case .p360: return "360p"
        case .custom: return "Lebar kustom…"
        }
    }

    /// Tinggi target (hanya mengecilkan, tidak memperbesar).
    public var height: Int? {
        switch self {
        case .p2160: return 2160
        case .p1440: return 1440
        case .p1080: return 1080
        case .p720: return 720
        case .p480: return 480
        case .p360: return 360
        default: return nil
        }
    }
}

public enum NamingStyle: String, Codable, CaseIterable, Sendable, Identifiable {
    case sequence, timestamp
    public var id: String { rawValue }
    public var label: String { self == .sequence ? "Nomor urut" : "Timestamp" }
}

public struct ExtractionSettings: Codable, Equatable, Sendable {
    public var mode: ExtractionMode = .interval
    public var everyN: Int = 24
    public var intervalSeconds: Double = 1
    public var fixedCount: Int = 50
    public var sceneThreshold: Double = 0.30

    public var format: ImageFormat = .jpg
    public var quality: Int = 92               // 1–100 (JPG / WebP)

    public var resolution: ResolutionPreset = .original
    public var customWidth: Int = 1280

    public var naming: NamingStyle = .sequence
    public var customPrefix: String = ""       // kosong = pakai nama video
    public var startNumber: Int = 1
    public var digits: Int = 6

    public var outputFolderPath: String = ""   // kosong = folder yang sama dengan video
    public var createSubfolder: Bool = true

    public var skipDuplicates: Bool = false
    public var hardwareDecode: Bool = true
    public var burnSubtitles: Bool = false

    public init() {}

    /// Jika JSON lama tidak memiliki field baru, pakai nilai bawaan.
    public init(from decoder: Decoder) throws {
        self.init()
        let c = try decoder.container(keyedBy: CodingKeys.self)
        func d<T: Decodable>(_ k: CodingKeys, _ cur: T) -> T { (try? c.decodeIfPresent(T.self, forKey: k)) ?? nil ?? cur }
        mode = d(.mode, mode); everyN = d(.everyN, everyN); intervalSeconds = d(.intervalSeconds, intervalSeconds)
        fixedCount = d(.fixedCount, fixedCount); sceneThreshold = d(.sceneThreshold, sceneThreshold)
        format = d(.format, format); quality = d(.quality, quality)
        resolution = d(.resolution, resolution); customWidth = d(.customWidth, customWidth)
        naming = d(.naming, naming); customPrefix = d(.customPrefix, customPrefix)
        startNumber = d(.startNumber, startNumber); digits = d(.digits, digits)
        outputFolderPath = d(.outputFolderPath, outputFolderPath); createSubfolder = d(.createSubfolder, createSubfolder)
        skipDuplicates = d(.skipDuplicates, skipDuplicates); hardwareDecode = d(.hardwareDecode, hardwareDecode)
        burnSubtitles = d(.burnSubtitles, burnSubtitles)
    }

    /// Perkiraan jumlah frame yang dihasilkan untuk rentang tertentu. nil = tidak bisa diperkirakan (deteksi adegan).
    public func estimatedFrames(duration: Double, fps: Double) -> Int? {
        let d = max(0, duration)
        let total = max(1, Int((d * fps).rounded()))
        switch mode {
        case .allFrames: return total
        case .everyNFrames: return Int((Double(total) / Double(max(1, everyN))).rounded(.up))
        case .interval: return min(total, max(1, Int((d / max(0.001, intervalSeconds) - 1e-9).rounded(.up))))
        case .fixedCount: return min(total, max(1, fixedCount))
        case .sceneChange: return nil
        }
    }

    /// Lebar keluaran setelah skala (untuk tampilan).
    public func outputSize(for w: Int, _ h: Int) -> (Int, Int) {
        guard w > 0, h > 0 else { return (w, h) }
        if resolution == .custom {
            let cw = min(w, max(16, customWidth))
            return (cw, Int((Double(h) * Double(cw) / Double(w) / 2).rounded()) * 2)
        }
        guard let th = resolution.height, th < h else { return (w, h) }
        return (Int((Double(w) * Double(th) / Double(h) / 2).rounded()) * 2, th)
    }
}
