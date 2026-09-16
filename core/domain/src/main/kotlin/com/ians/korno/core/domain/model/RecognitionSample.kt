package com.ians.korno.core.domain.model

/** Вид движения в терминах Activity Recognition — ровно то, что пишется в лог. */
enum class RecognizedActivity { IN_VEHICLE, ON_BICYCLE, ON_FOOT, WALKING, RUNNING, STILL, TILTING, UNKNOWN }

/**
 * Один ответ Activity Recognition: уверенность 0–100 по каждому названному варианту, не
 * только по лучшему. Пишется всю запись авторежима — по всей последовательности вид
 * движения определяется при сохранении.
 */
data class RecognitionSample(
    val activityId: Long = 0L,
    val timestampEpochMillis: Long,
    val confidences: Map<RecognizedActivity, Int>,
)
