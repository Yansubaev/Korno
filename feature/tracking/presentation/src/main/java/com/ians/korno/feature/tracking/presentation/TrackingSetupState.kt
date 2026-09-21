package com.ians.korno.feature.tracking.presentation

import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.AutoPauseSensitivity
import com.ians.korno.core.domain.model.GpsAccuracy

internal data class TrackingSetupState(
    val segmentKind: SegmentKind = SegmentKind.RUNNING,
    /** Вид движения определяет детектор; [segmentKind] при этом не выбран. */
    val autoMode: Boolean = false,
    /** Распознавание активности доступно: разрешение выдано и Play Services на месте. */
    val autoModeAvailable: Boolean = false,
    /** Авто выбрали, но включить не вышло — объяснить, почему остался вид. */
    val autoModeUnavailableHint: Boolean = false,
    val autoPause: Boolean = true,
    val gpsAccuracy: GpsAccuracy = GpsAccuracy.BALANCED,
    val autoPauseSensitivity: AutoPauseSensitivity = AutoPauseSensitivity.MEDIUM,
    val started: Boolean = false,
)

internal val SETUP_SEGMENT_KINDS = listOf(
    SegmentKind.RUNNING,
    SegmentKind.CYCLING,
    SegmentKind.ON_FOOT,
    SegmentKind.HIKING,
    SegmentKind.VEHICLE,
)

/** От самой чуткой к самой терпимой: чаще всего нужна именно HIGH, поэтому она первая. */
internal val SETUP_AUTO_PAUSE_SENSITIVITIES = listOf(
    AutoPauseSensitivity.HIGH,
    AutoPauseSensitivity.MEDIUM,
    AutoPauseSensitivity.LOW,
)
