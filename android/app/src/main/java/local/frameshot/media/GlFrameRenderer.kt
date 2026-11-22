package local.frameshot.media

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Menerima frame dari decoder lewat SurfaceTexture, lalu merendernya (dengan rotasi & skala)
 * ke framebuffer offscreen dan membaca pikselnya menjadi Bitmap.
 *
 * Harus dibuat, dipakai, dan dilepas dari SATU thread yang sama (konteks EGL terikat thread).
 *
 * @param displayW/H ukuran video setelah rotasi
 * @param rotation   rotasi searah jarum jam yang harus diterapkan (0/90/180/270)
 */
class GlFrameRenderer(
    private val displayW: Int,
    private val displayH: Int,
    private val rotation: Int,
) : SurfaceTexture.OnFrameAvailableListener {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private val callbackThread = HandlerThread("frameshot-frames").apply { start() }
    private val frameLock = Object()
    private var frameAvailable = false

    private var oesTex = 0
    private lateinit var surfaceTexture: SurfaceTexture
    lateinit var surface: Surface
        private set

    private var oesProgram = 0
    private var texProgram = 0
    private val stMatrix = FloatArray(16)

    // Framebuffer resolusi penuh (ber-mipmap untuk downscale berkualitas).
    private var fullTex = 0
    private var fullFbo = 0
    private var mipmapsValid = false
    // Framebuffer keluaran & analisis, dibuat sesuai kebutuhan.
    private val targets = HashMap<Long, IntArray>() // key = w<<32|h → [tex, fbo]
    private var readBuffer: ByteBuffer? = null

    private val maxTexture: Int

    init {
        setupEgl()
        val tmp = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, tmp, 0)
        maxTexture = if (tmp[0] > 0) tmp[0] else 4096
        if (displayW > maxTexture || displayH > maxTexture) {
            release()
            throw IllegalStateException("Resolusi video ${displayW}×$displayH melebihi batas GPU perangkat ini ($maxTexture px).")
        }

        GLES20.glGenTextures(1, tmp, 0)
        oesTex = tmp[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(oesTex)
        surfaceTexture.setDefaultBufferSize(if (rotation % 180 == 90) displayH else displayW,
            if (rotation % 180 == 90) displayW else displayH)
        surfaceTexture.setOnFrameAvailableListener(this, Handler(callbackThread.looper))
        surface = Surface(surfaceTexture)

        oesProgram = buildProgram(VERTEX, FRAGMENT_OES)
        texProgram = buildProgram(VERTEX, FRAGMENT_2D)

        val ids = createTarget(displayW, displayH, mipmap = true)
        fullTex = ids[0]
        fullFbo = ids[1]
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        synchronized(frameLock) {
            frameAvailable = true
            frameLock.notifyAll()
        }
    }

    /** Tunggu frame baru dari decoder dan jadikan frame aktif. false = timeout. */
    fun awaitFrame(timeoutMs: Long = 3000): Boolean {
        synchronized(frameLock) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!frameAvailable) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return false
                frameLock.wait(left)
            }
            frameAvailable = false
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(stMatrix)
        fullValid = false
        mipmapsValid = false
        return true
    }

    private var fullValid = false

    /** Render frame aktif dari tekstur decoder ke framebuffer tertentu (dengan rotasi). */
    private fun drawOes(fbo: Int, w: Int, h: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(oesProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(oesProgram, "uSt"), 1, false, stMatrix, 0)
        drawQuad(oesProgram, rotatedTexCoords())
    }

    private fun ensureFull() {
        if (fullValid) return
        drawOes(fullFbo, displayW, displayH)
        fullValid = true
    }

    /**
     * Baca frame aktif pada ukuran tertentu sebagai RGBA (baris atas lebih dulu).
     * `fast` = langsung dari tekstur decoder tanpa downscale berkualitas (untuk analisis).
     */
    fun readRgba(w: Int, h: Int, out: ByteBuffer, fast: Boolean = false) {
        val fbo = when {
            w == displayW && h == displayH -> { ensureFull(); fullFbo }
            fast -> {
                val ids = targetFor(w, h)
                drawOes(ids[1], w, h)
                ids[1]
            }
            else -> downscaleTo(w, h)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        out.clear()
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, out)
        out.rewind()
    }

    private fun targetFor(w: Int, h: Int): IntArray {
        val key = (w.toLong() shl 32) or h.toLong()
        return targets.getOrPut(key) { createTarget(w, h, mipmap = false) }
    }

    /** Frame aktif sebagai Bitmap ARGB_8888 (memakai `reuse` jika ukurannya cocok). */
    fun readBitmap(w: Int, h: Int, reuse: Bitmap?): Bitmap {
        val need = w * h * 4
        var buf = readBuffer
        if (buf == null || buf.capacity() < need) {
            buf = ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder())
            readBuffer = buf
        }
        buf!!.limit(need)
        readRgba(w, h, buf)
        val bmp = if (reuse != null && reuse.width == w && reuse.height == h && reuse.isMutable) reuse
        else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buf.position(0)
        bmp.copyPixelsFromBuffer(buf)
        bmp.setHasAlpha(false)
        return bmp
    }

    private fun downscaleTo(w: Int, h: Int): Int {
        ensureFull()
        if (!mipmapsValid) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fullTex)
            GLES30.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            mipmapsValid = true
        }
        val ids = targetFor(w, h)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[1])
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(texProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fullTex)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(texProgram, "uSt"), 1, false, IDENTITY, 0)
        drawQuad(texProgram, floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
        return ids[1]
    }

    /**
     * Koordinat tekstur untuk tiap sudut keluaran (u',v' dengan v' ke bawah), memperhitungkan rotasi.
     * Hasilnya koordinat ST (t ke atas) yang nanti dikalikan matriks SurfaceTexture di shader.
     */
    private fun rotatedTexCoords(): FloatArray {
        val corners = arrayOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)
        val out = FloatArray(8)
        for ((i, c) in corners.withIndex()) {
            val (u, v) = c
            val (su, sv) = when (rotation) {
                90 -> v to 1f - u
                180 -> 1f - u to 1f - v
                270 -> 1f - v to u
                else -> u to v
            }
            out[i * 2] = su
            out[i * 2 + 1] = 1f - sv
        }
        return out
    }

    private fun drawQuad(program: Int, texCoords: FloatArray) {
        // Baris atas keluaran (v'=0) diletakkan di y = -1 agar glReadPixels menghasilkan urutan atas→bawah.
        val pos = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aTex = GLES20.glGetAttribLocation(program, "aTex")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, floatBuffer(pos))
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, floatBuffer(texCoords))
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    private fun createTarget(w: Int, h: Int, mipmap: Boolean): IntArray {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
            if (mipmap) GLES20.GL_LINEAR_MIPMAP_LINEAR else GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        if (mipmap) GLES30.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        val f = IntArray(1)
        GLES20.glGenFramebuffers(1, f, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t[0], 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "Framebuffer GPU gagal dibuat ($status)." }
        return intArrayOf(t[0], f[0])
    }

    private fun setupEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "EGL tidak tersedia." }
        val ver = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) { "EGL gagal diinisialisasi." }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "Konfigurasi EGL tidak ditemukan." }
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "Konteks OpenGL ES 3 gagal dibuat." }
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent gagal." }
    }

    fun release() {
        runCatching { if (::surface.isInitialized) surface.release() }
        runCatching { if (::surfaceTexture.isInitialized) surfaceTexture.release() }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        callbackThread.quitSafely()
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader gagal: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Program GL gagal: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    private fun floatBuffer(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

    companion object {
        private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        private const val VERTEX = """
            uniform mat4 uSt;
            attribute vec4 aPos;
            attribute vec4 aTex;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uSt * aTex).xy;
            }
        """
        private const val FRAGMENT_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES uTex;
            void main() { gl_FragColor = texture2D(uTex, vTex); }
        """
        private const val FRAGMENT_2D = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D uTex;
            void main() { gl_FragColor = texture2D(uTex, vTex); }
        """
    }
}
