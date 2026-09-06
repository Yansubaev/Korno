package com.ians.korno.core.data.preferences

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.ians.korno.core.domain.model.MeasurementSystem
import org.junit.jupiter.api.Test

class UserPreferencesLocaleTest {

    @Test
    fun `US locale defaults to imperial units`() {
        assertThat(defaultMeasurementSystemForCountry("us"))
            .isEqualTo(MeasurementSystem.IMPERIAL)
    }

    @Test
    fun `Kazakhstan locale defaults to metric units`() {
        assertThat(defaultMeasurementSystemForCountry("KZ"))
            .isEqualTo(MeasurementSystem.METRIC)
    }

    @Test
    fun `missing country defaults to metric units`() {
        assertThat(defaultMeasurementSystemForCountry(null))
            .isEqualTo(MeasurementSystem.METRIC)
    }
}
