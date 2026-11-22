import SwiftUI
import FrameshotCore

struct SettingsSidebar: View {
    @Environment(AppState.self) private var state
    @State private var showAdvanced = false

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    HStack {
                        Text("Pengaturan").font(.system(size: 15, weight: .bold, design: .rounded))
                        Spacer()
                        Menu {
                            Button("Kembalikan ke bawaan") { state.resetSettings() }
                        } label: {
                            Image(systemName: "ellipsis.circle").font(.system(size: 14))
                        }
                        .menuStyle(.borderlessButton)
                        .menuIndicator(.hidden)
                        .fixedSize()
                    }
                    .padding(.top, 2)

                    ModeSection()
                    RangeSection()
                    FormatSection()
                    SizeSection()
                    NamingSection()
                    OutputSection()
                    AdvancedSection(expanded: $showAdvanced)
                }
                .padding(.horizontal, 20)
                .padding(.top, 40)
                .padding(.bottom, 20)
            }
            .scrollIndicators(.automatic)
            .disabled(state.phase == .running)
            .onReceive(NotificationCenter.default.publisher(for: .init("frameshot.debug.advanced"))) { _ in
                showAdvanced = true
            }

            FooterBar()
        }
        .background(Color.primary.opacity(0.018))
    }
}

// MARK: - Mode

struct ModeSection: View {
    @Environment(AppState.self) private var state

    var body: some View {
        @Bindable var state = state
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "Mode Screenshot", symbol: "camera.aperture")
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 8) {
                ForEach(ExtractionMode.allCases) { mode in
                    ModeTile(mode: mode, selected: state.settings.mode == mode) {
                        withAnimation(.spring(duration: 0.3)) { state.settings.mode = mode }
                    }
                }
            }
            Card(padding: 12) {
                VStack(alignment: .leading, spacing: 10) {
                    modeControls
                    Text(state.settings.mode.explanation)
                        .font(.system(size: 11.5))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    @ViewBuilder private var modeControls: some View {
        @Bindable var state = state
        switch state.settings.mode {
        case .allFrames:
            HStack(spacing: 8) {
                Image(systemName: "square.stack.3d.down.right.fill").foregroundStyle(Theme.gradient)
                if let n = state.estimatedFrames {
                    Text("\(formatCount(n)) frame akan disimpan").font(.system(size: 13, weight: .semibold))
                } else {
                    Text("Semua frame akan disimpan").font(.system(size: 13, weight: .semibold))
                }
            }
            if let n = state.estimatedFrames, n > 5000 {
                Label("Jumlahnya besar. Pastikan ruang disk cukup.", systemImage: "exclamationmark.triangle.fill")
                    .font(.system(size: 11.5, weight: .medium))
                    .foregroundStyle(.orange)
            }
        case .everyNFrames:
            HStack {
                Text("Ambil 1 dari tiap").font(.system(size: 13))
                Spacer()
                NumberStepper(value: intBinding(\.everyN), range: 1...100_000, step: 1, suffix: "frame")
            }
            quickChips([2, 6, 12, 24, 48, 120].map { ("\($0)", Double($0)) }, current: Double(state.settings.everyN)) {
                state.settings.everyN = Int($0)
            }
            if let fps = state.selected?.info?.fps {
                Text("≈ \(String(format: "%.2f", fps / Double(max(1, state.settings.everyN)))) gambar per detik")
                    .font(.system(size: 11.5, weight: .medium)).foregroundStyle(Theme.accent)
            }
        case .interval:
            HStack {
                Text("Ambil setiap").font(.system(size: 13))
                Spacer()
                NumberStepper(value: $state.settings.intervalSeconds, range: 0.04...3600,
                              step: state.settings.intervalSeconds < 1 ? 0.1 : 1, decimals: 2, suffix: "detik")
            }
            quickChips([("0.5s", 0.5), ("1s", 1), ("2s", 2), ("5s", 5), ("10s", 10), ("30s", 30), ("1m", 60)],
                       current: state.settings.intervalSeconds) { state.settings.intervalSeconds = $0 }
        case .fixedCount:
            HStack {
                Text("Jumlah gambar").font(.system(size: 13))
                Spacer()
                NumberStepper(value: intBinding(\.fixedCount), range: 1...100_000, step: 10)
            }
            quickChips([10, 25, 50, 100, 250, 500].map { ("\($0)", Double($0)) }, current: Double(state.settings.fixedCount)) {
                state.settings.fixedCount = Int($0)
            }
            if let item = state.selected, item.info != nil {
                let gap = item.effectiveDuration / Double(max(1, state.settings.fixedCount))
                Text("≈ 1 gambar tiap \(gap >= 1 ? String(format: "%.1f detik", gap) : String(format: "%.0f ms", gap * 1000))")
                    .font(.system(size: 11.5, weight: .medium)).foregroundStyle(Theme.accent)
            }
        case .sceneChange:
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("Sensitivitas").font(.system(size: 13))
                    Spacer()
                    Text(sensitivityLabel).font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.accent)
                }
                // Sensitivitas tinggi = ambang rendah.
                Slider(value: Binding(get: { 1 - state.settings.sceneThreshold },
                                      set: { state.settings.sceneThreshold = ((1 - $0) * 100).rounded() / 100 }),
                       in: 0.4...0.95)
                    .tint(Theme.violet)
                HStack {
                    Text("Sedikit gambar").font(.system(size: 10.5)).foregroundStyle(.tertiary)
                    Spacer()
                    Text("Banyak gambar").font(.system(size: 10.5)).foregroundStyle(.tertiary)
                }
            }
        }
    }

    private var sensitivityLabel: String {
        let t = state.settings.sceneThreshold
        switch t {
        case ..<0.15: return "Sangat tinggi"
        case ..<0.25: return "Tinggi"
        case ..<0.38: return "Sedang"
        case ..<0.5: return "Rendah"
        default: return "Sangat rendah"
        }
    }

    private func intBinding(_ kp: WritableKeyPath<ExtractionSettings, Int>) -> Binding<Double> {
        Binding(get: { Double(state.settings[keyPath: kp]) }, set: { state.settings[keyPath: kp] = Int($0) })
    }

    private func quickChips(_ items: [(String, Double)], current: Double, set: @escaping (Double) -> Void) -> some View {
        HStack(spacing: 5) {
            ForEach(items, id: \.0) { item in
                Chip(title: item.0, selected: abs(current - item.1) < 0.0001) { set(item.1) }
            }
        }
    }
}

