package com.ians.korno.feature.home.domain.usecase

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.repository.TrackRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ObserveHomeSummaryUseCaseTest {

    @Test
    fun `an ongoing recording is neither a recent row nor part of the week sums`() = runTest {
        val now = System.currentTimeMillis()
        val saved = Activity(id = 1L, startTimeEpochMillis = now - 2_000, endTimeEpochMillis = now - 1_000, distanceMeters = 5_000.0)
        val ongoing = Activity(id = 2L, startTimeEpochMillis = now, endTimeEpochMillis = null, distanceMeters = 300.0)
        val repository = Proxy.newProxyInstance(
            TrackRepository::class.java.classLoader,
            arrayOf(TrackRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "observeActivities" -> flowOf(Result.Success(listOf(ongoing, saved)))
                "observeAllSegments" -> flowOf(Result.Success(emptyList<Any>()))
                else -> error("${method.name} is not expected here")
            }
        } as TrackRepository

        ObserveHomeSummaryUseCase(repository)().test {
            val summary = (awaitItem() as Result.Success).data
            assertThat(summary.recentActivities.map { it.id }).containsExactly(1L)
            assertThat(summary.weekDistanceMeters).isEqualTo(5_000.0)
            awaitComplete()
        }
    }
}
