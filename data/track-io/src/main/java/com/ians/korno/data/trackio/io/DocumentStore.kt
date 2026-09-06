package com.ians.korno.data.trackio.io

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.error.TrackFileError
import com.ians.korno.core.domain.model.SharedTrackFile
import com.ians.korno.core.domain.model.TrackFileFormat
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Работа с документами Storage Access Framework.
 *
 * Файлы приходят и уходят через выбор пользователя, поэтому здесь нет ни путей, ни
 * разрешений на внешнюю память: приложение видит ровно тот документ, который ему
 * отдали, и ничего вокруг него.
 */
internal class DocumentStore(private val context: Context) {

    private val contentResolver: ContentResolver get() = context.contentResolver

    fun readText(uri: String): Result<String, TrackFileError> = try {
        val text = contentResolver.openInputStream(uri.toUri())?.use { stream ->
            stream.bufferedReader().readText()
        }
        // BOM в начале файла ломает поиск корневого элемента, а встречается он часто —
        // Windows-редакторы дописывают его молча.
        text?.removePrefix(BYTE_ORDER_MARK)
            ?.let { Result.Success(it) }
            ?: Result.Error(TrackFileError.UNREADABLE_SOURCE)
    } catch (error: FileNotFoundException) {
        Result.Error(TrackFileError.NOT_FOUND)
    } catch (error: SecurityException) {
        Result.Error(TrackFileError.UNREADABLE_SOURCE)
    } catch (error: IOException) {
        Result.Error(TrackFileError.UNREADABLE_SOURCE)
    }

    fun writeText(uri: String, content: String): EmptyResult<TrackFileError> = try {
        // Режим "wt" усекает существующий файл: без него перезапись более коротким
        // треком оставила бы хвост старого — файл открылся бы, но с лишними точками.
        val stream = contentResolver.openOutputStream(uri.toUri(), "wt")
            ?: contentResolver.openOutputStream(uri.toUri())
        if (stream == null) {
            Result.Error(TrackFileError.UNWRITABLE_DESTINATION)
        } else {
            stream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            Result.Success(Unit)
        }
    } catch (error: FileNotFoundException) {
        Result.Error(TrackFileError.NOT_FOUND)
    } catch (error: SecurityException) {
        Result.Error(TrackFileError.UNWRITABLE_DESTINATION)
    } catch (error: IOException) {
        Result.Error(TrackFileError.UNWRITABLE_DESTINATION)
    }

    /** Создаёт документ в выбранной пользователем папке и возвращает ссылку на него. */
    fun createInTree(
        treeUri: String,
        fileName: String,
        mimeType: String,
    ): Result<String, TrackFileError> = try {
        val tree = treeUri.toUri()
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        DocumentsContract.createDocument(contentResolver, parent, mimeType, fileName)
            ?.let { Result.Success(it.toString()) }
            ?: Result.Error(TrackFileError.UNWRITABLE_DESTINATION)
    } catch (error: FileNotFoundException) {
        Result.Error(TrackFileError.NOT_FOUND)
    } catch (error: SecurityException) {
        Result.Error(TrackFileError.UNWRITABLE_DESTINATION)
    } catch (error: IllegalArgumentException) {
        Result.Error(TrackFileError.UNWRITABLE_DESTINATION)
    }

    fun displayName(uri: String): String? = try {
        contentResolver.query(uri.toUri(), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
    } catch (error: SecurityException) {
        null
    } catch (error: IllegalArgumentException) {
        null
    }

    /**
     * Разрешение на документ живёт до перезагрузки, а операция уходит в WorkManager и
     * может пережить процесс, поэтому доступ закрепляется, если провайдер это позволяет.
     */
    fun persistPermission(uri: String, writable: Boolean) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            if (writable) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        runCatching { contentResolver.takePersistableUriPermission(uri.toUri(), flags) }
    }

    /** Кладёт файл во внутренний кэш и отдаёт ссылку FileProvider для отправки наружу. */
    fun writeToShareCache(
        fileName: String,
        content: String,
        format: TrackFileFormat,
    ): Result<SharedTrackFile, TrackFileError> = try {
        val directory = File(context.cacheDir, SHARE_DIRECTORY).apply { mkdirs() }
        clearStaleShareFiles(directory)
        val file = File(directory, fileName)
        file.writeText(content)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.trackfiles", file)
        Result.Success(
            SharedTrackFile(
                uri = uri.toString(),
                fileName = fileName,
                mimeType = format.mimeType,
            ),
        )
    } catch (error: IOException) {
        Result.Error(TrackFileError.UNWRITABLE_DESTINATION)
    } catch (error: IllegalArgumentException) {
        Result.Error(TrackFileError.UNWRITABLE_DESTINATION)
    }

    /**
     * Отданный наружу файл нельзя удалить сразу — принимающее приложение читает его
     * позже, — поэтому кэш чистится по возрасту при следующей отправке.
     */
    private fun clearStaleShareFiles(directory: File) {
        val threshold = System.currentTimeMillis() - SHARE_FILE_LIFETIME_MILLIS
        directory.listFiles()?.forEach { file ->
            if (file.lastModified() < threshold) file.delete()
        }
    }

    private fun String.toUri(): Uri = Uri.parse(this)

    private companion object {
        const val SHARE_DIRECTORY = "track-files"
        const val SHARE_FILE_LIFETIME_MILLIS = 24L * 60 * 60 * 1_000
        const val BYTE_ORDER_MARK = "\uFEFF"
    }
}
