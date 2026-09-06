package com.ians.korno.core.data.local

import android.database.sqlite.SQLiteFullException
import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

internal suspend inline fun <T> safeLocalCall(
    crossinline action: suspend () -> T,
): Result<T, DataError.Local> =
    try {
        Result.Success(action())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.Error(error.toLocalError())
    }

internal fun <T> Flow<T>.asLocalResult(): Flow<Result<T, DataError.Local>> =
    map<T, Result<T, DataError.Local>> { Result.Success(it) }
        .catch { error ->
            if (error is CancellationException) throw error
            emit(Result.Error(error.toLocalError()))
        }

internal fun Throwable.toLocalError(): DataError.Local =
    when (this) {
        is SQLiteFullException -> DataError.Local.DISK_FULL
        else -> DataError.Local.UNKNOWN
    }