struct ModeTile: View {
    let mode: ExtractionMode
    let selected: Bool
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            VStack(spacing: 7) {
                Image(systemName: mode.symbol)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(selected ? AnyShapeStyle(Theme.gradient) : AnyShapeStyle(Color.secondary))
                    .frame(height: 20)
                Text(mode.title)
                    .font(.system(size: 11, weight: selected ? .semibold : .medium))
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .minimumScaleFactor(0.85)
            }
            .frame(maxWidth: .infinity, minHeight: 66)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(selected ? AnyShapeStyle(Theme.softGradient) : AnyShapeStyle(Color.primary.opacity(hover ? 0.07 : 0.04)))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(selected ? AnyShapeStyle(Theme.gradient) : AnyShapeStyle(Theme.cardStroke),
                                  lineWidth: selected ? 1.5 : 1)
            )
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
    }
}

// MARK: - Rentang

struct RangeSection: View {
    @Environment(AppState.self) private var state

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "Rentang Waktu", symbol: "scissors")
            if let item = state.selected, let info = item.info {
                RangeEditor(item: item, info: info)
            } else {
                Text("Pilih video untuk mengatur rentang.")
                    .font(.system(size: 12)).foregroundStyle(.secondary)
            }
        }
    }
}

struct RangeEditor: View {
    @Environment(AppState.self) private var state
    @Bindable var item: VideoItem
    let info: VideoInfo

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            SegmentedChoice(selection: Binding(get: { item.customRange }, set: { v in
                withAnimation(.easeOut(duration: 0.2)) {
                    item.customRange = v
                    if v && item.rangeEnd <= item.rangeStart { item.rangeStart = 0; item.rangeEnd = info.duration }
                }
            }), options: [(false, "Seluruh video"), (true, "Pilih rentang")])

