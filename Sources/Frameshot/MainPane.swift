import SwiftUI
import FrameshotCore

struct MainPane: View {
    @Environment(AppState.self) private var state

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            header
            if state.videos.isEmpty {
                EmptyDropZone()
            } else {
                if let item = state.selected {
                    PreviewSection(item: item)
                } else {
                    Spacer()
                }
                QueueStrip()
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 40)
        .padding(.bottom, 20)
    }

    private var header: some View {
        HStack(spacing: 12) {
            AppLogo(size: 34)
            VStack(alignment: .leading, spacing: 1) {
                Text("Frameshot").font(.system(size: 18, weight: .bold, design: .rounded))
                Text("Screenshot video per frame").font(.system(size: 12)).foregroundStyle(.secondary)
            }
            Spacer()
            if !state.videos.isEmpty {
                Button { state.openVideoPanel() } label: {
                    Label("Tambah Video", systemImage: "plus")
                }
                .buttonStyle(SoftButtonStyle())
                .disabled(state.phase == .running)
                Button { state.openFolderPanel() } label: {
                    Label("Folder", systemImage: "folder.badge.plus")
                }
                .buttonStyle(SoftButtonStyle())
                .disabled(state.phase == .running)
                .help("Tambahkan semua video di dalam satu folder")
            }
        }
        .background(WindowDragArea().padding(.top, -40))
    }
}

// MARK: - Zona kosong

struct EmptyDropZone: View {
    @Environment(AppState.self) private var state
    @State private var hover = false

    var body: some View {
        VStack(spacing: 18) {
            ZStack {
                Circle().fill(Theme.softGradient).frame(width: 112, height: 112)
                Image(systemName: "play.rectangle.on.rectangle.fill")
                    .font(.system(size: 44, weight: .medium))
                    .foregroundStyle(Theme.gradient)
            }
            .scaleEffect(hover ? 1.04 : 1)
            VStack(spacing: 6) {
                Text("Seret video ke sini").font(.system(size: 22, weight: .bold, design: .rounded))
                Text("atau pilih file dari Mac kamu. Bisa banyak sekaligus.")
                    .foregroundStyle(.secondary)
            }
            HStack(spacing: 10) {
                Button { state.openVideoPanel() } label: {
                    Label("Pilih Video", systemImage: "film").padding(.horizontal, 10)
                }
                .buttonStyle(PrimaryButtonStyle())
                .frame(width: 170)
                Button { state.openFolderPanel() } label: {
                    Label("Pilih Folder", systemImage: "folder").padding(.vertical, 4)
                }
                .buttonStyle(SoftButtonStyle())
            }
            Text("MP4 · MKV · MOV · WEBM · AVI · dan lainnya")
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(.tertiary)
                .padding(.top, 4)

            HStack(spacing: 22) {
                tip("sparkles.tv", "Deteksi ganti adegan", "pas untuk anime")
                tip("timer", "Interval waktu", "misal tiap 1 detik")
                tip("camera.viewfinder", "Ambil 1 frame", "dari pratinjau")
            }
            .padding(.top, 18)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .strokeBorder(Color.primary.opacity(hover ? 0.22 : 0.12), style: StrokeStyle(lineWidth: 1.5, dash: [8, 7]))
        )
        .onHover { h in withAnimation(.easeOut(duration: 0.2)) { hover = h } }
    }

    private func tip(_ symbol: String, _ title: String, _ sub: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: symbol).foregroundStyle(Theme.accent)
            VStack(alignment: .leading, spacing: 0) {
                Text(title).font(.system(size: 12, weight: .semibold))
                Text(sub).font(.system(size: 11)).foregroundStyle(.secondary)
            }
        }
    }
}

// MARK: - Pratinjau

