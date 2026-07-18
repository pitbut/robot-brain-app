package com.robutpit.robotphone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Looper
import android.util.Base64
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Держит "телефон-мозг" робота живым: шлёт GPS+компас+видео на сервер
 * по WebSocket, пока явно не остановлен. Работает как foreground-сервис,
 * чтобы Android не убил его при сворачивании приложения/блокировке экрана.
 *
 * Кнопка "Остановить" (в приложении и в уведомлении) вызывает ACTION_STOP,
 * который аккуратно закрывает сокет, GPS, компас и камеру — без необходимости
 * лезть в настройки телефона, чтобы прибить процесс вручную.
 */
class RobotBrainService : LifecycleService(), SensorEventListener {

    companion object {
        const val ACTION_START = "com.robutpit.robotphone.START"
        const val ACTION_STOP = "com.robutpit.robotphone.STOP"
        const val EXTRA_HOST = "host"
        const val CHANNEL_ID = "robot_brain_channel"
        const val NOTIF_ID = 1
    }

    private var webSocket: WebSocket? = null
    private lateinit var httpClient: OkHttpClient
    private lateinit var fusedLocation: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var lastHeading: Float? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var lastFrameSentAt = 0L
    private var running = false

    override fun onCreate() {
        super.onCreate()
        httpClient = OkHttpClient.Builder()
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
                startForeground(NOTIF_ID, buildNotification("Подключение..."))
                startEverything(host)
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Робот — телефон-мозг",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, RobotBrainService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Робот — телефон-мозг")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stopPending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun startEverything(host: String) {
        if (running) return
        running = true
        RobotBrainState.status.value = "Подключение к серверу..."
        connectWebSocket(host)
        startLocationUpdates()
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        startCamera()
    }

    /** Полная остановка: закрывает сокет, GPS, компас, камеру — без "убийства" через настройки. */
    private fun stopEverything() {
        running = false
        webSocket?.close(1000, "stopped by user")
        webSocket = null
        locationCallback?.let { fusedLocation.removeLocationUpdates(it) }
        locationCallback = null
        sensorManager.unregisterListener(this)
        cameraProvider?.unbindAll()
        cameraProvider = null
        RobotBrainState.status.value = "Остановлено"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ───────────────────────── WebSocket ─────────────────────────
    private fun connectWebSocket(host: String) {
        val url = "wss://$host/ws"
        val request = Request.Builder().url(url).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val hello = JSONObject().put("type", "hello").put("role", "phone")
                webSocket.send(hello.toString())
                RobotBrainState.status.value = "Онлайн, шлём GPS/видео"
                updateNotification("Онлайн")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                RobotBrainState.status.value = "Ошибка соединения: ${t.message}"
                updateNotification("Ошибка соединения")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                RobotBrainState.status.value = "Соединение закрыто"
            }
        })
    }

    // ───────────────────────── GPS ─────────────────────────
    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val msg = JSONObject().apply {
                    put("type", "phone_gps")
                    put("lat", loc.latitude)
                    put("lng", loc.longitude)
                    put("heading", lastHeading ?: JSONObject.NULL)
                    put("acc", loc.accuracy)
                }
                webSocket?.send(msg.toString())
                RobotBrainState.status.value =
                    "GPS: %.6f, %.6f (\u00b1%.0fм)  Компас: %s".format(
                        loc.latitude, loc.longitude, loc.accuracy,
                        lastHeading?.roundToInt()?.toString()?.plus("\u00b0") ?: "нет"
                    )
            }
        }
        fusedLocation.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
    }

    // ───────────────────────── Компас ─────────────────────────
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val rotMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotMatrix, orientation)
        var heading = Math.toDegrees(orientation[0].toDouble()).toFloat()
        if (heading < 0) heading += 360f
        lastHeading = heading
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ───────────────────────── Камера ─────────────────────────
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val now = System.currentTimeMillis()
                if (now - lastFrameSentAt >= 300) { // ~3 кадра/сек — хватает для наблюдения
                    lastFrameSentAt = now
                    try {
                        val jpeg = imageProxyToJpeg(imageProxy)
                        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
                        val msg = JSONObject().put("type", "video_frame").put("data", b64)
                        webSocket?.send(msg.toString())
                    } catch (_: Exception) { /* пропускаем битый кадр, не критично */ }
                }
                imageProxy.close()
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            } catch (e: Exception) {
                RobotBrainState.status.value = "Ошибка камеры: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // Упрощённая конвертация YUV_420_888 -> NV21 -> JPEG.
    // Для превью-качества (низкое разрешение, JPEG q=50) этого достаточно;
    // если на конкретном устройстве видео идёт с искажёнными цветами —
    // нужен более строгий конвертер, учитывающий pixelStride/rowStride.
    private fun imageProxyToJpeg(image: ImageProxy): ByteArray {
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
}
