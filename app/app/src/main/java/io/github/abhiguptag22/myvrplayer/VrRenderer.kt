package io.github.abhiguptag22.myvrplayer

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Everything the renderer needs for one frame. Built on the UI thread, read on the GL thread. */
data class RenderParams(
    val mode: VrMode = VrMode.MONO_STATIC,
    val hShift: Float = 0f,           // percent
    val vShift: Float = 0f,           // percent
    val distortion: Float = 0f,       // 0 = off
    val ipd: Float = 0.46f,
    val distance: Float = 49f,
    val videoAspect: Float = 16f / 9f, // display aspect reported by VLC (whole frame)
    val pixelAspect: Float = 16f / 9f, // storage aspect, ignoring SAR (used by scale "NO")
    val scaleAspect: Float = 0f,       // 0 = source, >0 = forced, <0 = "NO"
    val invertScale: Boolean = false,
    val imageOn: Boolean = true,
    val brightness: Int = 50,
    val contrast: Int = 50,
    val saturation: Int = 50,
    val subScale: Float = 1f,
    val subPos: Float = 0f,           // fraction of picture height, + = up
    val whiteScreen: Boolean = false, // plain white in both eyes, for spotting dust on screen/lenses
    val theatre: Boolean = false,     // cinema hall around the screen
    val roomLight: Float = 0.0f,     // house lights, 0 = dark, 1 = photo brightness
)

/**
 * Draws the video (and VLC's subtitle layer) into one or two eye windows.
 * VLC decodes into [videoTexture]; subtitles are rendered by VLC into [subTexture].
 */
