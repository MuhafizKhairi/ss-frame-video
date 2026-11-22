import Foundation
import FrameshotCore

// Uji cepat dari terminal:
//   swift run frameshot-check <video> <folder-output> [mode] [format] [naming] [start] [end]
// mode: all | everyN | interval | count | scene

let args = CommandLine.arguments
guard args.count >= 3 else {
    print("pakai: frameshot-check <video> <folder-output> [mode] [format] [naming] [start] [end]")
    exit(2)
}
guard let tc = FFmpegToolchain.locate() else { print("ffmpeg tidak ditemukan"); exit(1) }
print("ffmpeg \(tc.version) — webp:\(tc.supportsWebP) subs:\(tc.supportsSubtitles) vt:\(tc.supportsVideoToolbox)")

let video = URL(fileURLWithPath: args[1])
let info = try VideoProbe.probe(video, toolchain: tc)
print("info: \(info.width)x\(info.height) \(info.fpsDisplay) \(formatTimecode(info.duration)) \(info.frameCount) frame \(info.codecDisplay)")

var s = ExtractionSettings()
s.outputFolderPath = args[2]
s.createSubfolder = true
if args.count > 3 {
    s.mode = ["all": .allFrames, "everyN": .everyNFrames, "interval": .interval,
              "count": .fixedCount, "scene": .sceneChange][args[3]] ?? .interval
}
if args.count > 4 { s.format = ImageFormat(rawValue: args[4]) ?? .jpg }
if args.count > 5 { s.naming = NamingStyle(rawValue: args[5]) ?? .sequence }
s.fixedCount = 10
s.everyN = 24
let start = args.count > 6 ? parseTimecode(args[6]) ?? 0 : 0
let end = args.count > 7 ? parseTimecode(args[7]) ?? info.duration : info.duration

let job = ExtractionJob(videoURL: video, info: info, start: start, end: end, settings: s)
let runner = ExtractionRunner(job: job, toolchain: tc)
print("perkiraan: \(job.expectedFrames.map(String.init) ?? "?") frame")
print("ffmpeg " + CommandBuilder.arguments(for: job, toolchain: tc, outputPattern: "OUT").joined(separator: " "))
if let b = FrameGrabber.sampleFrameBytes(video: video, start: start, end: end, settings: s, toolchain: tc) {
    print("sampel ukuran/frame: \(formatBytes(b))")
}

let sem = DispatchSemaphore(value: 0)
Task {
    let r = await runner.run { p in
        print(String(format: "  … %d frame  %.0f%%  %.0f fps", p.frames, p.fraction * 100, p.fps))
    }
    print("hasil: \(r.frames) frame, \(formatBytes(Double(r.totalBytes))), \(String(format: "%.2f", r.elapsed))s → \(r.outputFolder.path)")
    print("contoh: \(r.sampleFiles.prefix(4).map(\.lastPathComponent))")
    if let e = r.errorMessage { print("ERROR: \(e)") }
    sem.signal()
}
sem.wait()
