package com.ians.korno.core.logging

enum class LogLevel(val priority: Int) {
    VERBOSE(0),
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4),
}

enum class LogCategory {
    APP,
    TRACKING,
    GPS,
    DATABASE,
    NETWORK,
    EXPORT,
    UI,

    /** Сигналы, приходящие не от приложения: распознавание активности, питание, экран. */
    SYSTEM,
}

/**
 * Small application-owned facade over the concrete logger. Keeping call sites behind this
 * interface lets release builds bind a no-op implementation and keeps tests independent of
 * Android's Logcat.
 */
interface AppLogger {
    fun log(
        level: LogLevel,
        category: LogCategory,
        tag: String,
        throwable: Throwable? = null,
        fields: Map<String, String> = emptyMap(),
        containsSensitiveLocation: Boolean = false,
        message: () -> String,
    )
}

object NoOpAppLogger : AppLogger {
    override fun log(
        level: LogLevel,
        category: LogCategory,
        tag: String,
        throwable: Throwable?,
        fields: Map<String, String>,
        containsSensitiveLocation: Boolean,
        message: () -> String,
    ) = Unit
}

fun AppLogger.verbose(
    category: LogCategory,
    tag: String,
    fields: Map<String, String> = emptyMap(),
    containsSensitiveLocation: Boolean = false,
    message: () -> String,
) = log(
    level = LogLevel.VERBOSE,
    category = category,
    tag = tag,
    fields = fields,
    containsSensitiveLocation = containsSensitiveLocation,
    message = message,
)

fun AppLogger.debug(
    category: LogCategory,
    tag: String,
    fields: Map<String, String> = emptyMap(),
    message: () -> String,
) = log(LogLevel.DEBUG, category, tag, fields = fields, message = message)

fun AppLogger.info(
    category: LogCategory,
    tag: String,
    fields: Map<String, String> = emptyMap(),
    message: () -> String,
) = log(LogLevel.INFO, category, tag, fields = fields, message = message)

fun AppLogger.warn(
    category: LogCategory,
    tag: String,
    throwable: Throwable? = null,
    fields: Map<String, String> = emptyMap(),
    message: () -> String,
) = log(LogLevel.WARN, category, tag, throwable, fields, message = message)

fun AppLogger.error(
    category: LogCategory,
    tag: String,
    throwable: Throwable? = null,
    fields: Map<String, String> = emptyMap(),
    message: () -> String,
) = log(LogLevel.ERROR, category, tag, throwable, fields, message = message)