struct PreviewSection: View {
    @Environment(AppState.self) private var state
    let item: VideoItem
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            previewImage
            if let info = item.info {
                RangeScrubber(item: item, info: info)
                controls(info)
                infoPills(info)
            }
        }
        .focusable()
        .focusEffectDisabled()
        .focused($focused)
        .onKeyPress(.leftArrow, phases: .down) { press in
            if press.modifiers.contains(.shift) { state.step(seconds: -1) } else { state.step(frames: -1) }
            return .handled
        }
        .onKeyPress(.rightArrow, phases: .down) { press in
            if press.modifiers.contains(.shift) { state.step(seconds: 1) } else { state.step(frames: 1) }
            return .handled
        }
        .onKeyPress(characters: CharacterSet(charactersIn: "iIoO")) { press in
            if press.characters.lowercased() == "i" { state.setRangeStart() } else { state.setRangeEnd() }
            return .handled
        }
    }

    private var previewImage: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.black)
            if let img = state.previewImage {
                Image(nsImage: img)
                    .resizable()
                    .interpolation(.high)
                    .aspectRatio(contentMode: .fit)
            } else if let err = item.loadError {
                VStack(spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill").font(.largeTitle).foregroundStyle(.yellow)
                    Text("Video tidak bisa dibaca").font(.headline).foregroundStyle(.white)
                    Text(err).font(.caption).foregroundStyle(.white.opacity(0.7)).multilineTextAlignment(.center)
                }
                .padding()
            } else {
                ProgressView().controlSize(.large).tint(.white)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(alignment: .topLeading) {
            if item.info != nil {
                HStack(spacing: 6) {
                    Text(formatTimecode(state.currentTime, forceHours: item.duration >= 3600))
                        .font(.system(size: 12, weight: .semibold, design: .monospaced))
                    Text("·").foregroundStyle(.white.opacity(0.5))
                    Text("Frame \(formatCount(state.currentFrameNumber))")
                        .font(.system(size: 12, weight: .medium)).monospacedDigit()
                    if state.previewLoading {
                        ProgressView().controlSize(.mini).tint(.white)
                    }
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 10).padding(.vertical, 6)
                .background(Capsule().fill(.black.opacity(0.55)))
                .padding(12)
            }
        }
        .overlay(alignment: .topTrailing) {
            if item.info != nil {
                Button { state.captureCurrentFrame() } label: {
                    Label("Ambil frame ini", systemImage: "camera.fill")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 11).padding(.vertical, 7)
                        .background(Capsule().fill(.black.opacity(0.55)))
                        .overlay(Capsule().strokeBorder(.white.opacity(0.15)))
                }
                .buttonStyle(.plain)
                .help("Simpan frame ini dengan resolusi penuh (⌘S)")
                .padding(12)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .frame(minHeight: 260)
        .onTapGesture { focused = true }
    }

    private func controls(_ info: VideoInfo) -> some View {
        HStack(spacing: 6) {
            IconButton(symbol: "gobackward", help: "Mundur 1 detik (⇧←)") { state.step(seconds: -1) }
            IconButton(symbol: "backward.frame.fill", help: "Frame sebelumnya (←)") { state.step(frames: -1) }
            IconButton(symbol: "forward.frame.fill", help: "Frame berikutnya (→)") { state.step(frames: 1) }
            IconButton(symbol: "goforward", help: "Maju 1 detik (⇧→)") { state.step(seconds: 1) }
            Text("\(formatTimecode(state.currentTime, forceHours: info.duration >= 3600)) / \(formatTimecode(info.duration, millis: false))")
                .font(.system(size: 12, weight: .medium, design: .monospaced))
                .foregroundStyle(.secondary)
                .padding(.leading, 6)
            Spacer()
            Button { state.setRangeStart() } label: {
                Label("Set Awal", systemImage: "chevron.left.to.line")
            }
            .buttonStyle(SoftButtonStyle(compact: true))
            .help("Jadikan posisi ini awal rentang (I)")
            Button { state.setRangeEnd() } label: {
                Label("Set Akhir", systemImage: "chevron.right.to.line")
            }
            .buttonStyle(SoftButtonStyle(compact: true))
            .help("Jadikan posisi ini akhir rentang (O)")
        }
    }

    private func infoPills(_ info: VideoInfo) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                InfoPill(symbol: "rectangle.dashed", text: "\(info.width)×\(info.height)")
                InfoPill(symbol: "speedometer", text: info.fpsDisplay)
                InfoPill(symbol: "clock", text: formatTimecode(info.duration, millis: false))
                InfoPill(symbol: "square.stack.3d.up", text: "\(formatCount(info.frameCount)) frame")
                InfoPill(symbol: "cpu", text: info.codecDisplay + (info.isTenBit ? " 10-bit" : ""))
                InfoPill(symbol: "internaldrive", text: formatBytes(Double(info.fileSize)))
                if info.subtitleStreams > 0 {
                    InfoPill(symbol: "captions.bubble", text: "\(info.subtitleStreams) subtitle")
                }
            }
        }
    }
}

