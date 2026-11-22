import SwiftUI
import ImageIO
import FrameshotCore

struct SummarySheet: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let s = state.summary ?? RunSummary()
        VStack(spacing: 20) {
            VStack(spacing: 10) {
                ZStack {
                    Circle().fill(iconBackground(s)).frame(width: 64, height: 64)
                    Image(systemName: iconName(s))
                        .font(.system(size: 28, weight: .bold))
                        .foregroundStyle(.white)
                }
                Text(title(s)).font(.system(size: 22, weight: .bold, design: .rounded))
                Text(subtitle(s)).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }

            HStack(spacing: 10) {
                statBox("Gambar", formatCount(s.frames), "photo.stack")
                statBox("Ukuran", formatBytes(Double(s.bytes)), "internaldrive")
                statBox("Waktu", formatTimecode(s.elapsed, millis: false), "clock")
            }

            if !s.samples.isEmpty {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 8) {
                    ForEach(s.samples.prefix(8), id: \.self) { url in
                        ThumbnailView(url: url)
                            .onTapGesture(count: 2) { state.reveal(url) }
                    }
                }
            }

            if !s.errors.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(Array(s.errors.enumerated()), id: \.offset) { _, e in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(e.0).font(.system(size: 12, weight: .semibold))
                            Text(e.1).font(.system(size: 11, design: .monospaced)).foregroundStyle(.secondary)
                                .lineLimit(4).textSelection(.enabled)
                        }
                    }
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 10).fill(Color.red.opacity(0.08)))
            }

            HStack(spacing: 10) {
                Button("Tutup") { dismiss() }
                    .buttonStyle(SoftButtonStyle())
                    .keyboardShortcut(.cancelAction)
                if let folder = s.folders.first {
                    Button {
                        if s.folders.count > 1 { s.folders.forEach { state.reveal($0) } } else { state.reveal(folder) }
                    } label: {
                        Label(s.folders.count > 1 ? "Buka \(s.folders.count) Folder" : "Buka Folder", systemImage: "folder.fill")
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .frame(width: 200)
                    .keyboardShortcut(.defaultAction)
                }
            }
        }
        .padding(28)
        .frame(width: 560)
    }

    private func title(_ s: RunSummary) -> String {
        if !s.errors.isEmpty && s.frames == 0 { return "Ekstraksi gagal" }
        if s.cancelled { return "Dibatalkan" }
        if !s.errors.isEmpty { return "Selesai, dengan kendala" }
        return "Selesai!"
    }

    private func subtitle(_ s: RunSummary) -> String {
        if s.cancelled { return "\(formatCount(s.frames)) gambar sempat tersimpan." }
        if s.videos > 1 { return "\(formatCount(s.frames)) gambar dari \(s.videos) video sudah tersimpan." }
        return "\(formatCount(s.frames)) gambar sudah tersimpan."
    }

    private func iconName(_ s: RunSummary) -> String {
        if !s.errors.isEmpty && s.frames == 0 { return "xmark" }
        if s.cancelled { return "stop.fill" }
        return "checkmark"
    }

    private func iconBackground(_ s: RunSummary) -> AnyShapeStyle {
        if !s.errors.isEmpty && s.frames == 0 { return AnyShapeStyle(Color.red) }
        if s.cancelled { return AnyShapeStyle(Color.orange) }
        return AnyShapeStyle(Theme.gradient)
    }

    private func statBox(_ label: String, _ value: String, _ symbol: String) -> some View {
        VStack(spacing: 4) {
            Image(systemName: symbol).font(.system(size: 13)).foregroundStyle(Theme.accent)
            Text(value).font(.system(size: 16, weight: .bold, design: .rounded)).monospacedDigit()
            Text(label).font(.system(size: 11)).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 12).fill(Theme.cardFill))
    }
}

/// Thumbnail ringan dari file gambar hasil (pakai ImageIO, tidak memuat gambar penuh).
struct ThumbnailView: View {
    let url: URL
    @State private var image: NSImage?

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 8).fill(Color.black.opacity(0.85))
            if let image {
                Image(nsImage: image).resizable().aspectRatio(contentMode: .fill)
            }
        }
        .aspectRatio(16 / 9, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .help(url.lastPathComponent)
        .task {
            let u = url
            image = await Task.detached { () -> NSImage? in
                guard let src = CGImageSourceCreateWithURL(u as CFURL, nil),
                      let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, [
                        kCGImageSourceCreateThumbnailFromImageAlways: true,
                        kCGImageSourceThumbnailMaxPixelSize: 360,
                        kCGImageSourceCreateThumbnailWithTransform: true,
                      ] as CFDictionary) else { return nil }
                return NSImage(cgImage: cg, size: NSSize(width: cg.width, height: cg.height))
            }.value
        }
    }
}
