package com.ians.korno.core.logging

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Логи лежат в двух измерениях. Общий поток сессии приложения ротируется по объёму
 * ([maxFileBytes] × [maxFileCount]), лог активности живёт своим файлом и переживает и
 * ротацию, и перезапуск приложения.
 *
 * Подробности записи (`DEBUG` и ниже — по строке на каждый сэмпл) идут только в лог
 * активности: в общем потоке они занимали 95–97 % объёма и прокручивали его за пару часов,
 * вытесняя редкие события самого приложения. В общий поток из записи попадает `INFO` и
 * выше — старт, стоп, смены состояния, системные сигналы, ошибки.
 *
 * Каждый новый файл начинается строкой [fileHeader]: чем записан файл (сборка, версия
 * Play Services). `diagnostics.json` описывает момент выгрузки, а не записи.
 */
internal class LogFileStore(
    private val logsDirectory: File,
    private val shareDirectory: File,
    private val sessionId: String,
    private val uriForFile: (File) -> String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val maxFileCount: Int = DEFAULT_MAX_FILE_COUNT,
    private val maxActivityFileBytes: Long = DEFAULT_MAX_ACTIVITY_FILE_BYTES,
    private val maxActivityFileCount: Int = DEFAULT_MAX_ACTIVITY_FILE_COUNT,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** Первая строка каждого нового файла; `activityId` — для файла активности. */
    private val fileHeader: (activityId: Long?) -> String? = { null },
) {
    private val mutex = Mutex()
    private val activitiesDirectory = File(logsDirectory, ACTIVITIES_DIRECTORY_NAME)

    private var currentFile: File? = null
    private var writer: BufferedWriter? = null
    private var fileIndex = 0

    private var activityFile: File? = null
    private var activityWriter: BufferedWriter? = null
    private var openActivityId: Long? = null
    private var activityTruncated = false

    suspend fun append(
        jsonLine: String,
        activityId: Long? = null,
        level: LogLevel = LogLevel.INFO,
    ) = withContext(ioDispatcher) {
        mutex.withLock {
            if (activityId == null || level.priority >= LogLevel.INFO.priority) {
                appendToSessionLogLocked(jsonLine)
            }
            if (activityId != null) appendToActivityLogLocked(activityId, jsonLine)
        }
    }

    suspend fun storageInfo(): LogStorageInfo = withContext(ioDispatcher) {
        mutex.withLock {
            flushWritersLocked()
            val files = logFilesLocked() + activityFilesLocked()
            LogStorageInfo(
                sizeBytes = files.sumOf(File::length),
                fileCount = files.size,
                sessionId = sessionId,
            )
        }
    }

    suspend fun activityInfo(activityId: Long): ActivityLogInfo = withContext(ioDispatcher) {
        mutex.withLock {
            if (openActivityId == activityId) runCatching { activityWriter?.flush() }
            val file = activityFileFor(activityId)
            if (!file.isFile) return@withLock ActivityLogInfo(activityId = activityId)
            val lines = runCatching { file.useLines { lines -> lines.count() } }.getOrDefault(0)
            ActivityLogInfo(
                activityId = activityId,
                sizeBytes = file.length(),
                recordCount = lines,
                isTruncated = openActivityId == activityId && activityTruncated,
            )
        }
    }

    suspend fun clear(): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            closeWriterLocked()
            closeActivityWriterLocked()
            currentFile = null
            fileIndex = 0
            val sessionCleared = logFilesLocked().all(File::delete)
            val activitiesCleared = activityFilesLocked().all(File::delete)
            sessionCleared && activitiesCleared
        }
    }

    suspend fun deleteActivityLog(activityId: Long): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            if (openActivityId == activityId) closeActivityWriterLocked()
            val file = activityFileFor(activityId)
            !file.exists() || file.delete()
        }
    }

    suspend fun createArchive(diagnosticsJson: String): LogArchiveResult =
        withContext(ioDispatcher) {
            mutex.withLock {
                flushWritersLocked()
                val logFiles = logFilesLocked()
                if (logFiles.isEmpty()) return@withLock LogArchiveResult.NoLogs
                writeArchiveLocked(
                    archiveName = "korno-diagnostics-$sessionId.zip",
                    diagnosticsJson = diagnosticsJson,
                    entries = logFiles.associateBy { file -> "logs/${file.name}" },
                )
            }
        }

    suspend fun createActivityArchive(
        activityId: Long,
        diagnosticsJson: String,
    ): LogArchiveResult = withContext(ioDispatcher) {
        mutex.withLock {
            if (openActivityId == activityId) runCatching { activityWriter?.flush() }
            val file = activityFileFor(activityId)
            if (!file.isFile || file.length() == 0L) return@withLock LogArchiveResult.NoLogs
            flushWritersLocked()
            writeArchiveLocked(
                archiveName = "korno-activity-$activityId-$sessionId.zip",
                diagnosticsJson = diagnosticsJson,
                entries = mapOf("logs/${file.name}" to file),
                textEntries = appContextLocked(file)
                    ?.let { context -> mapOf(APP_CONTEXT_ENTRY to context) }
                    .orEmpty(),
            )
        }
    }

    /**
     * Записи приложения вне активности вокруг неё: запуск процесса и причина смерти
     * прошлого, прогрев GPS до появления `activityId`, снятие зависшей подписки, итог
     * отписки после стопа. В сам лог активности они не попадают — активности ещё или уже
     * нет, — а без них не видно, в каком окружении запись началась.
     *
     * Строки общего потока с `activityId` пропускаются: свои уже лежат в логе активности,
     * чужие к ней не относятся. Время сравнивается строками — формат у всех записей один.
     */
    private fun appContextLocked(activityFile: File): String? {
        val activityTimestamps = activityFile.useLines { lines ->
            lines.mapNotNull(::timestampOf).toList()
        }
        val from = activityTimestamps.minOrNull()?.shiftedBy(-APP_CONTEXT_BEFORE_MILLIS) ?: return null
        val to = activityTimestamps.max().shiftedBy(APP_CONTEXT_AFTER_MILLIS) ?: return null
        val context = logFilesLocked()
            .flatMap { file -> file.readLines() }
            .filterNot { line -> ACTIVITY_ID_MARKER in line }
            .mapNotNull { line -> timestampOf(line)?.let { timestamp -> timestamp to line } }
            .filter { (timestamp, _) -> timestamp in from..to }
            .sortedBy { (timestamp, _) -> timestamp }
        if (context.isEmpty()) return null
        return context.joinToString(separator = "\n", postfix = "\n") { (_, line) -> line }
    }

    private fun writeArchiveLocked(
        archiveName: String,
        diagnosticsJson: String,
        entries: Map<String, File>,
        textEntries: Map<String, String> = emptyMap(),
    ): LogArchiveResult = try {
        shareDirectory.mkdirs()
        clearStaleArchivesLocked()
        val archive = File(shareDirectory, archiveName)
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("diagnostics.json"))
            zip.write(diagnosticsJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            entries.forEach { (entryName, file) ->
                zip.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
            textEntries.forEach { (entryName, text) ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        LogArchiveResult.Success(
            SharedLogArchive(
                uri = uriForFile(archive),
                fileName = archive.name,
            ),
        )
    } catch (_: Exception) {
        LogArchiveResult.Error
    }

    private fun appendToSessionLogLocked(jsonLine: String) {
        logsDirectory.mkdirs()
        var file = ensureWriterLocked()
        val bytesToWrite = jsonLine.byteLength()
        if (file.length() > 0L && file.length() + bytesToWrite > maxFileBytes) {
            closeWriterLocked()
            currentFile = null
            fileIndex += 1
            file = ensureWriterLocked()
        }
        writer?.writeLine(jsonLine)
        pruneLogsLocked()
    }

    /**
     * Лог активности не ротируется: при переполнении запись останавливается, а в файле
     * остаётся маркер. Начало тренировки (настройки, первые фиксы, разгон GPS) объясняет
     * больше, чем её хвост, и терять его ради последних минут смысла нет.
     */
    private fun appendToActivityLogLocked(activityId: Long, jsonLine: String) {
        if (openActivityId != activityId) {
            closeActivityWriterLocked()
            activitiesDirectory.mkdirs()
            val file = activityFileFor(activityId)
            openActivityId = activityId
            activityFile = file
            activityTruncated = false
            activityWriter = file.appendingWriter()
            fileHeader(activityId)?.let { header -> activityWriter?.writeLine(header) }
        }
        if (activityTruncated) return
        val file = activityFile ?: return
        if (file.length() + jsonLine.byteLength() > maxActivityFileBytes) {
            activityWriter?.writeLine(TRUNCATION_RECORD)
            activityTruncated = true
            return
        }
        activityWriter?.writeLine(jsonLine)
        pruneActivityLogsLocked()
    }

    private fun ensureWriterLocked(): File {
        currentFile?.let { return it }
        val file = File(logsDirectory, "korno-$sessionId-$fileIndex.jsonl")
        currentFile = file
        writer = file.appendingWriter()
        fileHeader(null)?.let { header -> writer?.writeLine(header) }
        return file
    }

    private fun flushWritersLocked() {
        runCatching { writer?.flush() }
        runCatching { activityWriter?.flush() }
    }

    private fun closeWriterLocked() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
    }

    private fun closeActivityWriterLocked() {
        runCatching { activityWriter?.flush() }
        runCatching { activityWriter?.close() }
        activityWriter = null
        activityFile = null
        openActivityId = null
        activityTruncated = false
    }

    private fun activityFileFor(activityId: Long) =
        File(activitiesDirectory, "activity-$activityId.jsonl")

    private fun logFilesLocked(): List<File> = logsDirectory.listFiles()
        ?.filter { it.isFile && it.extension == "jsonl" }
        ?.sortedByDescending(File::lastModified)
        .orEmpty()

    private fun activityFilesLocked(): List<File> = activitiesDirectory.listFiles()
        ?.filter { it.isFile && it.extension == "jsonl" }
        ?.sortedByDescending(File::lastModified)
        .orEmpty()

    private fun pruneLogsLocked() {
        val expiry = nowMillis() - LOG_RETENTION_MILLIS
        logFilesLocked().forEachIndexed { index, file ->
            val expired = file.lastModified() < expiry
            val aboveLimit = index >= maxFileCount
            if (file != currentFile && (expired || aboveLimit)) file.delete()
        }
    }

    private fun pruneActivityLogsLocked() {
        val expiry = nowMillis() - LOG_RETENTION_MILLIS
        activityFilesLocked().forEachIndexed { index, file ->
            val expired = file.lastModified() < expiry
            val aboveLimit = index >= maxActivityFileCount
            if (file != activityFile && (expired || aboveLimit)) file.delete()
        }
    }

    private fun clearStaleArchivesLocked() {
        val expiry = nowMillis() - ARCHIVE_RETENTION_MILLIS
        shareDirectory.listFiles()?.forEach { file ->
            if (file.lastModified() < expiry) file.delete()
        }
    }

    private fun File.appendingWriter() =
        BufferedWriter(OutputStreamWriter(FileOutputStream(this, true), Charsets.UTF_8))

    private fun BufferedWriter.writeLine(line: String) {
        write(line)
        newLine()
        // Debug logs favour crash resilience over throughput. All disk work is already
        // serialized on Dispatchers.IO, so flushing here never blocks the caller.
        flush()
    }

    private fun timestampOf(line: String): String? =
        TIMESTAMP_PATTERN.find(line)?.groupValues?.get(1)

    private fun String.shiftedBy(millis: Long): String? = runCatching {
        val format = timestampFormat()
        format.format(format.parse(this)!!.time + millis)
    }.getOrNull()

    private fun timestampFormat() = SimpleDateFormat(TIMESTAMP_FORMAT, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun String.byteLength(): Long = toByteArray(Charsets.UTF_8).size + 1L

    private companion object {
        const val DEFAULT_MAX_FILE_BYTES = 2L * 1024L * 1024L
        const val DEFAULT_MAX_FILE_COUNT = 5
        const val DEFAULT_MAX_ACTIVITY_FILE_BYTES = 8L * 1024L * 1024L
        const val DEFAULT_MAX_ACTIVITY_FILE_COUNT = 20
        const val LOG_RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        const val ARCHIVE_RETENTION_MILLIS = 24L * 60L * 60L * 1_000L
        const val ACTIVITIES_DIRECTORY_NAME = "activities"
        const val APP_CONTEXT_ENTRY = "context/app.jsonl"
        const val APP_CONTEXT_BEFORE_MILLIS = 10L * 60L * 1_000L
        const val APP_CONTEXT_AFTER_MILLIS = 60L * 1_000L
        const val ACTIVITY_ID_MARKER = "\"activityId\":"
        const val TIMESTAMP_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        val TIMESTAMP_PATTERN = Regex("\"timestampUtc\":\"([^\"]+)\"")

        /** Валидная JSONL-строка: файл должен остаться читаемым для `jq` и после обрыва. */
        const val TRUNCATION_RECORD =
            """{"level":"WARN","category":"APP","tag":"LogFileStore",""" +
                """"message":"Activity log truncated: size limit reached"}"""
    }
}
