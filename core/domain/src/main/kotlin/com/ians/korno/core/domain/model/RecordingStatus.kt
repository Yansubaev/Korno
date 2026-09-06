package com.ians.korno.core.domain.model

enum class RecordingStatus {
    IDLE,

    /**
     * Пользователь нажал «Начать», но сигнала ещё нет: активность уже создана,
     * а секундомер и трек стоят на месте до первой надёжной точки.
     */
    WAITING_FOR_GPS,
    RECORDING,
    MANUAL_PAUSE,
    AUTO_PAUSE,
    STOPPED,
}
