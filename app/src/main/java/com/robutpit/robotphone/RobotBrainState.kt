package com.robutpit.robotphone

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Простой обмен статусом между RobotBrainService и MainActivity —
 * оба живут в одном процессе, отдельная шина сообщений не нужна.
 */
object RobotBrainState {
    val status = MutableStateFlow("Остановлено")
}
