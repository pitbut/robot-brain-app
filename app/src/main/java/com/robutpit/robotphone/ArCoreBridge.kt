package com.robutpit.robotphone

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Расстояния до препятствий в метрах (лево/центр/право — тот же смысл,
 * что раньше был у 3 сонаров на роботе, только теперь считает камера).
 * Float.MAX_VALUE = "не знаем/свободно".
 */
data class Obstacles(
    val left: Float = Float.MAX_VALUE,
    val center: Float = Float.MAX_VALUE,
    val right: Float = Float.MAX_VALUE,
)

/**
 * ARCore-рендерер живёт в MainActivity (ему нужен GL-контекст),
 * а WebSocket и вся логика навигации — в RobotBrainService.
 * Этот объект — простой мост между ними внутри одного процесса.
 */
object ArCoreBridge {
    val obstacles = MutableStateFlow(Obstacles())
    val depthSupported = MutableStateFlow<Boolean?>(null) // null = ещё не знаем

    /** Сервис подписывается сюда, чтобы получать готовые JPEG-кадры для отправки на сервер. */
    var onVideoFrame: ((ByteArray) -> Unit)? = null
}
