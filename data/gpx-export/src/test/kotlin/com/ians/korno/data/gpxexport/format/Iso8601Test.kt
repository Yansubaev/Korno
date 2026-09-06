package com.ians.korno.data.gpxexport.format

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class Iso8601Test {

    @Test
    fun `formats an instant as utc without fractions when there are none`() {
        assertThat(Iso8601.format(1_757_052_000_000L)).isEqualTo("2025-09-05T06:00:00Z")
    }

    @Test
    fun `formats fractional seconds when present`() {
        assertThat(Iso8601.format(1_757_052_000_500L)).isEqualTo("2025-09-05T06:00:00.500Z")
    }

    @Test
    fun `formats instants before the epoch`() {
        assertThat(Iso8601.format(-1L)).isEqualTo("1969-12-31T23:59:59.999Z")
    }

    @Test
    fun `formats a leap day`() {
        assertThat(Iso8601.format(1_709_164_800_000L)).isEqualTo("2024-02-29T00:00:00Z")
    }

    @Test
    fun `parse is the inverse of format`() {
        listOf(0L, 1L, -1L, 1_757_052_000_500L, 1_709_164_800_000L, 4_102_444_800_000L)
            .forEach { epochMillis ->
                assertThat(Iso8601.parse(Iso8601.format(epochMillis))).isEqualTo(epochMillis)
            }
    }

    @Test
    fun `parses an offset by shifting to utc`() {
        assertThat(Iso8601.parse("2025-09-05T09:00:00+03:00")).isEqualTo(1_757_052_000_000L)
        assertThat(Iso8601.parse("2025-09-05T01:00:00-05:00")).isEqualTo(1_757_052_000_000L)
    }

    @Test
    fun `treats a missing offset as utc`() {
        assertThat(Iso8601.parse("2025-09-05T06:00:00")).isEqualTo(1_757_052_000_000L)
    }

    @Test
    fun `keeps only millisecond precision from longer fractions`() {
        assertThat(Iso8601.parse("2025-09-05T06:00:00.123456Z")).isEqualTo(1_757_052_000_123L)
        assertThat(Iso8601.parse("2025-09-05T06:00:00.5Z")).isEqualTo(1_757_052_000_500L)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "not a time",
            "2025-09-05",
            "2025-09-05T06:00",
            "2025-13-05T06:00:00Z",
            "2025-09-05T25:00:00Z",
            "2025-09-05T06:00:00ZZ",
            "2025-09-05T06:00:00+3",
            "2025-09-05T06:00:00.Z",
            "2026/09/05T06:00:00Z",
        ],
    )
    fun `rejects values that are not iso timestamps`(value: String) {
        assertThat(Iso8601.parse(value)).isNull()
    }
}
