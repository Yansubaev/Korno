package com.ians.korno.core.logging

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.core.content.FileProvider
import co.touchlab.kermit.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class DebugLogger(
    private val context: Context,
) : AppLogger, LogManager, ActivityLogScope {
    private val sessionId = UUID.randomUUID().toString().take(8)
    private val settingsDataSource = DataStoreDebugLogSettingsDataSource(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<LogCommand>(capacity = LOG_QUEUE_CAPACITY)
    private val droppedRecords = AtomicInteger(0)
    private val bootstrapLock = Any()
    private val bootstrapRequests = ArrayDeque<LogRequest>()
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }
    private val fileStore = LogFileStore(
        logsDirectory = File(context.noBackupFilesDir, "debug-logs"),
        shareDirectory = File(context.cacheDir, "debug-diagnostics"),
        sessionId = sessionId,
        uriForFile = { file ->
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.diagnostics",
                file,
            ).toString()
        },
        fileHeader = ::fileHeaderLine,
    )

    /** Чем записан лог; не меняется за время жизни процесса. */
    private val buildFields: Map<String, String> by lazy {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        mapOf(
            "gitSha" to BuildConfig.GIT_SHA,
            "gitDirty" to BuildConfig.GIT_DIRTY.toString(),
            "versionName" to packageInfo?.versionName.orEmpty(),
            "sdkInt" to Build.VERSION.SDK_INT.toString(),
            "playServicesVersion" to playServicesVersion(),
        )
    }

    @Volatile
    private var settingsLoaded = false

    @Volatile
    private var currentSettings = LogSettings(enabled = false)

    @Volatile
    override var currentActivityId: Long? = null
        private set

    init {
        scope.launch {
            for (command in queue) {
                when (command) {
                    is LogCommand.Write -> try {
                        fileStore.append(
                            jsonLine = json.encodeToString(command.record),
                            activityId = command.record.activityId,
                            level = command.level,
                        )
                    } catch (error: Exception) {
                        Logger.e { "[APP/DebugLogger] File logging failed: ${error.message}" }
                    }
                    is LogCommand.Barrier -> command.completion.complete(Unit)
                }
            }
        }
        scope.launch {
            settingsDataSource.observeSettings().collect { settings ->
                val pending = synchronized(bootstrapLock) {
                    currentSettings = settings
                    settingsLoaded = true
                    bootstrapRequests.toList().also { bootstrapRequests.clear() }
                }
                pending.forEach(::process)
            }
        }
        installCrashHandler()
    }

    override fun log(
        level: LogLevel,
        category: LogCategory,
        tag: String,
        throwable: Throwable?,
        fields: Map<String, String>,
        containsSensitiveLocation: Boolean,
        message: () -> String,
    ) {
        val request = LogRequest(
            level = level,
            category = category,
            tag = tag,
            throwable = throwable,
            fields = fields,
            containsSensitiveLocation = containsSensitiveLocation,
            // Область берётся в момент вызова, а не записи: запись может быть отложена
            // до загрузки настроек, и к тому времени активность успевает смениться.
            activityId = currentActivityId,
            message = message,
        )
        if (!settingsLoaded) {
            synchronized(bootstrapLock) {
                if (!settingsLoaded) {
                    if (bootstrapRequests.size == BOOTSTRAP_BUFFER_CAPACITY) {
                        bootstrapRequests.removeFirst()
                    }
                    bootstrapRequests.addLast(request)
                    return
                }
            }
        }
        process(request)
    }

    override fun begin(activityId: Long) {
        currentActivityId = activityId
    }

    override fun end() {
        currentActivityId = null
    }

    override fun observeSettings(): Flow<LogSettings> = settingsDataSource.observeSettings()

    override suspend fun setEnabled(enabled: Boolean) = settingsDataSource.setEnabled(enabled)

    override suspend fun setMinimumLevel(level: LogLevel) =
        settingsDataSource.setMinimumLevel(level)

    override suspend fun setIncludeLocationSamples(enabled: Boolean) =
        settingsDataSource.setIncludeLocationSamples(enabled)

    override suspend fun setIgnoreActivityRecognition(enabled: Boolean) =
        settingsDataSource.setIgnoreActivityRecognition(enabled)

    override suspend fun getStorageInfo(): LogStorageInfo {
        awaitPendingWrites()
        return fileStore.storageInfo()
    }

    override suspend fun clearLogs(): Boolean {
        awaitPendingWrites()
        return runCatching { fileStore.clear() }.getOrDefault(false)
    }

    override suspend fun createArchive(): LogArchiveResult {
        awaitPendingWrites()
        return fileStore.createArchive(
            diagnosticsJson = json.encodeToString(diagnosticsSnapshot()),
        )
    }

    override suspend fun getActivityLogInfo(activityId: Long): ActivityLogInfo {
        awaitPendingWrites()
        return fileStore.activityInfo(activityId)
    }

    override suspend fun createActivityArchive(activityId: Long): LogArchiveResult {
        awaitPendingWrites()
        return fileStore.createActivityArchive(
            activityId = activityId,
            diagnosticsJson = json.encodeToString(diagnosticsSnapshot(activityId)),
        )
    }

    override suspend fun deleteActivityLogs(activityId: Long): Boolean {
        awaitPendingWrites()
        return runCatching { fileStore.deleteActivityLog(activityId) }.getOrDefault(false)
    }

    private fun process(request: LogRequest) {
        val settings = currentSettings
        if (!settings.enabled || request.level.priority < settings.minimumLevel.priority) return
        if (request.containsSensitiveLocation && !settings.includeLocationSamples) return

        val message = runCatching(request.message).getOrElse { error ->
            "Log message evaluation failed: ${error.javaClass.simpleName}"
        }
        val safeFields = request.fields.mapValues { (key, value) ->
            if (SENSITIVE_FIELD_MARKERS.any { marker -> key.contains(marker, ignoreCase = true) }) {
                REDACTED_VALUE
            } else {
                value
            }
        }
        val logcatMessage = buildString {
            append('[')
            append(request.category.name)
            append('/')
            append(request.tag)
            append("] ")
            append(message)
            if (safeFields.isNotEmpty()) append(" | $safeFields")
            request.throwable?.let { append('\n').append(it.stackTraceToString()) }
        }
        when (request.level) {
            LogLevel.VERBOSE -> Logger.v { logcatMessage }
            LogLevel.DEBUG -> Logger.d { logcatMessage }
            LogLevel.INFO -> Logger.i { logcatMessage }
            LogLevel.WARN -> Logger.w { logcatMessage }
            LogLevel.ERROR -> Logger.e { logcatMessage }
        }

        val droppedBeforeThisRecord = droppedRecords.getAndSet(0)
        val record = LogRecord(
            timestampUtc = utcTimestamp(System.currentTimeMillis()),
            uptimeMillis = SystemClock.elapsedRealtime(),
            level = request.level.name,
            category = request.category.name,
            tag = request.tag,
            message = message,
            throwable = request.throwable?.stackTraceToString(),
            thread = Thread.currentThread().name,
            sessionId = sessionId,
            activityId = request.activityId,
            fields = if (droppedBeforeThisRecord > 0) {
                safeFields + ("droppedRecords" to droppedBeforeThisRecord.toString())
            } else {
                safeFields
            },
        )
        if (queue.trySend(LogCommand.Write(record, request.level)).isFailure) {
            droppedRecords.incrementAndGet()
        }
    }

    private suspend fun awaitPendingWrites() {
        val completion = CompletableDeferred<Unit>()
        queue.send(LogCommand.Barrier(completion))
        completion.await()
    }

    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            log(
                level = LogLevel.ERROR,
                category = LogCategory.APP,
                tag = "UncaughtExceptionHandler",
                throwable = throwable,
                fields = mapOf("crashedThread" to thread.name),
            ) { "Unhandled exception" }
            // The process is about to terminate. A short bounded wait preserves the final
            // stack trace without being able to hang the crashing thread indefinitely.
            runBlocking {
                withTimeoutOrNull(CRASH_FLUSH_TIMEOUT_MILLIS) { awaitPendingWrites() }
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun diagnosticsSnapshot(activityId: Long? = null): DiagnosticsSnapshot {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        return DiagnosticsSnapshot(
            createdAtUtc = utcTimestamp(System.currentTimeMillis()),
            sessionId = sessionId,
            activityId = activityId,
            packageName = context.packageName,
            versionName = packageInfo?.versionName.orEmpty(),
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo?.longVersionCode ?: 0L
            } else {
                @Suppress("DEPRECATION")
                (packageInfo?.versionCode ?: 0).toLong()
            },
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            loggingLevel = currentSettings.minimumLevel.name,
            locationSamplesIncluded = currentSettings.includeLocationSamples,
            gitSha = BuildConfig.GIT_SHA,
            gitDirty = BuildConfig.GIT_DIRTY,
            playServicesVersion = playServicesVersion(),
        )
    }

    /**
     * Первая строка каждого файла лога. `versionName` у всех debug-сборок один, и какой
     * сборкой записан лог, раньше приходилось угадывать по датам коммитов; Play Services
     * обновляется сам и меняет поведение распознавания без единой правки у нас.
     */
    private fun fileHeaderLine(activityId: Long?): String = json.encodeToString(
        LogRecord(
            timestampUtc = utcTimestamp(System.currentTimeMillis()),
            uptimeMillis = SystemClock.elapsedRealtime(),
            level = LogLevel.INFO.name,
            category = LogCategory.APP.name,
            tag = TAG,
            message = "Log file opened",
            thread = Thread.currentThread().name,
            sessionId = sessionId,
            activityId = activityId,
            fields = buildFields,
        ),
    )

    private fun playServicesVersion(): String = runCatching {
        val info = context.packageManager.getPackageInfo(PLAY_SERVICES_PACKAGE, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName} ($code)"
    }.getOrDefault("unavailable")

    private data class LogRequest(
        val level: LogLevel,
        val category: LogCategory,
        val tag: String,
        val throwable: Throwable?,
        val fields: Map<String, String>,
        val containsSensitiveLocation: Boolean,
        val activityId: Long?,
        val message: () -> String,
    )

    private sealed interface LogCommand {
        data class Write(val record: LogRecord, val level: LogLevel) : LogCommand

        data class Barrier(val completion: CompletableDeferred<Unit>) : LogCommand
    }

    @Serializable
    private data class LogRecord(
        val timestampUtc: String,
        val uptimeMillis: Long,
        val level: String,
        val category: String,
        val tag: String,
        val message: String,
        val throwable: String? = null,
        val thread: String,
        val sessionId: String,
        val activityId: Long? = null,
        val fields: Map<String, String> = emptyMap(),
    )

    @Serializable
    private data class DiagnosticsSnapshot(
        val createdAtUtc: String,
        val sessionId: String,
        val activityId: Long? = null,
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val manufacturer: String,
        val model: String,
        val androidVersion: String,
        val sdkInt: Int,
        val loggingLevel: String,
        val locationSamplesIncluded: Boolean,
        val gitSha: String,
        val gitDirty: Boolean,
        val playServicesVersion: String,
    )

    private companion object {
        const val TAG = "DebugLogger"
        const val PLAY_SERVICES_PACKAGE = "com.google.android.gms"
        const val LOG_QUEUE_CAPACITY = 1_024
        const val BOOTSTRAP_BUFFER_CAPACITY = 100
        const val CRASH_FLUSH_TIMEOUT_MILLIS = 750L
        const val REDACTED_VALUE = "<redacted>"
        val SENSITIVE_FIELD_MARKERS = listOf(
            "authorization",
            "password",
            "token",
            "cookie",
            "secret",
        )
    }
}

private fun utcTimestamp(epochMillis: Long): String = SimpleDateFormat(
    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
    Locale.US,
).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date(epochMillis))
