package com.ians.korno.feature.tracking.data.service

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.ians.korno.core.domain.model.RecordingNotificationStyle
import org.junit.jupiter.api.Test

class RecordingNotificationStyleTest {

    @Test
    fun `auto follows whether the system allows promotion`() {
        assertThat(usesLiveUpdate(RecordingNotificationStyle.AUTO, canPromote = true)).isTrue()
        assertThat(usesLiveUpdate(RecordingNotificationStyle.AUTO, canPromote = false)).isFalse()
    }

    @Test
    fun `forced style ignores promotion availability`() {
        listOf(true, false).forEach { canPromote ->
            assertThat(usesLiveUpdate(RecordingNotificationStyle.LIVE_UPDATE, canPromote)).isTrue()
            assertThat(usesLiveUpdate(RecordingNotificationStyle.CUSTOM, canPromote)).isFalse()
        }
    }
}
