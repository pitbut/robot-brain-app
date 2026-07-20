package com.robutpit.robotphone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Looper
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Два независимых канала связи:
 *  1) НАРУЖУ, через интернет, в Render — там сидит оператор (клиент), который
 *     физически далеко от робота. Через этот канал: видео/GPS/статус -> оператору,
 *     маршрут/старт-стоп -> от оператора.
 *  2) ЛОКАЛЬНО — телефон сам поднимает WebSocket-сервер, ESP32 подключается
 *     к точке доступа телефона напрямую, без интернета. Через этот канал
 *     телефон шлёт готовые команды на моторы (phone_drive) и получает
 *     показания датчиков (если когда-нибудь подключишь сонары).
 */
class RobotBrainService : LifecycleService(), SensorEventListener {

    companion object {
        const val ACTION_START = "com.robutpit.robotphone.START"
        const val ACTION_STOP = "com.robutpit.robotphone.STOP"
        const val EXTRA_HOST = "host"
        const val CHANNEL_ID = "robot_brain_channel"
        const val NOTIF_ID = 1
        const val LOCAL_WS_PORT = 8080
        const val DISCOVERY_PORT = 8888 // сюда телефон шлёт широковещательные "я тут" пакеты для ESP32

        const val WP_ARRIVE_M = 3.0
        const val MIN_BEARING_DIST = 8.0
        const val TURN_THRESHOLD = 8.0
        const val NAV_SPEED_BASE = 170
        const val NAV_SPEED_MIN = 90
        const val STEER_GAIN = 2.2

        const val OBST_STOP_M = 0.5f
        const val OBST_SIDE_M = 0.35f
    }

    // ── Наружу, в Render (оператор) ──
    private var remoteSocket: WebSocket? = null
    private lateinit var httpClient: OkHttpClient

    // ── Локально, для ESP32 ──
    private var localServer: RobotWsServer? = null

    private lateinit var fusedLocation: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var lastHeading: Double? = null
    private var lastLocation: Pair<Double, Double>? = null
    private var running = false
    private var navJob: Job? = null
    private var discoveryJob: Job? = null
    private var discoverySocket: DatagramSocket? = null

    private val waypoints = mutableListOf<Pair<Double, Double>>()
    private var currentWpIndex = 0
    private var navRunningLocal = false
    private var connectionStatus = "Подключение..."
    private var localServerInfo = ""
    private var lastGpsText = ""

    private fun updateStatusDisplay() {
        val parts = listOfNotNull(
            connectionStatus,
            localServerInfo.takeIf { it.isNotEmpty() },
            lastGpsText.takeIf { it.isNotEmpty() },
        )
        RobotBrainState.status.value = parts.joinToString("\n")
    }

    override fun onCreate() {
        super.onCreate()
        httpClient = OkHttpClient.Builder()
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        createNotificationChannel()

        ArCoreBridge.onVideoFrame = { bytes ->
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val msg = JSONObject().put("type", "video_frame").put("data", b64)
            remoteSocket?.send(msg.toString())
        }
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
        connectionStatus = "Подключение к серверу..."
        updateStatusDisplay()
        connectToRender(host)
        startLocalServer()
        startLocationUpdates()
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        startNavLoop()
    }

    private fun stopEverything() {
        running = false
        navJob?.cancel()
        navJob = null
        discoveryJob?.cancel()
        discoveryJob = null
        discoverySocket?.close()
        discoverySocket = null
        sendDriveToRobot(0, 0)
        remoteSocket?.close(1000, "stopped by user")
        remoteSocket = null
        localServer?.stop()
        localServer = null
        locationCallback?.let { fusedLocation.removeLocationUpdates(it) }
        locationCallback = null
        sensorManager.unregisterListener(this)
        connectionStatus = "Остановлено"
        lastGpsText = ""
        localServerInfo = ""
        updateStatusDisplay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ───────────────────────── Локальный сервер для ESP32 ─────────────────────────
    private fun startLocalServer() {
        localServer = RobotWsServer(
            LOCAL_WS_PORT,
            onOperatorMessage = { /* оператор сюда не подключается напрямую */ },
            onRobotMessage = { statusJson -> remoteSocket?.send(statusJson.toString()) },
        )
        localServer?.start()
        val ip = getLocalIpAddress() ?: "не найден — проверь Wi-Fi"
        localServerInfo = "ESP32 подключается на: $ip:$LOCAL_WS_PORT"
        updateStatusDisplay()
        startDiscoveryListener()
    }

    /** IP телефона в сети его точки доступа — именно на него должна стучаться ESP32.
     *  Раньше эта функция брала первый попавшийся IPv4-адрес с любого интерфейса
     *  (включая мобильный интернет) — из-за этого показывался неправильный IP.
     *  Теперь ищем именно интерфейс точки доступа (обычно "ap0"/"swlan0" на Android),
     *  и только если не нашли — показываем ВСЕ найденные адреса, чтобы можно было
     *  сверить руками с тем, что видно в настройках точки доступа. */
    private fun getLocalIpAddress(): String? {
        try {
            val candidates = mutableListOf<Pair<String, String>>() // (имя интерфейса, ip)
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        candidates.add(iface.name to (addr.hostAddress ?: continue))
                    }
                }
            }
            // Точка доступа Android обычно называется ap0, swlan0 или похоже — ищем в первую очередь её.
            val apMatch = candidates.firstOrNull {
                it.first.contains("ap", ignoreCase = true) || it.first.contains("swlan", ignoreCase = true)
            }
            if (apMatch != null) return apMatch.second

