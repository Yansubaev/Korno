package com.ians.korno.core.map

import android.content.Context
import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.repository.MapCacheRepository
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.storage.FileSource

/** Имя базы, в которой MapLibre держит и кэш тайлов, и офлайн-регионы. */
private const val CACHE_DATABASE_NAME = "mbgl-offline.db"

/**
 * По умолчанию MapLibre держит 50 МБ. Векторные тайлы компактные, и 128 МБ хватает
 * на несколько городов — этого достаточно, чтобы уже пройденные маршруты открывались
 * без сети, и мало, чтобы кто-то заметил расход места.
 */
private const val MAX_CACHE_SIZE_BYTES = 128L * 1024 * 1024

internal class MapLibreCacheRepository(
    private val context: Context,
) : MapCacheRepository {

    override suspend fun configure(): EmptyResult<DataError.Local> =
        withOfflineManager { manager, continuation ->
            manager.setMaximumAmbientCacheSize(MAX_CACHE_SIZE_BYTES, continuation)
        }

    override suspend fun cacheSizeBytes(): Long? = withContext(Dispatchers.IO) {
        runCatching {
            withContext(Dispatchers.Main) { MapLibre.getInstance(context) }
            val path = FileSource.getResourcesCachePath(context)
            File(path, CACHE_DATABASE_NAME).takeIf { it.isFile }?.length()
        }.getOrNull()
    }

    override suspend fun clear(): EmptyResult<DataError.Local> {
        val cleared = withOfflineManager { manager, continuation ->
            manager.clearAmbientCache(continuation)
        }
        if (cleared is Result.Error) return cleared
        // SQLite после удаления строк не отдаёт место файловой системе, поэтому размер
        // на экране не изменился бы без упаковки базы.
        return withOfflineManager { manager, continuation -> manager.packDatabase(continuation) }
    }

    /**
     * `OfflineManager` инициализируется и вызывает колбэки на главном потоке,
     * поэтому обращение к нему выполняется именно там.
     */
    private suspend fun withOfflineManager(
        action: (OfflineManager, OfflineManager.FileSourceCallback) -> Unit,
    ): EmptyResult<DataError.Local> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val callback = object : OfflineManager.FileSourceCallback {
                override fun onSuccess() {
                    if (continuation.isActive) continuation.resume(Result.Success(Unit))
                }

                override fun onError(message: String) {
                    if (continuation.isActive) continuation.resume(Result.Error(DataError.Local.UNKNOWN))
                }
            }
            runCatching {
                MapLibre.getInstance(context)
                action(OfflineManager.getInstance(context), callback)
            }.onFailure {
                if (continuation.isActive) continuation.resume(Result.Error(DataError.Local.UNKNOWN))
            }
        }
    }
}
