package com.vrplayer.bilisbs.renderer

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 2.0 SBS (Side-by-Side) 渲染器
 *
 * 渲染管线（两个 Pass）：
 *   Pass 1: 视频帧 → FBO 离屏纹理（OES 外部纹理采样）
 *   Pass 2: FBO 纹理 → 屏幕（带桶形畸变的 Fragment Shader）
 */
class SBSRenderer(
    private val onSurfaceReady: (Surface) -> Unit,
    private val requestRender: () -> Unit
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    companion object {
        private const val TAG = "SBSRenderer"
    }

    // ===== 视频纹理 =====
    private var videoTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var frameAvailable = false
    private var vertexDirty = false

    // ===== FBO（离屏渲染目标）=====
    private var fboId = 0
    private var fboTextureId = 0
    private var fboWidth = 0
    private var fboHeight = 0

    // ===== Shader 程序 =====
    private var videoProgram = 0     // Pass 1: 视频 → FBO
    private var distortProgram = 0   // Pass 2: FBO → 屏幕（畸变）

    private var screenWidth = 0
    private var screenHeight = 0
    private var videoWidth = 0
    private var videoHeight = 0

    // ===== VR 显示参数 =====
    @Volatile private var displayScale = 0.85f
    @Volatile private var displayGap = 0

    // ===== 畸变参数 =====
    @Volatile private var distortionK1 = 0.0f
    @Volatile private var distortionK2 = 0.0f

    fun setDisplayParams(scale: Int, gap: Int) {
        displayScale = scale / 100f
        displayGap = gap
        requestRender()
    }

    fun setDistortion(k1: Float, k2: Float) {
        distortionK1 = k1
        distortionK2 = k2
        requestRender()
    }

    // SBS 3D 模式（视频本身是左右分屏的）
    @Volatile private var sbs3dMode = false

    fun setSBS3DMode(enabled: Boolean) {
        sbs3dMode = enabled
        requestRender()
    }

    private val stMatrix = FloatArray(16)

    // 全屏四边形顶点（根据视频宽高比动态调整）
    private var vertices = floatArrayOf(
        -1f, -1f, 0f,
         1f, -1f, 0f,
        -1f,  1f, 0f,
         1f,  1f, 0f
    )

    // 纹理坐标（普通 2D 模式：整幅视频）
    private val texCoords = floatArrayOf(
        0f, 0f,
        1f, 0f,
        0f, 1f,
        1f, 1f
    )

    // SBS 3D 左眼纹理坐标：只采样视频左半部 (x: 0.0 ~ 0.5)
    private val texCoordsLeftEye = floatArrayOf(
        0.0f, 0f,
        0.5f, 0f,
        0.0f, 1f,
        0.5f, 1f
    )

    // SBS 3D 右眼纹理坐标：只采样视频右半部 (x: 0.5 ~ 1.0)
    private val texCoordsRightEye = floatArrayOf(
        0.5f, 0f,
        1.0f, 0f,
        0.5f, 1f,
        1.0f, 1f
    )

    // Pass 2 用的全屏四边形（固定铺满 viewport）
    private val fullQuadVertices = floatArrayOf(
        -1f, -1f, 0f,
         1f, -1f, 0f,
        -1f,  1f, 0f,
         1f,  1f, 0f
    )

    private val fullQuadTexCoords = floatArrayOf(
        0f, 0f,
        1f, 0f,
        0f, 1f,
        1f, 1f
    )

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var texCoordBuffer: FloatBuffer
    private lateinit var texCoordLeftBuffer: FloatBuffer
    private lateinit var texCoordRightBuffer: FloatBuffer
    private lateinit var fullQuadVertexBuffer: FloatBuffer
    private lateinit var fullQuadTexCoordBuffer: FloatBuffer

    // ===== Pass 1 Shader: 视频 OES 纹理 → FBO =====
    private val videoVertexShader = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        uniform mat4 uSTMatrix;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    private val videoFragmentShader = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES uTexture;
        varying vec2 vTexCoord;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """.trimIndent()

    // ===== Pass 2 Shader: 普通 2D 纹理 + 桶形畸变 =====
    private val distortVertexShader = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val distortFragmentShader = """
        precision mediump float;
        uniform sampler2D uTexture;
        uniform float uK1;
        uniform float uK2;
        varying vec2 vTexCoord;
        void main() {
            // 将纹理坐标映射到 [-1, 1] 范围（以中心为原点）
            vec2 uv = vTexCoord * 2.0 - 1.0;
            float r2 = dot(uv, uv);
            float r4 = r2 * r2;
            
            // 桶形畸变公式
            float distortion = 1.0 + uK1 * r2 + uK2 * r4;
            vec2 distortedUV = uv * distortion;
            
            // 映射回 [0, 1]
            distortedUV = distortedUV * 0.5 + 0.5;
            
            // 超出范围的像素显示黑色
            if (distortedUV.x < 0.0 || distortedUV.x > 1.0 ||
                distortedUV.y < 0.0 || distortedUV.y > 1.0) {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
            } else {
                gl_FragColor = texture2D(uTexture, distortedUV);
            }
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        // 创建顶点缓冲
        vertexBuffer = createFloatBuffer(vertices)
        texCoordBuffer = createFloatBuffer(texCoords)
        texCoordLeftBuffer = createFloatBuffer(texCoordsLeftEye)
        texCoordRightBuffer = createFloatBuffer(texCoordsRightEye)
        fullQuadVertexBuffer = createFloatBuffer(fullQuadVertices)
        fullQuadTexCoordBuffer = createFloatBuffer(fullQuadTexCoords)

        // 创建视频外部纹理
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        videoTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // 创建 SurfaceTexture
        surfaceTexture = SurfaceTexture(videoTextureId)
        surfaceTexture!!.setOnFrameAvailableListener(this)

        // 编译 Pass 1 Shader（视频 → FBO）
        videoProgram = createProgram(videoVertexShader, videoFragmentShader)

        // 编译 Pass 2 Shader（FBO → 屏幕，带畸变）
        distortProgram = createProgram(distortVertexShader, distortFragmentShader)

        Matrix.setIdentityM(stMatrix, 0)

        // Surface 就绪
        val surface = Surface(surfaceTexture!!)
        onSurfaceReady(surface)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
        setupFBO(width / 2, height)  // FBO 大小 = 每只眼睛的半屏
        recalcVertices()
    }

    override fun onDrawFrame(gl: GL10?) {
        // 更新视频帧
        synchronized(this) {
            if (frameAvailable) {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(stMatrix)
                frameAvailable = false
            }
            if (vertexDirty) {
                vertexBuffer.position(0)
                vertexBuffer.put(vertices)
                vertexBuffer.position(0)
                vertexDirty = false
            }
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val halfWidth = screenWidth / 2
        val scale = displayScale
        val gap = displayGap
        val k1 = distortionK1
        val k2 = distortionK2

        val vpW = (halfWidth * scale).toInt()
        val vpH = (screenHeight * scale).toInt()
        val vpY = (screenHeight - vpH) / 2

        val useDistortion = (k1 != 0.0f || k2 != 0.0f)
        val isSBS3D = sbs3dMode

        if (!useDistortion) {
            // ===== 无畸变：直接渲染到屏幕 =====

            // 左眼
            drawVideoPass(if (isSBS3D) texCoordLeftBuffer else texCoordBuffer)
            val leftX = (halfWidth - vpW) / 2 - gap / 2
            GLES20.glViewport(leftX, vpY, vpW, vpH)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            cleanupVideoPass()

            // 右眼
            drawVideoPass(if (isSBS3D) texCoordRightBuffer else texCoordBuffer)
            val rightX = halfWidth + (halfWidth - vpW) / 2 + gap / 2
            GLES20.glViewport(rightX, vpY, vpW, vpH)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            cleanupVideoPass()

        } else {
            // ===== 有畸变：两个 Pass =====
            val leftX = (halfWidth - vpW) / 2 - gap / 2
            val rightX = halfWidth + (halfWidth - vpW) / 2 + gap / 2

            // --- 左眼 ---
            // Pass 1: 视频 → FBO
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glViewport(0, 0, fboWidth, fboHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawVideoPass(if (isSBS3D) texCoordLeftBuffer else texCoordBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            cleanupVideoPass()

            // Pass 2: FBO → 屏幕（带畸变）
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            drawDistortPass(k1, k2)
            GLES20.glViewport(leftX, vpY, vpW, vpH)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            cleanupDistortPass()

            // --- 右眼 ---
            // Pass 1: 视频 → FBO
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glViewport(0, 0, fboWidth, fboHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawVideoPass(if (isSBS3D) texCoordRightBuffer else texCoordBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            cleanupVideoPass()

            // Pass 2: FBO → 屏幕（带畸变）
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            drawDistortPass(k1, k2)
            GLES20.glViewport(rightX, vpY, vpW, vpH)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            cleanupDistortPass()
        }
    }

    /** 设置 Pass 1 的 GL 状态（视频纹理绑定） */
    private fun drawVideoPass(texBuf: FloatBuffer) {
        GLES20.glUseProgram(videoProgram)

        val posHandle = GLES20.glGetAttribLocation(videoProgram, "aPosition")
        val texHandle = GLES20.glGetAttribLocation(videoProgram, "aTexCoord")
        val stMatHandle = GLES20.glGetUniformLocation(videoProgram, "uSTMatrix")
        val textureHandle = GLES20.glGetUniformLocation(videoProgram, "uTexture")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        GLES20.glUniform1i(textureHandle, 0)
        GLES20.glUniformMatrix4fv(stMatHandle, 1, false, stMatrix, 0)

        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, texBuf)
    }

    /** 清理 Pass 1 的 GL 状态 */
    private fun cleanupVideoPass() {
        val posHandle = GLES20.glGetAttribLocation(videoProgram, "aPosition")
        val texHandle = GLES20.glGetAttribLocation(videoProgram, "aTexCoord")
        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(texHandle)
    }

    /** 设置 Pass 2 的 GL 状态（畸变 Shader） */
    private fun drawDistortPass(k1: Float, k2: Float) {
        GLES20.glUseProgram(distortProgram)

        val dPosHandle = GLES20.glGetAttribLocation(distortProgram, "aPosition")
        val dTexHandle = GLES20.glGetAttribLocation(distortProgram, "aTexCoord")
        val dTextureHandle = GLES20.glGetUniformLocation(distortProgram, "uTexture")
        val dK1Handle = GLES20.glGetUniformLocation(distortProgram, "uK1")
        val dK2Handle = GLES20.glGetUniformLocation(distortProgram, "uK2")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
        GLES20.glUniform1i(dTextureHandle, 0)
        GLES20.glUniform1f(dK1Handle, k1)
        GLES20.glUniform1f(dK2Handle, k2)

        GLES20.glEnableVertexAttribArray(dPosHandle)
        GLES20.glVertexAttribPointer(dPosHandle, 3, GLES20.GL_FLOAT, false, 0, fullQuadVertexBuffer)

        GLES20.glEnableVertexAttribArray(dTexHandle)
        GLES20.glVertexAttribPointer(dTexHandle, 2, GLES20.GL_FLOAT, false, 0, fullQuadTexCoordBuffer)
    }

    /** 清理 Pass 2 的 GL 状态 */
    private fun cleanupDistortPass() {
        val dPosHandle = GLES20.glGetAttribLocation(distortProgram, "aPosition")
        val dTexHandle = GLES20.glGetAttribLocation(distortProgram, "aTexCoord")
        GLES20.glDisableVertexAttribArray(dPosHandle)
        GLES20.glDisableVertexAttribArray(dTexHandle)
    }

    // ===== FBO 管理 =====

    private fun setupFBO(width: Int, height: Int) {
        // 清理旧的 FBO
        if (fboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            GLES20.glDeleteTextures(1, intArrayOf(fboTextureId), 0)
        }

        fboWidth = width
        fboHeight = height

        // 创建 FBO 纹理
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        fboTextureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // 创建 FBO 并挂载纹理
        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        fboId = fbos[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, fboTextureId, 0)

        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "FBO 创建失败! status=$status")
        }

        // 恢复默认帧缓冲
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        Log.d(TAG, "FBO 创建完成: ${width}x${height}")
    }

    // ===== 回调 & 工具 =====

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        synchronized(this) { frameAvailable = true }
        requestRender()
    }

    fun setVideoSize(width: Int, height: Int) {
        synchronized(this) {
            videoWidth = width
            videoHeight = height
        }
        recalcVertices()
        requestRender()
    }

    private fun recalcVertices() {
        synchronized(this) {
            if (videoWidth <= 0 || videoHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) return

            val halfViewportW = screenWidth / 2f
            val halfViewportH = screenHeight.toFloat()
            val viewportAR = halfViewportW / halfViewportH
            
            // 视频本身的真实比例
            val rawVideoAR = videoWidth.toFloat() / videoHeight
            
            // 计算单眼的最终目标显示比例
            val targetAR = if (sbs3dMode) {
                if (rawVideoAR > 2.5f) {
                    // 比例大于 2.5 (如 32:9, 3840x1080) -> Full SBS
                    // 每一半画面本身就是正常比例（16:9），所以单眼显示比例 = 总比例 / 2
                    Log.d(TAG, "检测到 Full SBS (AR=$rawVideoAR)")
                    rawVideoAR / 2f
                } else {
                    // 比例小于 2.5 (如 16:9, 1920x1080) -> Half SBS
                    // 每一半画面被水平挤压了 50%（成了 8:9），我们需要把它拉伸回原来的比例
                    // 也就是：在取 0~0.5 纹理时，我们把它贴在一个完整的 16:9 (rawVideoAR) 的面片上
                    Log.d(TAG, "检测到 Half SBS (AR=$rawVideoAR)")
                    rawVideoAR
                }
            } else {
                // 普通 2D 模式
                rawVideoAR
            }

            var scaleX = 1f
            var scaleY = 1f

            if (targetAR > viewportAR) {
                scaleY = viewportAR / targetAR
            } else {
                scaleX = targetAR / viewportAR
            }

            vertices = floatArrayOf(
                -scaleX, -scaleY, 0f,
                 scaleX, -scaleY, 0f,
                -scaleX,  scaleY, 0f,
                 scaleX,  scaleY, 0f
            )
            vertexDirty = true
        }
    }

    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
        if (videoProgram != 0) { GLES20.glDeleteProgram(videoProgram); videoProgram = 0 }
        if (distortProgram != 0) { GLES20.glDeleteProgram(distortProgram); distortProgram = 0 }
        if (fboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            GLES20.glDeleteTextures(1, intArrayOf(fboTextureId), 0)
            fboId = 0
        }
    }

    private fun createProgram(vertShaderCode: String, fragShaderCode: String): Int {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertShaderCode)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragShaderCode)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw RuntimeException("GL program link failed: $log")
        }
        return prog
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log")
        }
        return shader
    }

    private fun createFloatBuffer(data: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(data)
            .also { it.position(0) }
    }
}
