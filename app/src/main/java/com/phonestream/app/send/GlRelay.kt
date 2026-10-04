package com.phonestream.app.send

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import com.phonestream.app.core.Planner
import com.phonestream.app.core.StreamPlan
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The screen picture takes a short detour through the GPU on its way to the encoder:
 *
 *   VirtualDisplay -> SurfaceTexture -> (draw) -> encoder's input surface
 *
 * That detour is what makes two things possible that the direct VirtualDisplay -> encoder connection can't do:
 *  - a hard frame-rate limit (a 90 / 120 Hz screen is not fed to the encoder at 90 / 120 fps, which would swamp
 *    the link and the TV's decoder), changeable while streaming;
 *  - showing a cropped or stretched version of the screen (landscape phone -> 16:9 TV, see [Planner.cropRect]).
 *
 * Everything happens on one private thread (all EGL / GL state lives there). The public functions block until
 * that thread has done the job and rethrow its failures, so callers can fall back to the direct connection when
 * this phone's GL stack can't do it ([start] throws).
 */
class GlRelay {
    private class Target(val surface: EGLSurface, val width: Int, val height: Int, val texCoords: FloatBuffer)

    private val thread = HandlerThread("ps-gl", Process.THREAD_PRIORITY_DISPLAY)
    private lateinit var handler: Handler

    // --- everything below is only touched on the GL thread ---
    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var idle: EGLSurface = EGL14.EGL_NO_SURFACE // 1x1 pbuffer, or none (surfaceless) while no encoder is attached
    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private var uTexture = 0
    private var texId = 0
    private var texture: SurfaceTexture? = null
    private var input: Surface? = null
    private var target: Target? = null
    private val texMatrix = FloatArray(16)
    private var srcW = 0
    private var srcH = 0
    private var minGapNs = 0L
    private var intervalMs = 16L
    private var lastSeenTs = 0L // timestamp of the newest frame the screen has delivered
    private var lastRenderedTs = 0L // timestamp of the newest frame given to the current encoder
    private var dirty = false // a frame was held back by the rate limit and not shown yet
    private var released = false

    private val trailing = Runnable {
        // The screen went quiet right after a frame the limiter held back: show that last picture anyway.
        val t = target
        if (dirty && t != null) draw(t, lastSeenTs)
    }

    /** Sets up EGL and the SurfaceTexture. Returns the Surface the VirtualDisplay must render into. */
    fun start(): Surface {
        thread.start()
        handler = Handler(thread.looper)
        try {
            return call { initGl() }
        } catch (e: Throwable) {
            release()
            throw e
        }
    }

    /** The VirtualDisplay's size from now on. */
    fun setSourceSize(w: Int, h: Int) = call {
        if (w != srcW || h != srcH) {
            srcW = w
            srcH = h
            texture?.setDefaultBufferSize(w, h)
            lastSeenTs = 0 // a picture of the old size: don't show it again, a new one is on its way
        }
    }

    /** Draws into [encoderSurface] from now on (replaces the previous encoder surface). */
    fun attach(encoderSurface: Surface, plan: StreamPlan, fpsCap: Int) = call {
        detachNow()
        val s = EGL14.eglCreateWindowSurface(dpy, config, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        if (s == null || s == EGL14.EGL_NO_SURFACE) {
            throw IllegalStateException("EGL refused the encoder surface (0x${Integer.toHexString(EGL14.eglGetError())})")
        }
        val u = Planner.cropRect(plan.srcWidth, plan.srcHeight, plan.width, plan.height, plan.fit)
        val tc = floatBuffer(
            u[0].toFloat(), u[1].toFloat(), u[2].toFloat(), u[1].toFloat(),
            u[0].toFloat(), u[3].toFloat(), u[2].toFloat(), u[3].toFloat(),
        )
        val t = Target(s, plan.width, plan.height, tc)
        if (!EGL14.eglMakeCurrent(dpy, s, s, ctx)) {
            EGL14.eglDestroySurface(dpy, s)
            throw IllegalStateException("EGL: cannot draw into the encoder surface (0x${Integer.toHexString(EGL14.eglGetError())})")
        }
        target = t
        lastRenderedTs = 0
        dirty = false
        applyFpsCap(fpsCap)
        // A still screen delivers no new frames, so show the latest one right away: the encoder needs a first picture.
        if (lastSeenTs != 0L) draw(t, lastSeenTs)
    }

    /** Stops drawing into the encoder surface. Call before the encoder is released. */
    fun detach() = call { detachNow() }

    /** At most this many frames per second go to the encoder. */
    fun setFpsCap(fps: Int) = call { applyFpsCap(fps) }

    fun release() {
        if (released) return
        released = true
        try {
            call { teardown() }
        } catch (_: Throwable) {
        }
        thread.quitSafely()
    }

    // ---- GL thread -----------------------------------------------------------------------------------

    private fun <T> call(block: () -> T): T {
        val task = FutureTask(Callable { block() })
        if (!handler.post(task)) throw IllegalStateException("GL thread is gone")
        try {
            return task.get(5, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: TimeoutException) {
            throw IllegalStateException("GL thread is not responding")
        }
    }

    private fun initGl(): Surface {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (dpy == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("EGL: no display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(dpy, version, 0, version, 1)) throw IllegalStateException("EGL: cannot initialise")

        // A config the encoder's surface accepts (RECORDABLE) that can also make the 1x1 idle surface.
        var cfg = chooseConfig(withPbuffer = true)
        val pbuffer = cfg != null
        if (cfg == null) cfg = chooseConfig(withPbuffer = false) ?: throw IllegalStateException("EGL: no suitable config")
        config = cfg
        ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (ctx == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("EGL: cannot create a context")
        if (pbuffer) {
            idle = EGL14.eglCreatePbufferSurface(
                dpy, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
        }
        // (with no idle surface this is a "surfaceless" context, which every phone with Android 8+ supports)
        if (!EGL14.eglMakeCurrent(dpy, idle, idle, ctx)) throw IllegalStateException("EGL: cannot activate the context")

        program = buildProgram()
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        if (aPosition < 0 || aTexCoord < 0 || uTexMatrix < 0 || uTexture < 0) throw IllegalStateException("GL: shader inputs missing")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(texId)
        st.setOnFrameAvailableListener({ onFrame() }, handler)
        texture = st
        applyFpsCap(60)
        return Surface(st).also { input = it }
    }

    private fun chooseConfig(withPbuffer: Boolean): EGLConfig? {
        val surfaceType = if (withPbuffer) EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT else EGL14.EGL_WINDOW_BIT
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, surfaceType,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        if (!EGL14.eglChooseConfig(dpy, attribs, 0, configs, 0, 1, n, 0) || n[0] < 1) return null
        return configs[0]
    }

    private fun buildProgram(): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw IllegalStateException("GL: cannot link the shader ($log)")
        }
        return p
    }

    private fun compile(type: Int, source: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, source)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw IllegalStateException("GL: cannot compile the shader ($log)")
        }
        return s
    }

    private fun applyFpsCap(fps: Int) {
        val f = fps.coerceIn(1, 240)
        intervalMs = (1000L / f).coerceAtLeast(1)
        minGapNs = (1_000_000_000L / f) * 7 / 10 // a frame counts as "on time" from 70% of the interval on
    }

    /** The screen delivered a frame. */
    private fun onFrame() {
        if (released) return
        val st = texture ?: return
        // Must always be consumed, shown or not: the next onFrameAvailable only comes after this.
        try {
            st.updateTexImage()
        } catch (_: Exception) {
            return
        }
        var ts = st.timestamp
        if (ts <= 0) ts = System.nanoTime()
        if (ts == lastSeenTs) return // no new picture since last time
        st.getTransformMatrix(texMatrix)
        lastSeenTs = ts
        val t = target ?: return
        if (lastRenderedTs != 0L && ts - lastRenderedTs < minGapNs) {
            if (!dirty) {
                dirty = true
                handler.postDelayed(trailing, intervalMs)
            }
            return
        }
        draw(t, ts)
    }

    private fun draw(t: Target, ts: Long) {
        var stamp = ts
        if (stamp <= lastRenderedTs) stamp = lastRenderedTs + 1_000 // timestamps must keep increasing
        GLES20.glViewport(0, 0, t.width, t.height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 8, QUAD)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 8, t.texCoords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(dpy, t.surface, stamp)
        // (fails once the encoder is being torn down; the encoder reports its own problems)
        EGL14.eglSwapBuffers(dpy, t.surface)
        lastRenderedTs = stamp
        dirty = false
    }

    private fun detachNow() {
        handler.removeCallbacks(trailing)
        dirty = false
        val t = target ?: return
        target = null
        EGL14.eglMakeCurrent(dpy, idle, idle, ctx)
        EGL14.eglDestroySurface(dpy, t.surface)
    }

    private fun teardown() {
        detachNow()
        handler.removeCallbacksAndMessages(null)
        try { texture?.setOnFrameAvailableListener(null) } catch (_: Exception) {}
        input?.release()
        input = null
        texture?.release()
        texture = null
        if (dpy != EGL14.EGL_NO_DISPLAY) {
            if (program != 0) GLES20.glDeleteProgram(program)
            if (texId != 0) GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
            program = 0
            texId = 0
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (idle != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, idle)
            if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(dpy)
        }
        idle = EGL14.EGL_NO_SURFACE
        ctx = EGL14.EGL_NO_CONTEXT
        dpy = EGL14.EGL_NO_DISPLAY
    }

    companion object {
        private fun floatBuffer(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(v)
                position(0)
            }

        // triangle strip: bottom-left, bottom-right, top-left, top-right
        private val QUAD = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

        private const val VERTEX = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
