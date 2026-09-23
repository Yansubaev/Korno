package com.ians.korno.feature.tracking.domain.usecase

import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.feature.tracking.domain.repository.TrackingController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformLatest

/** Живые цифры идущей записи для карточки на главной и в списке. */
data class OngoingRecording(
    val segmentKind: SegmentKind,
    val status: RecordingStatus,
    val elapsedMillis: Long,
    val distanceMeters: Double,
)

private const val TICK_MILLIS = 1_000L

/**
 * `null` — записи нет. Цифры берутся из сессии, а не из БД, куда они попадают с отставанием;
 * пока запись идёт, секундомер тикает раз в секунду, без записи не тикает ничего.
 */
class ObserveOngoingRecordingUseCase(
    private val trackingController: TrackingController,
) {
    operator fun invoke(): Flow<OngoingRecording?> =
        trackingController.observeSession().transformLatest { session ->
            if (!session.isActive) {
                emit(null)
                return@transformLatest
            }
            while (true) {
                emit(
                    OngoingRecording(
                        segmentKind = session.segmentKind,
                        status = session.status,
                        elapsedMillis = session.elapsedMillisAt(System.currentTimeMillis()),
                        distanceMeters = session.distanceMeters,
                    ),
                )
                delay(TICK_MILLIS)
            }
        }
}
