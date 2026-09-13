package com.ians.korno.core.domain.model

data class Activity(
    val id: Long = 0L,
    val startTimeEpochMillis: Long,
    val endTimeEpochMillis: Long? = null,
    val activityType: ActivityType,
    val title: String? = null,
    val note: String? = null,
    val distanceMeters: Double = 0.0,
    val movingTimeMillis: Long = 0L,
    val elapsedTimeMillis: Long = 0L,
    val averageSpeedMetersPerSecond: Double = 0.0,
    val maxSpeedMetersPerSecond: Double = 0.0,
    val elevationGainMeters: Double = 0.0,
    val isManuallyEdited: Boolean = false,
    /**
     * Запись завершена, но пользователь ещё не подтвердил её на экране сохранения.
     * Так помечаются активности, остановленные кнопкой «Стоп» в уведомлении:
     * данные уже в БД, но экран сохранения нужно показать при возврате в приложение.
     */
    val isPendingSummary: Boolean = false,
    /** `null` — шаги не считались (тип активности, нет датчика или разрешения); иначе счётчик. */
    val stepCount: Int? = null,
    /** Запись велась в авторежиме: вид движения определял детектор, сегментов может быть много. */
    val isAutoMode: Boolean = false,
)