            // Не нашли явный AP-интерфейс — покажем все варианты, чтобы можно было сверить руками.
            if (candidates.isNotEmpty()) {
                return candidates.joinToString(" / ") { "${it.first}:${it.second}" } +
                    " (не удалось точно определить точку доступа — сверь с настройками)"
            }
        } catch (_: Exception) { }
        return null
    }

    /**
     * IP телефона в сети точки доступа может меняться от раза к разу, а широковещательные
     * пакеты ОТ хоста (телефона) К подключённым устройствам многие реализации Android
     * не пропускают вообще. Поэтому вместо "телефон кричит в сеть" — наоборот: ESP32
     * сама рассылает broadcast "я тут", а телефон его слушает и отвечает ей ЛИЧНО
     * (unicast, не broadcast) — от хоста к своему клиенту unicast всегда доходит,
     * иначе не работал бы даже раздаваемый интернет.
     */
    private fun startDiscoveryListener() {
        discoveryJob?.cancel()
        discoveryJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(DISCOVERY_PORT)
                socket.broadcast = true
                discoverySocket = socket
                val buf = ByteArray(64)
                while (isActive) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet) // блокирующий вызов, разбудит socket.close() при остановке
                        val text = String(packet.data, 0, packet.length)
                        if (text.startsWith("ESP32_HELLO")) {
                            val reply = "PHONE_HERE:$LOCAL_WS_PORT".toByteArray()
                            socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                        }
                    } catch (e: Exception) {
                        if (!isActive) break
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun sendDriveToRobot(left: Int, right: Int) {
        val msg = JSONObject().put("type", "phone_drive").put("left", left).put("right", right)
        localServer?.sendToRobot(msg)
    }

    // ───────────────────────── Render (оператор) ─────────────────────────
    private fun connectToRender(host: String) {
        val url = "wss://$host/ws"
        val request = Request.Builder().url(url).build()
        remoteSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val hello = JSONObject().put("type", "hello").put("role", "phone")
                webSocket.send(hello.toString())
                connectionStatus = "Онлайн (связь с оператором через Render)"
                updateStatusDisplay()
                updateNotification("Онлайн")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleOperatorMessage(text)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connectionStatus = "ОШИБКА СОЕДИНЕНИЯ С RENDER: ${t.message}"
                updateStatusDisplay()
                updateNotification("Ошибка соединения")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connectionStatus = "Соединение с Render закрыто (код $code)"
                updateStatusDisplay()
            }
        })
    }

    private fun handleOperatorMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "waypoints" -> {
                    val arr = json.getJSONArray("points")
                    waypoints.clear()
                    for (i in 0 until arr.length()) {
                        val p = arr.getJSONObject(i)
                        waypoints.add(p.getDouble("lat") to p.getDouble("lng"))
                    }
                    currentWpIndex = 0
                }
                "nav_control" -> {
                    when (json.optString("cmd")) {
                        "start" -> { currentWpIndex = 0; navRunningLocal = true }
                        "stop" -> { navRunningLocal = false; sendDriveToRobot(0, 0) }
                    }
                    localServer?.sendToRobot(json) // ESP32 должна знать, что автопилот включён/выключен
                }
                "gimbal" -> localServer?.sendToRobot(json) // прокидываем ESP32 напрямую локально
            }
        } catch (_: Exception) { }
    }

    private fun sendNavDone() {
        remoteSocket?.send(JSONObject().put("type", "nav_done").toString())
    }

    // ───────────────────────── Цикл навигации (~3 раза в секунду) ─────────────────────────
    private fun startNavLoop() {
        navJob?.cancel()
        navJob = lifecycleScope.launch {
            while (isActive) {
                if (navRunningLocal) navTick()
                delay(300)
            }
        }
    }

    private fun navTick() {
        val drive = obstacleOverride() ?: computeWaypointDrive()
        sendDriveToRobot(drive.first, drive.second)
    }

    private fun obstacleOverride(): Pair<Int, Int>? {
        val o = ArCoreBridge.obstacles.value
        if (o.center > OBST_STOP_M && o.left > OBST_SIDE_M && o.right > OBST_SIDE_M) return null

        if (o.center <= OBST_STOP_M) {
            return if (o.left > OBST_SIDE_M || o.right > OBST_SIDE_M) {
                if (o.left > o.right) (-NAV_SPEED_MIN) to NAV_SPEED_MIN
                else NAV_SPEED_MIN to (-NAV_SPEED_MIN)
            } else {
                (-NAV_SPEED_MIN) to (-NAV_SPEED_MIN)
            }
        }
        if (o.left <= OBST_SIDE_M) return steerSpeeds(NAV_SPEED_MIN + 20, 20.0)
        if (o.right <= OBST_SIDE_M) return steerSpeeds(NAV_SPEED_MIN + 20, -20.0)
        return null
    }

    private fun computeWaypointDrive(): Pair<Int, Int> {
        val loc = lastLocation ?: return 0 to 0
        if (waypoints.isEmpty() || currentWpIndex >= waypoints.size) return 0 to 0

        var target = waypoints[currentWpIndex]
        var dist = gpsDistM(loc.first, loc.second, target.first, target.second)

        if (dist < WP_ARRIVE_M) {
            currentWpIndex++
            if (currentWpIndex >= waypoints.size) {
                navRunningLocal = false
                sendNavDone()
                return 0 to 0
            }
            target = waypoints[currentWpIndex]
            dist = gpsDistM(loc.first, loc.second, target.first, target.second)
        }

        val heading = lastHeading

        return if (dist < MIN_BEARING_DIST || heading == null) {
            NAV_SPEED_BASE to NAV_SPEED_BASE
        } else {
            val bearing = gpsBearing(loc.first, loc.second, target.first, target.second)
            var diff = angleDiff(bearing, heading)
            if (abs(diff) < TURN_THRESHOLD) diff = 0.0
            steerSpeeds(NAV_SPEED_BASE, diff)
        }
    }

    private fun steerSpeeds(base: Int, diffDeg: Double): Pair<Int, Int> {
        val delta = (STEER_GAIN * diffDeg).roundToInt()
        var spL = (base + delta).coerceIn(-255, 255)
        var spR = (base - delta).coerceIn(-255, 255)
        if (base > 0) {
            spL = maxOf(spL, NAV_SPEED_MIN)
            spR = maxOf(spR, NAV_SPEED_MIN)
        }
        return spL to spR
    }

    // ───────────────────────── GPS-геометрия ─────────────────────────
    private fun gpsDistM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun gpsBearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLng = Math.toRadians(lng2 - lng1)
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val y = sin(dLng) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLng)
        val b = Math.toDegrees(atan2(y, x))
        return (b + 360.0) % 360.0
    }

    private fun angleDiff(a: Double, b: Double): Double {
        return ((a - b + 540.0) % 360.0) - 180.0
    }

    // ───────────────────────── GPS ─────────────────────────
    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                lastLocation = loc.latitude to loc.longitude
                val msg = JSONObject().apply {
                    put("type", "phone_gps")
                    put("lat", loc.latitude)
                    put("lng", loc.longitude)
                    put("heading", lastHeading ?: JSONObject.NULL)
                    put("acc", loc.accuracy)
                }
                remoteSocket?.send(msg.toString())
                lastGpsText = "GPS: %.6f, %.6f (\u00b1%.0fм)".format(
                    loc.latitude, loc.longitude, loc.accuracy
                )
                if (!navRunningLocal) updateStatusDisplay()
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
        var heading = Math.toDegrees(orientation[0].toDouble())
        if (heading < 0) heading += 360.0
        lastHeading = heading
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
