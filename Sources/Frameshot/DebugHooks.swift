import AppKit
import FrameshotCore

/// Skrip uji otomatis untuk pengembangan (tidak aktif kecuali variabel lingkungan diset).
/// FRAMESHOT_DEBUG_SCRIPT="open:/a.mp4;wait:3;snap:/tmp/a.png;mode:sceneChange;range;extract;wait:5;snap:/tmp/b.png;quit"
@MainActor
enum DebugHooks {
    static func runIfRequested(_ state: AppState) {
        guard let script = ProcessInfo.processInfo.environment["FRAMESHOT_DEBUG_SCRIPT"] else { return }
        Task {
            for cmd in script.split(separator: ";").map(String.init) {
                let parts = cmd.split(separator: ":", maxSplits: 1).map(String.init)
                let arg = parts.count > 1 ? parts[1] : ""
                switch parts[0] {
                case "open": state.add(urls: arg.split(separator: "|").map { URL(fileURLWithPath: String($0)) })
                case "wait": try? await Task.sleep(nanoseconds: UInt64((Double(arg) ?? 1) * 1e9))
                case "snap": snapshot(to: arg)
                case "mode": if let m = ExtractionMode(rawValue: arg) { state.settings.mode = m }
                case "format": if let f = ImageFormat(rawValue: arg) { state.settings.format = f }
                case "out": state.settings.outputFolderPath = arg
                case "seek": state.seek(to: Double(arg) ?? 0)
                case "range": state.seek(to: 3); state.setRangeStart(); state.seek(to: 9); state.setRangeEnd()
                case "advanced": NotificationCenter.default.post(name: .init("frameshot.debug.advanced"), object: nil)
                case "dark": NSApp.appearance = NSAppearance(named: .darkAqua)
                case "light": NSApp.appearance = NSAppearance(named: .aqua)
                case "extract": state.startExtraction()
                case "capture": state.captureCurrentFrame()
                case "quit": NSApp.terminate(nil)
                default: break
                }
            }
        }
    }

    static func snapshot(to path: String) {
        // Gambar seluruh jendela (termasuk sheet jika terbuka) tanpa butuh izin rekam layar.
        for window in NSApp.windows where window.isVisible {
            guard let view = window.contentView?.superview ?? window.contentView else { continue }
            guard let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { continue }
            view.cacheDisplay(in: view.bounds, to: rep)
            let suffix = window.isSheet ? "-sheet" : ""
            let url = URL(fileURLWithPath: path.replacingOccurrences(of: ".png", with: "\(suffix).png"))
            try? rep.representation(using: .png, properties: [:])?.write(to: url)
        }
    }
}