// MARK: - Scrubber dengan rentang

struct RangeScrubber: View {
    @Environment(AppState.self) private var state
    let item: VideoItem
    let info: VideoInfo
    @State private var dragging: Handle?
    @State private var hoverX: CGFloat?

    enum Handle { case playhead, start, end }

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let d = max(info.duration, 0.001)
            let x = { (t: Double) -> CGFloat in CGFloat(t / d) * w }
            ZStack(alignment: .leading) {
                Capsule().fill(Color.primary.opacity(0.09)).frame(height: 6)
                if item.customRange {
                    Capsule().fill(Theme.gradient)
                        .frame(width: max(4, x(item.rangeEnd) - x(item.rangeStart)), height: 6)
                        .offset(x: x(item.rangeStart))
                    handle(at: x(item.rangeStart), symbol: "chevron.compact.left")
                    handle(at: x(item.rangeEnd), symbol: "chevron.compact.right")
                } else {
                    Capsule().fill(Theme.gradient.opacity(0.35)).frame(width: w, height: 6)
                }
                if let hx = hoverX, dragging == nil {
                    Rectangle().fill(Color.primary.opacity(0.25)).frame(width: 1, height: 16).offset(x: hx)
                }
                Circle()
                    .fill(.white)
                    .frame(width: 16, height: 16)
                    .shadow(color: .black.opacity(0.3), radius: 3, y: 1)
                    .overlay(Circle().strokeBorder(Theme.violet, lineWidth: 2.5))
                    .offset(x: x(state.currentTime) - 8)
            }
            .frame(height: 24)
            .contentShape(Rectangle())
            .onContinuousHover { phase in
                if case .active(let p) = phase { hoverX = p.x } else { hoverX = nil }
            }
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { g in
                        let t = Double(min(max(0, g.location.x), w) / w) * d
                        if dragging == nil {
                            dragging = .playhead
                            if item.customRange {
                                if abs(g.startLocation.x - x(item.rangeStart)) < 9 { dragging = .start }
                                else if abs(g.startLocation.x - x(item.rangeEnd)) < 9 { dragging = .end }
                            }
                        }
                        switch dragging {
                        case .start:
                            item.rangeStart = min(t, item.rangeEnd - info.frameDuration)
                            state.seek(to: item.rangeStart)
                        case .end:
                            item.rangeEnd = max(t, item.rangeStart + info.frameDuration)
                            state.seek(to: item.rangeEnd - info.frameDuration)
                        default:
                            state.seek(to: t)
                        }
                    }
                    .onEnded { _ in dragging = nil }
            )
        }
        .frame(height: 24)
    }

    private func handle(at x: CGFloat, symbol: String) -> some View {
        RoundedRectangle(cornerRadius: 3)
            .fill(Theme.violet)
            .frame(width: 8, height: 20)
            .overlay(Image(systemName: symbol).font(.system(size: 8, weight: .heavy)).foregroundStyle(.white))
            .offset(x: x - 4)
    }
}

// MARK: - Antrean video

