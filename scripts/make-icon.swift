// Menggambar ikon aplikasi Frameshot → AppIcon.iconset (lalu diubah ke .icns oleh build.sh).
// Pakai: swift scripts/make-icon.swift <folder-iconset>
import AppKit

let outDir = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "AppIcon.iconset")
try? FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)

func render(_ px: Int) -> Data {
    let s = CGFloat(px)
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: px, pixelsHigh: px, bitsPerSample: 8,
                               samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                               colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    let ctx = NSGraphicsContext.current!.cgContext

    // Kotak membulat ala macOS (inset ~10%).
    let inset = s * 0.1
    let rect = CGRect(x: inset, y: inset, width: s - inset * 2, height: s - inset * 2)
    let radius = rect.width * 0.225
    let path = CGPath(roundedRect: rect, cornerWidth: radius, cornerHeight: radius, transform: nil)

    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: -s * 0.012), blur: s * 0.03,
                  color: NSColor.black.withAlphaComponent(0.35).cgColor)
    ctx.addPath(path); ctx.setFillColor(NSColor.black.cgColor); ctx.fillPath()
    ctx.restoreGState()

    ctx.saveGState()
    ctx.addPath(path); ctx.clip()
    let colors = [NSColor(red: 0.39, green: 0.40, blue: 0.95, alpha: 1).cgColor,
                  NSColor(red: 0.62, green: 0.36, blue: 0.97, alpha: 1).cgColor,
                  NSColor(red: 0.93, green: 0.30, blue: 0.60, alpha: 1).cgColor] as CFArray
    let grad = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 0.55, 1])!
    ctx.drawLinearGradient(grad, start: CGPoint(x: rect.minX, y: rect.maxY), end: CGPoint(x: rect.maxX, y: rect.minY), options: [])
    // Kilau halus di atas.
    let gloss = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(),
                           colors: [NSColor.white.withAlphaComponent(0.22).cgColor, NSColor.white.withAlphaComponent(0).cgColor] as CFArray,
                           locations: [0, 1])!
    ctx.drawLinearGradient(gloss, start: CGPoint(x: 0, y: rect.maxY), end: CGPoint(x: 0, y: rect.midY), options: [])

    // Tiga "frame" bertumpuk.
    let fw = rect.width * 0.50, fh = fw * 0.62
    for i in (0..<3).reversed() {
        let off = CGFloat(i) * rect.width * 0.075
        let fr = CGRect(x: rect.midX - fw / 2 + off - rect.width * 0.06,
                        y: rect.midY - fh / 2 + off - rect.width * 0.05, width: fw, height: fh)
        let p = CGPath(roundedRect: fr, cornerWidth: fw * 0.09, cornerHeight: fw * 0.09, transform: nil)
        ctx.addPath(p)
        ctx.setFillColor(NSColor.white.withAlphaComponent(i == 0 ? 1 : (i == 1 ? 0.55 : 0.3)).cgColor)
        ctx.fillPath()
        if i == 0 {
            // Tombol play / aperture kecil di frame depan.
            let c = CGPoint(x: fr.midX, y: fr.midY)
            let r = fh * 0.24
            ctx.setFillColor(NSColor(red: 0.62, green: 0.36, blue: 0.97, alpha: 1).cgColor)
            ctx.move(to: CGPoint(x: c.x - r * 0.6, y: c.y - r))
            ctx.addLine(to: CGPoint(x: c.x - r * 0.6, y: c.y + r))
            ctx.addLine(to: CGPoint(x: c.x + r, y: c.y))
            ctx.closePath()
            ctx.fillPath()
        }
    }
    ctx.restoreGState()
    NSGraphicsContext.restoreGraphicsState()
    return rep.representation(using: .png, properties: [:])!
}

for (name, px) in [("16x16", 16), ("16x16@2x", 32), ("32x32", 32), ("32x32@2x", 64), ("128x128", 128),
                   ("128x128@2x", 256), ("256x256", 256), ("256x256@2x", 512), ("512x512", 512), ("512x512@2x", 1024)] {
    try! render(px).write(to: outDir.appendingPathComponent("icon_\(name).png"))
}
print("ikon dibuat di \(outDir.path)")
