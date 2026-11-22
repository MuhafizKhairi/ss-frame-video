import SwiftUI
import AppKit
import FrameshotCore

enum Theme {
    static let indigo = Color(red: 0.39, green: 0.40, blue: 0.95)
    static let violet = Color(red: 0.62, green: 0.36, blue: 0.97)
    static let pink = Color(red: 0.93, green: 0.30, blue: 0.60)

    static let accent = violet
    static let gradient = LinearGradient(colors: [indigo, violet, pink], startPoint: .topLeading, endPoint: .bottomTrailing)
    static let softGradient = LinearGradient(colors: [indigo.opacity(0.16), pink.opacity(0.12)],
                                             startPoint: .topLeading, endPoint: .bottomTrailing)

    static let cardFill = Color.primary.opacity(0.045)
    static let cardStroke = Color.primary.opacity(0.07)
    static let radius: CGFloat = 14
}

/// Kartu dengan latar lembut.
struct Card<Content: View>: View {
    var padding: CGFloat = 14
    @ViewBuilder var content: Content
    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: Theme.radius, style: .continuous).fill(Theme.cardFill))
            .overlay(RoundedRectangle(cornerRadius: Theme.radius, style: .continuous).strokeBorder(Theme.cardStroke))
    }
}

/// Judul bagian di sidebar.
struct SectionHeader: View {
    let title: String
    var symbol: String? = nil
    var trailing: AnyView? = nil
    var body: some View {
        HStack(spacing: 6) {
            if let symbol {
                Image(systemName: symbol).font(.system(size: 11, weight: .semibold)).foregroundStyle(Theme.accent)
            }
            Text(title.uppercased())
                .font(.system(size: 11, weight: .semibold))
                .tracking(0.6)
                .foregroundStyle(.secondary)
            Spacer()
            trailing
        }
    }
}

/// Pilihan kecil berbentuk kapsul.
struct Chip: View {
    let title: String
    var selected: Bool
    var disabled = false
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 12, weight: selected ? .semibold : .medium))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .padding(.horizontal, 6)
                .padding(.vertical, 5)
                .frame(maxWidth: .infinity)
                .foregroundStyle(selected ? Color.white : (disabled ? Color.secondary.opacity(0.5) : Color.primary))
                .background(
                    Capsule().fill(selected ? AnyShapeStyle(Theme.gradient) : AnyShapeStyle(Color.primary.opacity(0.06)))
                )
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .disabled(disabled)
    }
}

/// Tombol utama bergradien.
struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 11)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(enabled ? AnyShapeStyle(Theme.gradient) : AnyShapeStyle(Color.secondary.opacity(0.35)))
            )
            .shadow(color: enabled ? Theme.violet.opacity(configuration.isPressed ? 0.15 : 0.35) : .clear,
                    radius: configuration.isPressed ? 4 : 10, y: 4)
            .scaleEffect(configuration.isPressed ? 0.985 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

/// Tombol sekunder halus.
struct SoftButtonStyle: ButtonStyle {
    var compact = false
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: compact ? 12 : 13, weight: .medium))
            .padding(.horizontal, compact ? 9 : 12)
            .padding(.vertical, compact ? 5 : 7)
            .foregroundStyle(enabled ? Color.primary : Color.secondary.opacity(0.6))
            .background(
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .fill(Color.primary.opacity(configuration.isPressed ? 0.12 : 0.065))
            )
            .contentShape(RoundedRectangle(cornerRadius: 9))
    }
}

/// Tombol ikon bulat.
struct IconButton: View {
    let symbol: String
    var help: String
    var size: CGFloat = 30
    var action: () -> Void
    @State private var hover = false
    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: size * 0.42, weight: .semibold))
                .frame(width: size, height: size)
                .background(Circle().fill(Color.primary.opacity(hover ? 0.12 : 0.065)))
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .help(help)
        .onHover { hover = $0 }
    }
}

/// Info kecil: ikon + teks.
struct InfoPill: View {
    let symbol: String
    let text: String
    var body: some View {
        HStack(spacing: 5) {
            Image(systemName: symbol).font(.system(size: 10, weight: .semibold)).foregroundStyle(.secondary)
            Text(text).font(.system(size: 11.5, weight: .medium)).monospacedDigit()
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 5)
        .background(Capsule().fill(Color.primary.opacity(0.055)))
    }
}