class VrRenderer(
    private val onSurfacesReady: (video: SurfaceTexture, subs: SurfaceTexture) -> Unit,
    private val requestRender: () -> Unit,
    /** Decodes the theatre image; called on the GL thread. */
    private val loadTheatre: () -> android.graphics.Bitmap?,
) : GLSurfaceView.Renderer {

    // ---- theatre: environment texture + per-frame "screen glow" (video shrunk to GLOW_W x GLOW_H) ----
    private var envTexId = 0
    private var envReady = false
    private var glowFbo = 0
    private var glowTexId = 0
    private var glowProgram = 0
    private var gPos = 0; private var gTex = 0; private var gTexMat = 0; private var gUv = 0; private var gCell = 0
    private var uEnv = 0; private var uGlow = 0; private var uEnvOn = 0; private var uEnvC = 0; private var uEnvW = 0
    private var uRoom = 0; private var uGlowAmt = 0

    @Volatile var params = RenderParams()

    /** Increments on every decoded video frame; the freeze watchdog reads it. */
    val frameCount = AtomicLong(0)

    private var program = 0
    private var videoTexId = 0
    private var subTexId = 0
    private var videoTexture: SurfaceTexture? = null
    private var subTexture: SurfaceTexture? = null
    private val videoDirty = AtomicBoolean(false)
    private val subDirty = AtomicBoolean(false)
    @Volatile private var subSeen = false
    private val videoMat = FloatArray(16)
    private val subMat = FloatArray(16)
    private var width = 1
    private var height = 1

    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }

    // uniform locations
    private var uTex = 0; private var uSub = 0; private var uTexMat = 0; private var uSubMat = 0
    private var uLens = 0; private var uR = 0; private var uK = 0; private var uPanelC = 0; private var uPanelS = 0
    private var uUv = 0; private var uAdj = 0; private var uAdjOn = 0; private var uSubOn = 0; private var uSubXf = 0
    private var aPos = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uSub = GLES20.glGetUniformLocation(program, "uSub")
        uTexMat = GLES20.glGetUniformLocation(program, "uTexMat")
        uSubMat = GLES20.glGetUniformLocation(program, "uSubMat")
        uLens = GLES20.glGetUniformLocation(program, "uLens")
        uR = GLES20.glGetUniformLocation(program, "uR")
        uK = GLES20.glGetUniformLocation(program, "uK")
        uPanelC = GLES20.glGetUniformLocation(program, "uPanelC")
        uPanelS = GLES20.glGetUniformLocation(program, "uPanelS")
        uUv = GLES20.glGetUniformLocation(program, "uUv")
        uAdj = GLES20.glGetUniformLocation(program, "uAdj")
        uAdjOn = GLES20.glGetUniformLocation(program, "uAdjOn")
        uSubOn = GLES20.glGetUniformLocation(program, "uSubOn")
        uSubXf = GLES20.glGetUniformLocation(program, "uSubXf")
        uEnv = GLES20.glGetUniformLocation(program, "uEnv")
        uGlow = GLES20.glGetUniformLocation(program, "uGlow")
        uEnvOn = GLES20.glGetUniformLocation(program, "uEnvOn")
        uEnvC = GLES20.glGetUniformLocation(program, "uEnvC")
        uEnvW = GLES20.glGetUniformLocation(program, "uEnvW")
        uRoom = GLES20.glGetUniformLocation(program, "uRoom")
        uGlowAmt = GLES20.glGetUniformLocation(program, "uGlowAmt")
        setupTheatre()

        val ids = IntArray(2)
        GLES20.glGenTextures(2, ids, 0)
        videoTexId = ids[0]; subTexId = ids[1]
        for (id in ids) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        android.opengl.Matrix.setIdentityM(videoMat, 0)
        android.opengl.Matrix.setIdentityM(subMat, 0)

        videoTexture?.release(); subTexture?.release()
        subSeen = false
        val v = SurfaceTexture(videoTexId).apply {
            setOnFrameAvailableListener { videoDirty.set(true); frameCount.incrementAndGet(); requestRender() }
        }
        val s = SurfaceTexture(subTexId).apply {
            setOnFrameAvailableListener { subDirty.set(true); subSeen = true; requestRender() }
        }
        videoTexture = v; subTexture = s
        onSurfacesReady(v, s)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        GLES20.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        try {
            if (videoDirty.getAndSet(false)) videoTexture?.apply { updateTexImage(); getTransformMatrix(videoMat) }
            if (subDirty.getAndSet(false)) subTexture?.apply { updateTexImage(); getTransformMatrix(subMat) }
        } catch (e: RuntimeException) {
            Log.w(TAG, "updateTexImage failed", e)
        }

        val p = params
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        if (p.whiteScreen) {
            GLES20.glClearColor(1f, 1f, 1f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val theatreOn = p.theatre && envReady && p.mode != VrMode.NO_VR
        if (theatreOn) glowPass(p)

        GLES20.glUseProgram(program)
        GLES20.glUniform1f(uEnvOn, if (theatreOn) 1f else 0f)
        if (theatreOn) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, envTexId)
            GLES20.glUniform1i(uEnv, 2)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glowTexId)
            GLES20.glUniform1i(uGlow, 3)
            GLES20.glUniform2f(uEnvC, ENV_SCREEN_U, ENV_SCREEN_V)
            GLES20.glUniform2f(uEnvW, ENV_SCREEN_W_U, -ENV_SCREEN_W_V)
            GLES20.glUniform1f(uRoom, p.roomLight)
            GLES20.glUniform1f(uGlowAmt, GLOW_GAIN)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexId)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, subTexId)
        GLES20.glUniform1i(uSub, 1)
        GLES20.glUniformMatrix4fv(uTexMat, 1, false, videoMat, 0)
        GLES20.glUniformMatrix4fv(uSubMat, 1, false, subMat, 0)
        GLES20.glUniform4f(uAdj, (p.brightness - 50) / 100f, p.contrast / 50f, p.saturation / 50f, 0f)
        GLES20.glUniform1f(uAdjOn, if (p.imageOn) 1f else 0f)
        GLES20.glUniform1f(uSubOn, if (subSeen) 1f else 0f)
        GLES20.glUniform2f(uSubXf, p.subScale.coerceAtLeast(0.2f), p.subPos)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)

        val w = width.toFloat(); val h = height.toFloat()
        val aspect = contentAspect(p)
        if (p.mode == VrMode.NO_VR) {
            // Plain full-screen playback, no split, no distortion.
            val pw = minOf(w, h * aspect)
            drawEye(0, 0, width, w / 2, h / 2, 1f, 0f, w / 2, h / 2, pw, pw / aspect, 0f, 0f, 1f, 1f)
        } else {
            val eyeOff = p.ipd * IPD_TO_WIDTH * w
            val shift = p.hShift * HSHIFT_PER_PCT * w
            val panelW = w * DISTANCE_K / p.distance.coerceAtLeast(1f)
            val panelH = panelW / aspect
            val cy = h / 2 - p.vShift * VSHIFT_PER_PCT * h
            val k = p.distortion * DISTORTION_K
            for (eye in 0..1) {
                val sign = if (eye == 0) -1f else 1f
                val lensX = w / 2 + sign * eyeOff
                // Which part of the decoded frame this eye sees.
                val uv = when (p.mode) {
                    VrMode.SBS_STATIC -> floatArrayOf(if (eye == 0) 0f else 0.5f, 0f, 0.5f, 1f)
                    VrMode.TOU_STATIC -> floatArrayOf(0f, if (eye == 0) 0.5f else 0f, 1f, 0.5f)
                    else -> floatArrayOf(0f, 0f, 1f, 1f)
                }
                val x0 = if (eye == 0) 0 else width / 2
                drawEye(x0, 0, width / 2, lensX, h / 2, w / 4, k, lensX + sign * shift, cy, panelW, panelH,
                    uv[0], uv[1], uv[2], uv[3])
            }
        }
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun drawEye(
        x0: Int, y0: Int, eyeW: Int, lensX: Float, lensY: Float, radius: Float, k: Float,
        panelCx: Float, panelCy: Float, panelW: Float, panelH: Float,
        uvX: Float, uvY: Float, uvW: Float, uvH: Float,
    ) {
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(x0, y0, eyeW, height)
        GLES20.glUniform2f(uLens, lensX, lensY)
        GLES20.glUniform1f(uR, radius)
        GLES20.glUniform1f(uK, k)
        GLES20.glUniform2f(uPanelC, panelCx, panelCy)
        GLES20.glUniform2f(uPanelS, panelW, panelH)
        GLES20.glUniform4f(uUv, uvX, uvY, uvW, uvH)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun contentAspect(p: RenderParams): Float {
        var a = when {
            p.scaleAspect > 0f -> p.scaleAspect
            p.scaleAspect < 0f -> p.pixelAspect
            else -> p.videoAspect
        }
        if (a <= 0f || a.isNaN()) a = 16f / 9f
        if (p.invertScale) a = 1f / a
        return a
    }

    private fun setupTheatre() {
        envReady = false
        glowProgram = buildProgram(VERTEX, GLOW_FRAGMENT)
        gPos = GLES20.glGetAttribLocation(glowProgram, "aPos")
        gTex = GLES20.glGetUniformLocation(glowProgram, "uTex")
        gTexMat = GLES20.glGetUniformLocation(glowProgram, "uTexMat")
        gUv = GLES20.glGetUniformLocation(glowProgram, "uUv")
        gCell = GLES20.glGetUniformLocation(glowProgram, "uCell")

        val ids = IntArray(2)
        GLES20.glGenTextures(2, ids, 0)
        envTexId = ids[0]; glowTexId = ids[1]

        // small render target for the screen glow
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glowTexId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, GLOW_W, GLOW_H, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val fb = IntArray(1)
        GLES20.glGenFramebuffers(1, fb, 0)
        glowFbo = fb[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, glowFbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, glowTexId, 0)
        val fbOk = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        // the cinema hall itself (4096 x 2048, power of two so it can be mipmapped)
        val bmp = try { loadTheatre() } catch (e: Throwable) { Log.w(TAG, "theatre image failed", e); null }
        if (bmp != null && fbOk) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, envTexId)
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            bmp.recycle()
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            envReady = true
        }
    }

    /** Shrinks the current video frame into the GLOW_W x GLOW_H texture (16 samples per texel). */
    private fun glowPass(p: RenderParams) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, glowFbo)
        GLES20.glViewport(0, 0, GLOW_W, GLOW_H)
        GLES20.glUseProgram(glowProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexId)
        GLES20.glUniform1i(gTex, 0)
        GLES20.glUniformMatrix4fv(gTexMat, 1, false, videoMat, 0)
        when (p.mode) { // left-eye part of stereo frames
            VrMode.SBS_STATIC -> GLES20.glUniform4f(gUv, 0f, 0f, 0.5f, 1f)
            VrMode.TOU_STATIC -> GLES20.glUniform4f(gUv, 0f, 0.5f, 1f, 0.5f)
            else -> GLES20.glUniform4f(gUv, 0f, 0f, 1f, 1f)
        }
        GLES20.glUniform2f(gCell, 1f / GLOW_W, 1f / GLOW_H)
        GLES20.glEnableVertexAttribArray(gPos)
        GLES20.glVertexAttribPointer(gPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(gPos)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
    }

    fun release() {
        videoTexture?.release(); subTexture?.release()
        videoTexture = null; subTexture = null
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw RuntimeException("shader: " + GLES20.glGetShaderInfoLog(s))
            return s
        }
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(prog, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("link: " + GLES20.glGetProgramInfoLog(prog))
        return prog
    }

    companion object {
        private const val TAG = "VrRenderer"

        // Tuned on a 2400x1080 screen:
        const val IPD_TO_WIDTH = 0.49f        // eye centre offset from screen centre = ipd * 0.49 * width
        const val DISTANCE_K = 22.4f          // picture width = width * 22.4 / distance  (49 -> 0.457 W)
        const val HSHIFT_PER_PCT = 0.000458f  // "< >" 1% = 0.0458% of width, mirrored per eye
        const val VSHIFT_PER_PCT = 0.00145f   // "^ v" 1% = 0.145% of height, + = down
        const val DISTORTION_K = 0.06f

        // Theatre image (Poly Haven "Cinema Hall", CC0), see docs/theatre.md. Screen centre and width in texture UV:
        const val ENV_SCREEN_U = 1597f / 3200f
        const val ENV_SCREEN_V = 990f / 2000f
        const val ENV_SCREEN_W_U = 1040f / 3200f  // screen width as a fraction of image width
        const val ENV_SCREEN_W_V = 1040f / 2000f  // same width measured in image-height units
        const val GLOW_W = 16
        const val GLOW_H = 9
        const val GLOW_GAIN = 0.45f

        private const val GLOW_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            uniform mat4 uTexMat;
            uniform vec4 uUv;
            uniform vec2 uCell;
            void main() {
                vec2 base = (gl_FragCoord.xy - 0.5) * uCell;
                vec3 acc = vec3(0.0);
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        vec2 l = base + (vec2(float(i), float(j)) + 0.5) * 0.25 * uCell;
                        vec2 uv = uUv.xy + l * uUv.zw;
                        acc += texture2D(uTex, (uTexMat * vec4(uv, 0.0, 1.0)).xy).rgb;
                    }
                }
                gl_FragColor = vec4(acc / 16.0, 1.0);
            }
        """

        private const val VERTEX = """
            attribute vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            uniform samplerExternalOES uTex;
            uniform samplerExternalOES uSub;
            uniform mat4 uTexMat;
            uniform mat4 uSubMat;
            uniform vec2 uLens;
            uniform float uR;
            uniform float uK;
            uniform vec2 uPanelC;
            uniform vec2 uPanelS;
            uniform vec4 uUv;
            uniform vec4 uAdj;
            uniform float uAdjOn;
            uniform float uSubOn;
            uniform vec2 uSubXf;
            uniform sampler2D uEnv;
            uniform sampler2D uGlow;
            uniform float uEnvOn;
            uniform vec2 uEnvC;
            uniform vec2 uEnvW;
            uniform float uRoom;
            uniform float uGlowAmt;
            void main() {
                vec2 d = gl_FragCoord.xy - uLens;
                float r2 = dot(d, d) / (uR * uR);
                vec2 q = uLens + d / (1.0 + uK * r2);
                vec2 l = (q - uPanelC) / uPanelS + 0.5;
                if (l.x < 0.0 || l.x > 1.0 || l.y < 0.0 || l.y > 1.0) {
                    if (uEnvOn < 0.5) {
                        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                        return;
                    }
                    // Cinema hall, scaled and positioned so its screen matches the video panel.
                    vec2 dd = (q - uPanelC) / uPanelS.x;
                    vec2 euv = uEnvC + dd * uEnvW;
                    if (euv.x < 0.0 || euv.x > 1.0 || euv.y < 0.0 || euv.y > 1.0) {
                        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                        return;
                    }
                    vec3 env = texture2D(uEnv, euv).rgb;
                    // Dark-cinema grade: mute the warm wood and cool it slightly.
                    float lum = dot(env, vec3(0.299, 0.587, 0.114));
                    env = mix(vec3(lum), env, 0.35) * vec3(0.88, 0.93, 1.04);
                    float edge = smoothstep(0.0, 0.06, euv.x) * smoothstep(1.0, 0.94, euv.x)
                               * smoothstep(0.0, 0.06, euv.y) * smoothstep(1.0, 0.94, euv.y);
                    // Light from the screen: nearest part of the picture, fading with distance, plus overall spill.
                    vec2 nl = clamp(l, 0.0, 1.0);
                    vec3 near = texture2D(uGlow, nl).rgb;
                    vec3 avg = (texture2D(uGlow, vec2(0.25, 0.25)).rgb + texture2D(uGlow, vec2(0.75, 0.25)).rgb
                              + texture2D(uGlow, vec2(0.25, 0.75)).rgb + texture2D(uGlow, vec2(0.75, 0.75)).rgb
                              + texture2D(uGlow, vec2(0.5, 0.5)).rgb) * 0.2;
                    vec2 off = (l - nl) * vec2(1.0, uPanelS.y / uPanelS.x);
                    float fall = 1.0 / (1.0 + 7.0 * dot(off, off));
                    vec3 light = vec3(uRoom) + uGlowAmt * (near * fall * 0.85 + avg * 0.3);
                    gl_FragColor = vec4(clamp(env * light * edge, 0.0, 1.0), 1.0);
                    return;
                }
                vec2 uv = uUv.xy + l * uUv.zw;
                vec3 c = texture2D(uTex, (uTexMat * vec4(uv, 0.0, 1.0)).xy).rgb;
                if (uAdjOn > 0.5) {
                    c += uAdj.x;
                    c = (c - 0.5) * uAdj.y + 0.5;
                    float g = dot(c, vec3(0.299, 0.587, 0.114));
                    c = mix(vec3(g), c, uAdj.z);
                }
                if (uSubOn > 0.5) {
                    vec2 s = vec2(0.5 + (l.x - 0.5) / uSubXf.x, (l.y - uSubXf.y) / uSubXf.x);
                    if (s.x >= 0.0 && s.x <= 1.0 && s.y >= 0.0 && s.y <= 1.0) {
                        vec4 t = texture2D(uSub, (uSubMat * vec4(s, 0.0, 1.0)).xy);
                        c = c * (1.0 - t.a) + t.rgb;
                    }
                }
                gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
            }
        """
    }
}