struct QueueStrip: View {
    @Environment(AppState.self) private var state

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Antrean").font(.system(size: 13, weight: .semibold))
                Text("\(state.includedVideos.count)/\(state.videos.count) dipilih")
                    .font(.system(size: 12)).foregroundStyle(.secondary)
                Spacer()
                if state.videos.count > 1 {
                    Button("Hapus semua") { state.clearAll() }
                        .buttonStyle(.plain)
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                        .disabled(state.phase == .running)
                }
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(state.videos) { item in
                        QueueCard(item: item, selected: item.id == state.selectedID)
                            .onTapGesture { state.select(item) }
                    }
                    Button { state.openVideoPanel() } label: {
                        VStack(spacing: 6) {
                            Image(systemName: "plus").font(.system(size: 18, weight: .semibold))
                            Text("Tambah").font(.system(size: 11, weight: .medium))
                        }
                        .foregroundStyle(.secondary)
                        .frame(width: 92, height: 92)
                        .background(RoundedRectangle(cornerRadius: 12)
                            .strokeBorder(Color.primary.opacity(0.15), style: StrokeStyle(lineWidth: 1.2, dash: [5, 4])))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(state.phase == .running)
                }
                .padding(2)
            }
        }
    }
}

struct QueueCard: View {
    @Environment(AppState.self) private var state
    @Bindable var item: VideoItem
    let selected: Bool
    @State private var hover = false

    var body: some View {
        HStack(spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 8).fill(Color.black.opacity(0.85))
                if let t = item.thumbnail {
                    Image(nsImage: t).resizable().aspectRatio(contentMode: .fill)
                } else if item.loadError != nil {
                    Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.yellow)
                } else {
                    ProgressView().controlSize(.small).tint(.white)
                }
            }
            .frame(width: 112, height: 63)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay(alignment: .bottomTrailing) {
                if let info = item.info {
                    Text(formatTimecode(info.duration, millis: false))
                        .font(.system(size: 9.5, weight: .semibold, design: .monospaced))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 5).padding(.vertical, 2)
                        .background(Capsule().fill(.black.opacity(0.65)))
                        .padding(4)
                }
            }

            VStack(alignment: .leading, spacing: 5) {
                Text(item.name)
                    .font(.system(size: 12, weight: .semibold))
                    .lineLimit(2)
                    .truncationMode(.middle)
                    .frame(width: 130, alignment: .leading)
                statusView
                Toggle("Ikut diekstrak", isOn: $item.included)
                    .toggleStyle(.checkbox)
                    .font(.system(size: 11))
                    .disabled(item.info == nil || state.phase == .running)
            }
        }
        .padding(10)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(selected ? AnyShapeStyle(Theme.softGradient) : AnyShapeStyle(Theme.cardFill))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(selected ? AnyShapeStyle(Theme.gradient) : AnyShapeStyle(Theme.cardStroke),
                              lineWidth: selected ? 1.5 : 1)
        )
        .overlay(alignment: .topTrailing) {
            if hover && state.phase != .running {
                Button { state.remove(item) } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 15))
                        .symbolRenderingMode(.hierarchical)
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .padding(5)
                .help("Hapus dari antrean")
            }
        }
        .contentShape(Rectangle())
        .onHover { hover = $0 }
    }

    @ViewBuilder private var statusView: some View {
        switch item.status {
        case .ready:
            if item.loadError != nil {
                label("Gagal dibaca", "exclamationmark.triangle.fill", .orange)
            } else if item.customRange {
                label("\(formatTimecode(item.rangeStart, millis: false))–\(formatTimecode(item.rangeEnd, millis: false))", "scissors", .secondary)
            } else {
                label("Seluruh video", "film", .secondary)
            }
        case .running: label("Memproses…", "arrow.triangle.2.circlepath", Theme.accent)
        case .done(let n): label("\(formatCount(n)) frame", "checkmark.circle.fill", .green)
        case .failed: label("Gagal", "xmark.octagon.fill", .red)
        case .cancelled: label("Dibatalkan", "stop.circle.fill", .orange)
        }
    }

    private func label(_ text: String, _ symbol: String, _ color: Color) -> some View {
        HStack(spacing: 4) {
            Image(systemName: symbol).font(.system(size: 10, weight: .semibold))
            Text(text).font(.system(size: 11)).monospacedDigit()
        }
        .foregroundStyle(color)
    }
}