/// Kolom input waktu (HH:MM:SS.mmm / MM:SS / detik).
struct TimeField: View {
    let label: String
    var value: Double
    var maxValue: Double
    var onCommit: (Double) -> Void
    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.system(size: 11, weight: .medium)).foregroundStyle(.secondary)
            TextField("00:00.000", text: $text)
                .textFieldStyle(.plain)
                .font(.system(size: 13, weight: .medium, design: .monospaced))
                .padding(.horizontal, 9)
                .padding(.vertical, 6)
                .background(RoundedRectangle(cornerRadius: 8).fill(Color.primary.opacity(0.06)))
                .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(focused ? Theme.accent.opacity(0.7) : .clear, lineWidth: 1.5))
                .focused($focused)
                .onSubmit(commit)
                .onChange(of: focused) { _, f in if !f { commit() } }
        }
        .onAppear { text = formatTimecode(value, forceHours: maxValue >= 3600) }
        .onChange(of: value) { _, v in if !focused { text = formatTimecode(v, forceHours: maxValue >= 3600) } }
    }

    private func commit() {
        if let v = parseTimecode(text) {
            onCommit(min(max(0, v), maxValue))
        }
        text = formatTimecode(value, forceHours: maxValue >= 3600)
    }
}

/// Kolom angka dengan tombol -/+.
struct NumberStepper: View {
    @Binding var value: Double
    var range: ClosedRange<Double>
    var step: Double
    var decimals = 0
    var suffix = ""
    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 0) {
            stepButton("minus") { set(value - step) }
            TextField("", text: $text)
                .textFieldStyle(.plain)
                .multilineTextAlignment(.center)
                .font(.system(size: 15, weight: .semibold, design: .rounded))
                .monospacedDigit()
                .frame(minWidth: 54)
                .focused($focused)
                .onSubmit(commit)
                .onChange(of: focused) { _, f in if !f { commit() } }
            if !suffix.isEmpty {
                Text(suffix).font(.system(size: 12, weight: .medium)).foregroundStyle(.secondary).padding(.trailing, 4)
            }
            stepButton("plus") { set(value + step) }
        }
        .padding(3)
        .background(RoundedRectangle(cornerRadius: 10).fill(Color.primary.opacity(0.06)))
        .onAppear { text = format(value) }
        .onChange(of: value) { _, v in if !focused { text = format(v) } }
    }

    private func stepButton(_ s: String, _ a: @escaping () -> Void) -> some View {
        Button(action: a) {
            Image(systemName: s).font(.system(size: 11, weight: .bold))
                .frame(width: 28, height: 28)
                .background(RoundedRectangle(cornerRadius: 8).fill(Color.primary.opacity(0.07)))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func format(_ v: Double) -> String {
        decimals == 0 ? String(Int(v.rounded())) : String(format: "%.\(decimals)f", v)
    }

    private func set(_ v: Double) {
        let factor = pow(10, Double(decimals))
        value = min(range.upperBound, max(range.lowerBound, (v * factor).rounded() / factor))
        text = format(value)
    }

    private func commit() {
        if let v = Double(text.replacingOccurrences(of: ",", with: ".")) { set(v) } else { text = format(value) }
    }
}

/// Akses NSWindow untuk membuat titlebar transparan.
struct WindowConfigurator: NSViewRepresentable {
    func makeNSView(context: Context) -> NSView {
        let v = NSView()
        DispatchQueue.main.async {
            guard let w = v.window else { return }
            w.titlebarAppearsTransparent = true
            w.titleVisibility = .hidden
            w.styleMask.insert(.fullSizeContentView)
            w.isMovableByWindowBackground = false
            w.title = "Frameshot"
        }
        return v
    }
    func updateNSView(_ nsView: NSView, context: Context) {}
}

/// Area yang bisa diseret untuk memindahkan jendela (karena titlebar disembunyikan).
struct WindowDragArea: NSViewRepresentable {
    final class DragView: NSView {
        override var mouseDownCanMoveWindow: Bool { true }
        override func mouseDown(with event: NSEvent) {
            if event.clickCount == 2 { window?.performZoom(nil) } else { window?.performDrag(with: event) }
        }
    }
    func makeNSView(context: Context) -> NSView { DragView() }
    func updateNSView(_ nsView: NSView, context: Context) {}
}

/// Segmented control bergaya aplikasi (pengganti picker segmented sistem yang berwarna biru).
struct SegmentedChoice<T: Hashable>: View {
    @Binding var selection: T
    let options: [(T, String)]
    @Namespace private var ns

    var body: some View {
        HStack(spacing: 2) {
            ForEach(Array(options.enumerated()), id: \.offset) { _, opt in
                let isOn = opt.0 == selection
                Button {
                    withAnimation(.spring(duration: 0.28)) { selection = opt.0 }
                } label: {
                    Text(opt.1)
                        .font(.system(size: 12.5, weight: isOn ? .semibold : .medium))
                        .foregroundStyle(isOn ? Color.primary : Color.secondary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                        .background {
                            if isOn {
                                RoundedRectangle(cornerRadius: 7, style: .continuous)
                                    .fill(Color(nsColor: .controlBackgroundColor))
                                    .shadow(color: .black.opacity(0.12), radius: 2, y: 1)
                                    .matchedGeometryEffect(id: "seg", in: ns)
                            }
                        }
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(3)
        .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(Color.primary.opacity(0.07)))
    }
}
