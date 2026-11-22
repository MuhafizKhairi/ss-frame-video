package local.frameshot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    /** Jalankan selector pada video sintetis `fps` selama `seconds` detik, kembalikan waktu frame terpilih. */
    private fun run(s: ExtractionSettings, seconds: Double, fps: Double = 24.0): List<Double> {
        val sel = FrameSelector(s, seconds, 1 / fps)
        val out = ArrayList<Double>()
        val n = (seconds * fps).toInt()
        for (i in 0 until n) {
            val t = i / fps
            if (sel.offer(t)) out.add(t)
            if (sel.isDone) break
        }
        return out
    }

    @Test fun allFrames() = assertEquals(240, run(ExtractionSettings(mode = ExtractionMode.ALL_FRAMES), 10.0).size)

    @Test fun everyN() {
        val r = run(ExtractionSettings(mode = ExtractionMode.EVERY_N_FRAMES, everyN = 24), 10.0)
        assertEquals(10, r.size)
        assertEquals(0.0, r[0], 1e-9)
        assertEquals(1.0, r[1], 1e-9)
    }

    @Test fun intervalMatchesEstimate() {
        val s = ExtractionSettings(mode = ExtractionMode.INTERVAL, intervalSeconds = 2.0)
        val r = run(s, 10.0)
        assertEquals(listOf(0.0, 2.0, 4.0, 6.0, 8.0), r.map { Math.round(it * 1000) / 1000.0 })
        assertEquals(r.size, s.estimatedFrames(10.0, 24.0))
    }

    @Test fun fixedCount() {
        val s = ExtractionSettings(mode = ExtractionMode.FIXED_COUNT, fixedCount = 7)
        assertEquals(7, run(s, 10.0).size)
        // Lebih banyak dari jumlah frame → semua frame.
        assertEquals(24, run(s.copy(fixedCount = 500), 1.0).size)
    }

    @Test fun nextWantedTime() {
        val sel = FrameSelector(ExtractionSettings(mode = ExtractionMode.INTERVAL, intervalSeconds = 5.0), 60.0, 1 / 24.0)
        assertNull(sel.nextWantedTime())
        assertTrue(sel.offer(0.0))
        assertEquals(5.0, sel.nextWantedTime()!!, 1e-9)
        assertFalse(sel.offer(4.9))
        assertTrue(sel.offer(5.01))
        assertEquals(10.0, sel.nextWantedTime()!!, 1e-9)
    }

    @Test fun sceneScore() {
        val sc = SceneScorer()
        val black = ByteArray(64 * 4)
        val white = ByteArray(64 * 4) { 0xFF.toByte() }
        assertEquals(0.0, sc.score(black), 1e-9)
        assertEquals(0.0, sc.score(black), 1e-9)
        assertTrue(sc.score(white) > 0.9)
    }

    @Test fun duplicates() {
        val d = DuplicateDetector(16, 16)
        val a = ByteArray(16 * 16 * 4) { 10 }
        val b = ByteArray(16 * 16 * 4) { 200.toByte() }
        assertFalse(d.isDuplicate(a))
        assertTrue(d.isDuplicate(a.copyOf()))
        assertFalse(d.isDuplicate(b))
    }

    @Test fun timecode() {
        assertEquals(750.0, parseTimecode("12:30")!!, 1e-9)
        assertEquals(3723.5, parseTimecode("1:02:03,5")!!, 1e-9)
        assertNull(parseTimecode("a:b"))
        assertEquals("01:02:03.500", formatTimecode(3723.5).let { formatTimecode(3723.5, forceHours = true).padStart(12, '0') })
        assertEquals("12:30", formatTimecode(750.0, millis = false))
    }

    @Test fun naming() {
        val s = ExtractionSettings(naming = NamingStyle.SEQUENCE, digits = 6, startNumber = 1)
        val n = FrameNamer("Episode 01", s, setOf("Episode 01_000001.jpg"))
        assertEquals("Episode 01 (2)", n.prefix)
        assertEquals("Episode 01 (2)_000001.jpg", n.next(0))
        val t = FrameNamer("Ep", s.copy(naming = NamingStyle.TIMESTAMP), emptySet())
        assertEquals("Ep_00-12-30-500.jpg", t.next(750_500))
        assertEquals("Ep_00-12-30-500 (2).jpg", t.next(750_500))
        assertEquals("Ep_000001.jpg", exampleFilename(s, "Ep"))
        assertEquals("a_b — frames 2", uniqueFolderName("a/b") { it == "a_b — frames" })
    }

    @Test fun outputSize() {
        val s = ExtractionSettings(resolution = ResolutionPreset.P720)
        assertEquals(1280 to 720, s.outputSize(1920, 1080))
        assertEquals(640 to 360, s.outputSize(640, 360))
        assertEquals(1280 to 720, s.copy(resolution = ResolutionPreset.CUSTOM, customWidth = 1280).outputSize(1920, 1080))
    }

    @Test fun settingsJson() {
        val s = ExtractionSettings(mode = ExtractionMode.SCENE_CHANGE, quality = 80, customPrefix = "x")
        assertEquals(s, ExtractionSettings.fromJson(s.toJson()))
        assertEquals(ExtractionSettings(), ExtractionSettings.fromJson("{\"mode\":\"NOPE\"}"))
    }
}
