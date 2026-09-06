package com.ians.korno.core.domain.error

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class ResultTest {
    @Test
    fun `map transforms success data`() {
        val result = Result.Success(21).map { it * 2 }

        assertThat(result).isEqualTo(Result.Success(42))
    }

    @Test
    fun `map preserves failure`() {
        val result = Result.Error(DataError.Local.NOT_FOUND).map { value: Int -> value * 2 }

        assertThat(result).isEqualTo(Result.Error(DataError.Local.NOT_FOUND))
    }

    @Test
    fun `callbacks run only for matching result`() {
        var successValue = 0
        var failureValue: DataError.Local? = null

        Result.Success(7)
            .onSuccess { successValue = it }
            .onFailure { failureValue = it }

        assertThat(successValue).isEqualTo(7)
        assertThat(failureValue).isEqualTo(null)
    }
}