            if item.customRange {
                Card(padding: 12) {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack(spacing: 10) {
                            TimeField(label: "Dari", value: item.rangeStart, maxValue: info.duration) { v in
                                item.rangeStart = min(v, item.rangeEnd - info.frameDuration)
                                state.seek(to: item.rangeStart)
                            }
                            Image(systemName: "arrow.right").foregroundStyle(.tertiary).padding(.top, 16)
                            TimeField(label: "Sampai", value: item.rangeEnd, maxValue: info.duration) { v in
                                item.rangeEnd = max(v, item.rangeStart + info.frameDuration)
                                state.seek(to: item.rangeEnd - info.frameDuration)
                            }
                        }
                        HStack {
                            Text("Durasi \(formatTimecode(item.effectiveDuration))")
                                .font(.system(size: 11.5, weight: .medium)).monospacedDigit()
                                .foregroundStyle(Theme.accent)
                            Spacer()
                            Text("Tip: tekan I / O di pratinjau")
                                .font(.system(size: 10.5)).foregroundStyle(.tertiary)
                        }
                    }
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
    }
}

// MARK: - Format

struct FormatSection: View {
    @Environment(AppState.self) private var state

    var body: some View {
        @Bindable var state = state
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "Format Gambar", symbol: "photo")
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 4), spacing: 6) {
                ForEach(ImageFormat.allCases) { f in
                    Chip(title: f.label, selected: state.settings.format == f, disabled: !state.isAvailable(f)) {
                        state.settings.format = f
                    }
                    .help(state.isAvailable(f) ? f.hint : "Butuh ffmpeg dengan dukungan libwebp")
                }
            }
            Card(padding: 12) {
                VStack(alignment: .leading, spacing: 8) {
                    if state.settings.format.hasQuality {
                        HStack {
                            Text("Kualitas").font(.system(size: 13))
                            Spacer()
                            Text("\(state.settings.quality)")
                                .font(.system(size: 13, weight: .semibold, design: .rounded)).monospacedDigit()
                                .foregroundStyle(Theme.accent)
                        }
                        Slider(value: Binding(get: { Double(state.settings.quality) },
                                              set: { state.settings.quality = Int($0.rounded()) }),
                               in: 40...100, step: 1)
                            .tint(Theme.violet)
                    }
                    Text(state.settings.format.hint)
                        .font(.system(size: 11.5)).foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
}

// MARK: - Ukuran

struct SizeSection: View {
    @Environment(AppState.self) private var state

    var body: some View {
        @Bindable var state = state
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "Resolusi", symbol: "arrow.up.left.and.arrow.down.right")
            HStack {
                Picker("", selection: $state.settings.resolution) {
                    ForEach(ResolutionPreset.allCases) { r in Text(r.label).tag(r) }
                }
                .labelsHidden()
                .frame(maxWidth: 160)
                Spacer()
                if let info = state.selected?.info {
                    let (w, h) = state.settings.outputSize(for: info.width, info.height)
                    Text("→ \(w) × \(h)")
                        .font(.system(size: 12, weight: .medium, design: .monospaced))
                        .foregroundStyle(.secondary)
                }
            }
            if state.settings.resolution == .custom {
                HStack {
                    Text("Lebar").font(.system(size: 13))
                    Spacer()
                    NumberStepper(value: Binding(get: { Double(state.settings.customWidth) },
                                                 set: { state.settings.customWidth = Int($0) }),
                                  range: 64...7680, step: 64, suffix: "px")
                }
            }
            Text("Video tidak akan diperbesar melebihi ukuran aslinya.")
                .font(.system(size: 11)).foregroundStyle(.tertiary)
        }
    }
}

// MARK: - Penamaan

struct NamingSection: View {
    @Environment(AppState.self) private var state

    var body: some View {
        @Bindable var state = state
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "Nama File", symbol: "textformat")
            TextField("Awalan nama (kosong = nama video)", text: $state.settings.customPrefix)
                .textFieldStyle(.plain)
                .font(.system(size: 13))
                .padding(.horizontal, 10).padding(.vertical, 7)
                .background(RoundedRectangle(cornerRadius: 8).fill(Color.primary.opacity(0.06)))
            SegmentedChoice(selection: $state.settings.naming,
                            options: NamingStyle.allCases.map { ($0, $0.label) })
            HStack(spacing: 6) {
                Image(systemName: "doc").font(.system(size: 11)).foregroundStyle(.secondary)
                Text(exampleFilename(settings: state.settings, videoName: state.selected?.name ?? "Episode 01"))
                    .font(.system(size: 11.5, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            .padding(.horizontal, 2)
        }
    }
}

// MARK: - Output

struct OutputSection: View {
    @Environment(AppState.self) private var state

