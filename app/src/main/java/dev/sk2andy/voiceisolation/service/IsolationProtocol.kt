package dev.sk2andy.voiceisolation.service

internal object IsolationProtocol {
    const val IDLE = 0
    const val CONNECTING = 1
    const val LOADING_MODEL = 2
    const val RUNNING = 3
    const val STOPPING = 4
    const val ERROR = 5
}
