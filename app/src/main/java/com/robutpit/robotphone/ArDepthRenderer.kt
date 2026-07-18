package com.robutpit.robotphone

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.google.ar.core.Session
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Не рисует настоящую картинку с камеры (это не нужно — видео для оператора
 * и так уходит на сервер отдельными JPEG-кадрами). Единственная задача —
 * держать активный OpenGL-контекст, которого требует ARCore, и на каждом
 * кадре: а) вытащить JPEG для видео, б) вытащить карту глубины и понять,
 * свободно ли слева/по центру/справа перед роботом.
 */
class ArDepthRenderer(private val getSession: () -> Session?) : GLSurfaceView.Renderer {

    private var textureId = 0
    private var lastVideoFrameAt = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        getSession()?.setCameraTextureName(textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        getSession()?.setDisplayGeometry(0, width, height)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val session = getSession() ?: return
        val frame = try {
            session.update()
        } catch (e: Exception) {
            return
        }

        // ── Видео для оператора: ~3 кадра/сек, чтобы не грузить канал ──
        val now = System.currentTimeMillis()
        if (now - lastVideoFrameAt >= 300) {
            try {
                val image = frame.acquireCameraImage()
                lastVideoFrameAt = now
                try {
                    val jpeg = mediaImageToJpeg(image)
                    ArCoreBridge.onVideoFrame?.invoke(jpeg)
                } finally {
                    image.close()
                }
            } catch (_: Exception) { /* кадр камеры пока не готов — пропускаем */ }
        }

        // ── Глубина: объезд препятствий ──
        try {
            val depthImage = frame.acquireDepthImage16Bits()
            try {
                ArCoreBridge.depthSupported.value = true
                ArCoreBridge.obstacles.value = analyzeDepth(depthImage)
            } finally {
                depthImage.close()
            }
        } catch (_: Exception) {
            // Депс-карта пока не готова либо не поддерживается устройством —
            // ArCoreBridge.depthSupported тогда так и останется null/false,
            // навигация в сервисе просто не будет учитывать препятствия.
        }
    }

    // Простая конвертация YUV_420_888 -> NV21 -> JPEG. Для превью-качества
    // (низкое разрешение, JPEG q=50) этого достаточно.
    private fun mediaImageToJpeg(image: Image): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 50, out)
        return out.toByteArray()
    }

    // Карта глубины ARCore: 16 бит на пиксель, младшие 13 бит — миллиметры.
    // Смотрим только на нижнюю треть кадра (там, куда едет робот),
    // делим по ширине на лево/центр/право — как раньше было с 3 сонарами.
    private fun analyzeDepth(image: Image): Obstacles {
        val plane = image.planes[0]
        val buffer = plane.buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val width = image.width
        val height = image.height
        val rowStrideShorts = plane.rowStride / 2
        val yStart = (height * 0.66f).toInt()
        val third = width / 3

        fun regionMinMeters(xStart: Int, xEnd: Int): Float {
            var minMm = Int.MAX_VALUE
            for (y in yStart until height) {
                for (x in xStart until xEnd) {
                    val idx = y * rowStrideShorts + x
                    if (idx >= buffer.capacity()) continue
                    val raw = buffer.get(idx).toInt() and 0xFFFF
                    val depthMm = raw and 0x1FFF
                    if (depthMm in 1 until minMm) minMm = depthMm
                }
            }
            return if (minMm == Int.MAX_VALUE) Float.MAX_VALUE else minMm / 1000f
        }

        return Obstacles(
            left = regionMinMeters(0, third),
            center = regionMinMeters(third, 2 * third),
            right = regionMinMeters(2 * third, width),
        )
    }
}
