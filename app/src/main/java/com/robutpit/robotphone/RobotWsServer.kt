package com.robutpit.robotphone

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.Collections

/**
 * Телефон больше не ходит наружу в Render — сам является WebSocket-сервером
 * на локальном Wi-Fi. ESP32 и браузер оператора подключаются напрямую
 * к IP телефона (порт 8080), без интернета. Тот же протокол сообщений,
 * что был на сервере (hello/role, waypoints, nav_control, phone_drive,
 * video_frame, robot_status), просто сервер теперь встроен в приложение.
 */
class RobotWsServer(
    port: Int,
    private val onOperatorMessage: (JSONObject) -> Unit,
    private val onRobotMessage: (JSONObject) -> Unit,
) : WebSocketServer(InetSocketAddress(port)) {

    private val operatorConns = Collections.synchronizedSet(mutableSetOf<WebSocket>())
    private val robotConns = Collections.synchronizedSet(mutableSetOf<WebSocket>())
    private val roleOf = Collections.synchronizedMap(mutableMapOf<WebSocket, String>())

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        // роль клиент пришлёт первым сообщением {"type":"hello","role":"operator"/"robot"}
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val json = try { JSONObject(message) } catch (e: Exception) { return }
        when (json.optString("type")) {
            "hello" -> {
                val role = json.optString("role")
                roleOf[conn] = role
                if (role == "operator") operatorConns.add(conn)
                if (role == "robot") robotConns.add(conn)
            }
            // Всё остальное от оператора (маршрут, старт/стоп, гимбал, ручное управление)
            // обрабатывается логикой навигации в сервисе — просто прокидываем выше.
            else -> {
                when (roleOf[conn]) {
                    "operator" -> onOperatorMessage(json)
                    "robot" -> onRobotMessage(json) // статус/датчики ESP32 -> дальше оператору через Render
                }
            }
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        operatorConns.remove(conn)
        robotConns.remove(conn)
        roleOf.remove(conn)
    }

    override fun onError(conn: WebSocket?, ex: Exception) { /* соединение просто отвалится, ESP32 переподключится сама */ }

    override fun onStart() { /* сервер поднят и слушает */ }

    /** Команда на моторы — единственное, что реально нужно ESP32. */
    fun sendToRobot(json: JSONObject) {
        val text = json.toString()
        synchronized(robotConns) { robotConns.forEach { it.send(text) } }
    }

    /** Видео, GPS-точка, статус — всё это видит только оператор. */
    fun sendToOperator(json: JSONObject) {
        val text = json.toString()
        synchronized(operatorConns) { operatorConns.forEach { it.send(text) } }
    }

    fun hasRobotConnected(): Boolean = robotConns.isNotEmpty()
}