    var body: some View {
        @Bindable var state = state
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "Simpan ke", symbol: "folder")
            Button { state.chooseOutputFolder() } label: {
                HStack(spacing: 10) {
                    Image(systemName: "folder.fill")
                        .font(.system(size: 18))
                        .foregroundStyle(Theme.gradient)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(folderTitle).font(.system(size: 13, weight: .semibold)).lineLimit(1)
                        Text(folderSubtitle).font(.system(size: 11)).foregroundStyle(.secondary)
                            .lineLimit(1).truncationMode(.head)
                    }
                    Spacer()
                    Text("Ubah").font(.system(size: 12, weight: .medium)).foregroundStyle(Theme.accent)
                }
                .padding(11)
                .background(RoundedRectangle(cornerRadius: 12).fill(Theme.cardFill))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.cardStroke))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help(state.settings.outputFolderPath.isEmpty ? "Disimpan di folder yang sama dengan video" : state.settings.outputFolderPath)

            HStack {
                Toggle("Buat subfolder per video", isOn: $state.settings.createSubfolder)
                    .toggleStyle(.switch).controlSize(.mini)
                    .font(.system(size: 12.5))
                Spacer()
                if !state.settings.outputFolderPath.isEmpty {
                    Button("Pakai folder video") { state.settings.outputFolderPath = "" }
                        .buttonStyle(.plain)
                        .font(.system(size: 11.5))
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private var folderTitle: String {
        state.settings.outputFolderPath.isEmpty ? "Folder yang sama dengan video"
            : URL(fileURLWithPath: state.settings.outputFolderPath).lastPathComponent
    }

    private var folderSubtitle: String {
        if state.settings.outputFolderPath.isEmpty { return "Klik untuk memilih folder lain" }
        return (state.settings.outputFolderPath as NSString).abbreviatingWithTildeInPath
    }
}

// MARK: - Lanjutan

struct AdvancedSection: View {
    @Environment(AppState.self) private var state
    @Binding var expanded: Bool

    var body: some View {
        @Bindable var state = state
        VStack(alignment: .leading, spacing: 10) {
            Button {
                withAnimation(.easeOut(duration: 0.2)) { expanded.toggle() }
            } label: {
                HStack {
                    SectionHeader(title: "Lanjutan", symbol: "slider.horizontal.3")
                    Image(systemName: "chevron.down")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(.secondary)
                        .rotationEffect(.degrees(expanded ? 180 : 0))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if expanded {
                Card(padding: 12) {
                    VStack(alignment: .leading, spacing: 12) {
                        option("Lewati frame duplikat",
                               "Buang frame yang identik dengan sebelumnya. Anime sering mengulang frame yang sama.",
                               $state.settings.skipDuplicates)
                        Divider()
                        option("Akselerasi hardware",
                               "Pakai chip Apple (VideoToolbox) untuk decode video lebih cepat.",
                               $state.settings.hardwareDecode,
                               disabled: !(state.toolchain?.supportsVideoToolbox ?? false))
                        Divider()
                        option("Tampilkan subtitle di gambar",
                               subtitleHint,
                               $state.settings.burnSubtitles,
                               disabled: !(state.toolchain?.supportsSubtitles ?? false))
                        if let tc = state.toolchain {
                            Divider()
                            Text("ffmpeg \(tc.version) · \(tc.ffmpeg.path)")
                                .font(.system(size: 10.5, design: .monospaced))
                                .foregroundStyle(.tertiary)
                                .lineLimit(1).truncationMode(.middle)
                        }
                    }
                }
                .transition(.opacity)
            }
        }
    }

    private var subtitleHint: String {
        if !(state.toolchain?.supportsSubtitles ?? false) {
            return "Tidak tersedia: ffmpeg kamu dibangun tanpa libass."
        }
        return "Tempel subtitle bawaan video (mis. MKV anime) ke dalam screenshot."
    }

    private func option(_ title: String, _ desc: String, _ binding: Binding<Bool>, disabled: Bool = false) -> some View {
        HStack(alignment: .top, spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.system(size: 12.5, weight: .medium))
                Text(desc).font(.system(size: 11)).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
            Toggle("", isOn: binding).toggleStyle(.switch).controlSize(.mini).labelsHidden()
        }
        .opacity(disabled ? 0.5 : 1)
        .disabled(disabled)
    }
}

// MARK: - Footer: perkiraan + tombol

struct FooterBar: View {
    @Environment(AppState.self) private var state

    var body: some View {
        VStack(spacing: 12) {
            if state.phase == .running {
                runningView
            } else {
                estimateRow
                Button { state.startExtraction() } label: {
                    Label(startTitle, systemImage: "bolt.fill")
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(!state.canStart)
                .keyboardShortcut(.return, modifiers: .command)
            }
        }
        .padding(16)
        .background(.bar)
        .overlay(alignment: .top) { Divider() }
    }

    private var startTitle: String {
        let n = state.includedVideos.count
        return n > 1 ? "Ekstrak \(n) Video" : "Mulai Ekstrak"
    }

    private var estimateRow: some View {
        HStack(spacing: 0) {
            stat("Gambar", state.estimatedFrames.map { "≈ " + formatCount($0) } ?? "Otomatis")
            Spacer()
            stat("Perkiraan ukuran", sizeText, warn: notEnoughSpace)
            Spacer()
            stat("Sisa disk", state.freeDiskBytes.map { formatBytes($0) } ?? "—", alignment: .trailing)
        }
    }

    private var sizeText: String {
        if state.includedVideos.isEmpty { return "—" }
        if state.settings.mode == .sceneChange { return "—" }
        if let b = state.estimatedBytes { return "≈ " + formatBytes(b) }
        return state.sampleLoading ? "menghitung…" : "—"
    }

    private var notEnoughSpace: Bool {
        guard let b = state.estimatedBytes, let free = state.freeDiskBytes else { return false }
        return b > free
    }

    private func stat(_ label: String, _ value: String, warn: Bool = false,
                      alignment: HorizontalAlignment = .leading) -> some View {
        VStack(alignment: alignment, spacing: 2) {
            Text(label).font(.system(size: 10.5)).foregroundStyle(.secondary)
            Text(value).font(.system(size: 13, weight: .semibold, design: .rounded)).monospacedDigit()
                .foregroundStyle(warn ? Color.red : Color.primary)
        }
    }

    private var runningView: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(state.runTotal > 1 ? "Video \(state.runIndex + 1) dari \(state.runTotal)" : "Mengekstrak…")
                        .font(.system(size: 11)).foregroundStyle(.secondary)
                    Text(state.runName).font(.system(size: 13, weight: .semibold)).lineLimit(1).truncationMode(.middle)
                }
                Spacer()
                Text("\(Int(state.overallFraction * 100))%")
                    .font(.system(size: 22, weight: .bold, design: .rounded)).monospacedDigit()
                    .foregroundStyle(Theme.gradient)
                    .contentTransition(.numericText())
            }
            GradientProgressBar(value: state.overallFraction)
            HStack {
                Label("\(formatCount(state.progress.frames)) frame", systemImage: "photo.stack")
                Spacer()
                if state.progress.fps > 0 {
                    Text(String(format: "%.0f fps", state.progress.fps))
                }
                Spacer()
                if let eta = state.progress.eta {
                    Text("sisa \(formatTimecode(eta, millis: false))")
                } else {
                    Text(formatTimecode(state.progress.elapsed, millis: false))
                }
            }
            .font(.system(size: 11.5, weight: .medium)).monospacedDigit()
            .foregroundStyle(.secondary)
            Button(role: .cancel) { state.cancelExtraction() } label: {
                Label("Batalkan", systemImage: "stop.fill").frame(maxWidth: .infinity)
            }
            .buttonStyle(SoftButtonStyle())
        }
    }
}

struct GradientProgressBar: View {
    var value: Double
    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.primary.opacity(0.08))
                Capsule().fill(Theme.gradient)
                    .frame(width: max(8, geo.size.width * CGFloat(min(1, max(0, value)))))
                    .animation(.easeOut(duration: 0.35), value: value)
            }
        }
        .frame(height: 8)
    }
}
