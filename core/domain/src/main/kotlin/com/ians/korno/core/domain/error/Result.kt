package com.ians.korno.core.domain.error

sealed interface Result<out D, out E : Error> {
    data class Success<out D>(val data: D) : Result<D, Nothing>

    data class Error<out E : com.ians.korno.core.domain.error.Error>(val error: E) : Result<Nothing, E>
}

typealias EmptyResult<E> = Result<Unit, E>

inline fun <D, E : Error, R> Result<D, E>.map(transform: (D) -> R): Result<R, E> =
    when (this) {
        is Result.Success -> Result.Success(transform(data))
        is Result.Error -> this
    }

inline fun <D, E : Error> Result<D, E>.onSuccess(action: (D) -> Unit): Result<D, E> {
    if (this is Result.Success) action(data)
    return this
}

inline fun <D, E : Error> Result<D, E>.onFailure(action: (E) -> Unit): Result<D, E> {
    if (this is Result.Error) action(error)
    return this
}

fun <D, E : Error> Result<D, E>.asEmptyResult(): EmptyResult<E> = map { Unit }
