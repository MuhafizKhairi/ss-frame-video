import SwiftUI
import UniformTypeIdentifiers
import FrameshotCore

struct ContentView: View {
    @Environment(AppState.self) private var state
    @State private var dropTargeted = false

    var body: some View {
        @Bindable var state = state
        ZStack {
            Color(nsColor: .windowBackgroundColor).ignoresSafeArea()

            if !state.toolchainChecked {
                ProgressView().controlSize(.small)
            } else if state.toolchain == nil {
                MissingFFmpegView()
            } else {
                HStack(spacing: 0) {
                    MainPane()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    Divider().ignoresSafeArea()
                    SettingsSidebar()
                        .frame(width: 372)
                }
                .ignoresSafeArea(edges: .top)
            }

            if dropTargeted { DropOverlay().transition(.opacity) }

            VStack {
                Spacer()
                if let toast = state.toast {
                    ToastView(toast: toast).padding(.bottom, 22)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .padding(.trailing, 372)
        }
        .tint(Theme.violet)
        .background(WindowConfigurator())
        .onDrop(of: [.fileURL], isTargeted: $dropTargeted.animation(.easeOut(duration: 0.15))) { providers in
            handleDrop(providers)
        }
        .sheet(isPresented: $state.showSummary, onDismiss: { state.dismissSummary() }) {
            SummarySheet()
        }
    }

    private func handleDrop(_ providers: [NSItemProvider]) -> Bool {
        guard state.toolchain != nil, state.phase != .running else { return false }
        let group = DispatchGroup()
        let collector = URLCollector()
        for p in providers where p.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
            group.enter()
            _ = p.loadObject(ofClass: URL.self) { url, _ in
                if let url { collector.add(url) }
                group.leave()
            }
        }
        group.notify(queue: .main) { state.add(urls: collector.urls) }
        return true
    }
}

private final class URLCollector: @unchecked Sendable {
    private var items: [URL] = []
    private let lock = NSLock()
    func add(_ u: URL) { lock.withLock { items.append(u) } }
    var urls: [URL] { lock.withLock { items } }
}

struct DropOverlay: View {
    var body: some View {
        ZStack {
            Rectangle().fill(.ultraThinMaterial)
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .strokeBorder(Theme.gradient, style: StrokeStyle(lineWidth: 2.5, dash: [10, 8]))
                .padding(28)
            VStack(spacing: 12) {
                Image(systemName: "arrow.down.circle.fill")
                    .font(.system(size: 54))
                    .foregroundStyle(Theme.gradient)
                Text("Lepaskan untuk menambahkan video").font(.title3.weight(.semibold))
                Text("File video atau folder berisi video").foregroundStyle(.secondary)
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }
}

struct ToastView: View {
    @Environment(AppState.self) private var state
    let toast: Toast
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: toast.symbol)
                .foregroundStyle(toast.isError ? AnyShapeStyle(Color.red) : AnyShapeStyle(Theme.gradient))
            Text(toast.message).font(.system(size: 13, weight: .medium)).lineLimit(2)
            if let url = toast.revealURL {
                Button("Tampilkan") { state.reveal(url) }
                    .buttonStyle(SoftButtonStyle(compact: true))
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Capsule().fill(.regularMaterial))
        .overlay(Capsule().strokeBorder(Theme.cardStroke))
        .shadow(color: .black.opacity(0.18), radius: 16, y: 6)
    }
}

struct MissingFFmpegView: View {
    @Environment(AppState.self) private var state
    @State private var copied = false
    private let command = "brew install ffmpeg"

    var body: some View {
        VStack(spacing: 18) {
            AppLogo(size: 64)
            Text("Frameshot butuh ffmpeg").font(.system(size: 22, weight: .bold, design: .rounded))
            Text("ffmpeg adalah mesin gratis yang dipakai untuk membaca video.\nPasang sekali lewat Terminal, lalu klik “Cek Lagi”.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            HStack {
                Text(command).font(.system(size: 14, weight: .medium, design: .monospaced))
                Spacer()
                Button(copied ? "Tersalin" : "Salin") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(command, forType: .string)
                    copied = true
                }
                .buttonStyle(SoftButtonStyle(compact: true))
            }
            .padding(12)
            .frame(width: 360)
            .background(RoundedRectangle(cornerRadius: 10).fill(Theme.cardFill))
            Text("Belum punya Homebrew? Kunjungi brew.sh").font(.caption).foregroundStyle(.secondary)
            Button("Cek Lagi") { Task { await state.checkToolchain() } }
                .buttonStyle(PrimaryButtonStyle())
                .frame(width: 200)
        }
        .padding(40)
    }
}

struct AppLogo: View {
    var size: CGFloat = 34
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.28, style: .continuous).fill(Theme.gradient)
            Image(systemName: "film.stack")
                .font(.system(size: size * 0.46, weight: .semibold))
                .foregroundStyle(.white)
        }
        .frame(width: size, height: size)
        .shadow(color: Theme.violet.opacity(0.35), radius: size * 0.2, y: size * 0.08)
    }
}
