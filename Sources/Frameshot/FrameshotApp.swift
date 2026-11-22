import SwiftUI
import AppKit

@main
struct FrameshotApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @State private var state = AppState()

    var body: some Scene {
        Window("Frameshot", id: "main") {
            ContentView()
                .environment(state)
                .frame(minWidth: 1040, minHeight: 680)
                .onAppear {
                    delegate.attach(state)
                    DebugHooks.runIfRequested(state)
                }
        }
        .defaultSize(width: 1240, height: 800)
        .commands {
            CommandGroup(replacing: .newItem) {
                Button("Buka Video…") { state.openVideoPanel() }
                    .keyboardShortcut("o")
                Button("Tambah Folder Video…") { state.openFolderPanel() }
                    .keyboardShortcut("o", modifiers: [.command, .shift])
                Divider()
                Button("Pilih Folder Output…") { state.chooseOutputFolder() }
                    .keyboardShortcut("e", modifiers: [.command, .shift])
            }
            CommandMenu("Frame") {
                Button("Ambil Frame Ini") { state.captureCurrentFrame() }
                    .keyboardShortcut("s")
                    .disabled(state.selected?.info == nil)
                Divider()
                Button("Mulai Ekstrak") { state.startExtraction() }
                    .disabled(!state.canStart)
                Button("Batalkan") { state.cancelExtraction() }
                    .keyboardShortcut(".", modifiers: .command)
                    .disabled(state.phase != .running)
            }
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    private weak var state: AppState?
    private var pendingURLs: [URL] = []

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Saat dijalankan langsung dari terminal (bukan .app), tetap tampil sebagai aplikasi biasa.
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }

    /// Jangan tinggalkan ffmpeg / file sementara jika keluar saat ekstraksi berjalan.
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        MainActor.assumeIsolated {
            guard let state, state.phase == .running else { return .terminateNow }
            if ProcessInfo.processInfo.environment["FRAMESHOT_DEBUG_SCRIPT"] == nil {
                let alert = NSAlert()
                alert.messageText = "Ekstraksi masih berjalan"
                alert.informativeText = "Hentikan proses dan keluar? Gambar yang sudah tersimpan tetap ada."
                alert.addButton(withTitle: "Hentikan & Keluar")
                alert.addButton(withTitle: "Lanjutkan")
                if alert.runModal() != .alertFirstButtonReturn { return .terminateCancel }
            }
            state.cancelForTermination()
            return .terminateLater
        }
    }

    /// File yang dibuka lewat Finder ("Buka Dengan") atau diseret ke ikon Dock.
    func application(_ application: NSApplication, open urls: [URL]) {
        if let state { MainActor.assumeIsolated { state.add(urls: urls) } } else { pendingURLs += urls }
    }

    @MainActor func attach(_ state: AppState) {
        self.state = state
        if !pendingURLs.isEmpty {
            state.add(urls: pendingURLs)
            pendingURLs = []
        }
    }
}
