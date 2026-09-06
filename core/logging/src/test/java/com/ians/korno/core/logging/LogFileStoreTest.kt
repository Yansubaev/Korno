package com.ians.korno.core.logging

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class LogFileStoreTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `log files rotate and keep only the configured count`() = runTest {
        val store = createStore(maxFileBytes = 20L, maxFileCount = 2)

        store.append("first-record-is-long")
        store.append("second-record-is-long")
        store.append("third-record-is-long")

        val info = store.storageInfo()
        assertThat(info.fileCount).isEqualTo(2)
        assertThat(info.sizeBytes).isGreaterThan(0L)
        assertThat(info.sessionId).isEqualTo(SESSION_ID)
    }

    @Test
    fun `archive contains diagnostics and log files`() = runTest {
        val store = createStore()
        store.append("{\"message\":\"hello\"}")

        val result = store.createArchive("{\"sessionId\":\"$SESSION_ID\"}")

        val archive = (result as LogArchiveResult.Success).archive
        ZipFile(archive.uri).use { zip ->
            val entryNames = zip.entries().asSequence().map { it.name }.toList()
            assertThat(entryNames).contains("diagnostics.json")
            assertThat(entryNames).contains("logs/korno-$SESSION_ID-0.jsonl")
        }
    }

    @Test
    fun `clear removes every log file`() = runTest {
        val store = createStore()
        store.append("record")
        store.append("activity-record", activityId = 7L)

        assertThat(store.clear()).isEqualTo(true)
        assertThat(store.storageInfo().fileCount).isEqualTo(0)
        assertThat(store.activityInfo(7L).recordCount).isEqualTo(0)
    }

    @Test
    fun `records are written to both the session log and the activity log`() = runTest {
        val store = createStore()

        store.append("{\"message\":\"shared\"}", activityId = 7L)
        store.append("{\"message\":\"session only\"}")

        assertThat(store.activityInfo(7L).recordCount).isEqualTo(1)
        // Общий лог + файл активности: обе копии считаются в общем объёме.
        assertThat(store.storageInfo().fileCount).isEqualTo(2)
    }

    @Test
    fun `activity logs are kept apart from each other`() = runTest {
        val store = createStore()

        store.append("first", activityId = 1L)
        store.append("second", activityId = 2L)
        store.append("third", activityId = 2L)

        assertThat(store.activityInfo(1L).recordCount).isEqualTo(1)
        assertThat(store.activityInfo(2L).recordCount).isEqualTo(2)
        assertThat(store.activityInfo(3L).recordCount).isEqualTo(0)
    }

    @Test
    fun `activity archive carries only that activity's log`() = runTest {
        val store = createStore()
        store.append("{\"message\":\"other\"}", activityId = 1L)
        store.append("{\"message\":\"wanted\"}", activityId = 2L)

        val result = store.createActivityArchive(2L, "{\"activityId\":2}")

        val archive = (result as LogArchiveResult.Success).archive
        ZipFile(archive.uri).use { zip ->
            val entryNames = zip.entries().asSequence().map { it.name }.toList()
            assertThat(entryNames).contains("diagnostics.json")
            assertThat(entryNames).contains("logs/activity-2.jsonl")
            assertThat(entryNames).doesNotContain("logs/activity-1.jsonl")
        }
    }

    @Test
    fun `activity archive without records reports no logs`() = runTest {
        val store = createStore()
        store.append("session only")

        assertThat(store.createActivityArchive(1L, "{}")).isEqualTo(LogArchiveResult.NoLogs)
    }

    @Test
    fun `activity log stops growing past its size limit and is marked truncated`() = runTest {
        val store = createStore(maxActivityFileBytes = 30L)

        store.append("first-record-is-long", activityId = 1L)
        store.append("second-record-is-long", activityId = 1L)
        store.append("third-record-is-long", activityId = 1L)

        val info = store.activityInfo(1L)
        // Первая запись, маркер обрыва — и ничего после него.
        assertThat(info.recordCount).isEqualTo(2)
        assertThat(info.isTruncated).isEqualTo(true)
    }

    @Test
    fun `deleting an activity log leaves the session log alone`() = runTest {
        val store = createStore()
        store.append("record", activityId = 1L)

        assertThat(store.deleteActivityLog(1L)).isEqualTo(true)
        assertThat(store.activityInfo(1L).recordCount).isEqualTo(0)
        assertThat(store.storageInfo().fileCount).isEqualTo(1)
    }

    @Test
    fun `activity logs above the retained count are pruned`() = runTest {
        val store = createStore(maxActivityFileCount = 2)

        store.append("first", activityId = 1L)
        store.append("second", activityId = 2L)
        store.append("third", activityId = 3L)

        assertThat(store.activityInfo(1L).recordCount).isEqualTo(0)
        assertThat(store.activityInfo(3L).recordCount).isEqualTo(1)
    }

    private fun createStore(
        maxFileBytes: Long = 2L * 1024L * 1024L,
        maxFileCount: Int = 5,
        maxActivityFileBytes: Long = 8L * 1024L * 1024L,
        maxActivityFileCount: Int = 20,
    ) = LogFileStore(
        logsDirectory = temporaryDirectory.resolve("logs").toFile(),
        shareDirectory = temporaryDirectory.resolve("share").toFile(),
        sessionId = SESSION_ID,
        uriForFile = File::getAbsolutePath,
        ioDispatcher = UnconfinedTestDispatcher(),
        maxFileBytes = maxFileBytes,
        maxFileCount = maxFileCount,
        maxActivityFileBytes = maxActivityFileBytes,
        maxActivityFileCount = maxActivityFileCount,
    )

    private companion object {
        const val SESSION_ID = "session1"
    }
}
