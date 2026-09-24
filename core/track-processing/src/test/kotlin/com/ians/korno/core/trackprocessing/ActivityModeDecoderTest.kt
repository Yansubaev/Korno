package com.ians.korno.core.trackprocessing

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isBetween
import assertk.assertions.isEmpty
import assertk.assertions.isIn
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecognitionSample
import com.ians.korno.core.domain.model.RecognizedActivity
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.model.SegmentKind
import org.junit.jupiter.api.Test

class ActivityModeDecoderTest {
    private val decoder = ActivityModeDecoder()

    @Test
    fun `a vehicle verdict in the middle of a ride without stops stays cycling`() {
        // Ровно запись 31: полторы минуты уверенного IN_VEHICLE на велосипедной скорости.
        val track = Track()
            .move(seconds = 300, speed = 4.5, RecognizedActivity.ON_BICYCLE to 95)
            .move(seconds = 90, speed = 4.5, RecognizedActivity.IN_VEHICLE to 85, RecognizedActivity.ON_BICYCLE to 3)
            .move(seconds = 300, speed = 4.5, RecognizedActivity.ON_BICYCLE to 95)

        assertThat(track.decode().kinds()).containsExactly(SegmentKind.CYCLING)
    }

    @Test
    fun `a real transfer is found and its boundary snaps to the stop even when recognition lags`() {
        val track = Track()
            .move(seconds = 300, speed = 4.5, RecognizedActivity.ON_BICYCLE to 95)
        val stopStart = track.now
        track.move(seconds = 60, speed = 0.0, RecognizedActivity.STILL to 100)
        val departure = track.now
        // Машина уже поехала, а распознавание ещё минуту говорит «велосипед».
        track.move(seconds = 60, speed = 12.0, RecognizedActivity.ON_BICYCLE to 60)
            .move(seconds = 600, speed = 12.0, RecognizedActivity.IN_VEHICLE to 90)

        val segments = track.decode()

        assertThat(segments.kinds()).containsExactly(SegmentKind.CYCLING, SegmentKind.VEHICLE)
        assertThat(segments[1].startTimeEpochMillis).isBetween(stopStart, departure)
    }

    @Test
    fun `a gap in gps is enough for a transfer`() {
        val track = Track()
            .move(seconds = 300, speed = 4.5, RecognizedActivity.ON_BICYCLE to 95)
            .gap(seconds = 120)
            .move(seconds = 600, speed = 4.5, RecognizedActivity.IN_VEHICLE to 90)

        assertThat(track.decode().kinds()).containsExactly(SegmentKind.CYCLING, SegmentKind.VEHICLE)
    }

    @Test
    fun `walking and cycling switch without a stop`() {
        val track = Track()
            .move(seconds = 300, speed = 4.5, RecognizedActivity.ON_BICYCLE to 95)
            .move(seconds = 180, speed = 1.2, RecognizedActivity.WALKING to 85, RecognizedActivity.ON_FOOT to 85)
            .move(seconds = 300, speed = 4.5, RecognizedActivity.ON_BICYCLE to 95)

        assertThat(track.decode().kinds())
            .containsExactly(SegmentKind.CYCLING, SegmentKind.ON_FOOT, SegmentKind.CYCLING)
    }

    @Test
    fun `a walking verdict faster than a human walks is running`() {
        val track = Track().move(seconds = 300, speed = 4.5, RecognizedActivity.WALKING to 80)

        assertThat(track.decode().kinds()).containsExactly(SegmentKind.RUNNING)
    }

    @Test
    fun `step cadence tells running from walking under the on foot umbrella`() {
        val track = Track().move(seconds = 300, speed = 2.5, RecognizedActivity.ON_FOOT to 90, cadence = 165)

        assertThat(track.decode().kinds()).containsExactly(SegmentKind.RUNNING)
    }

    @Test
    fun `the user's kind wins inside a manual segment`() {
        val track = Track().move(seconds = 600, speed = 4.5, RecognizedActivity.ON_BICYCLE to 95)
        val manual = ActivitySegment(
            kind = SegmentKind.VEHICLE,
            startTimeEpochMillis = 300_000L,
            endTimeEpochMillis = 600_000L,
            isManuallyEdited = true,
        )

        val segments = track.decode(manualSegments = listOf(manual))

        assertThat(segments.kinds()).containsExactly(SegmentKind.CYCLING, SegmentKind.VEHICLE)
        assertThat(segments[1].isManuallyEdited).isTrue()
    }

    @Test
    fun `recognition that says it does not know moves nothing`() {
        // Своё незнание Play Services выражает ответом UNKNOWN с ровными 10/10/10, и
        // альтернативы в нём есть. Считать их наблюдением — значит голосовать против пеших
        // видов: ходьба получала половину зонтика ON_FOOT против целых десяток велосипеда и
        // транспорта, и прогулка, на которой распознавание замолчало, уезжала в велосипед.
        val track = Track()
            .move(seconds = 120, speed = 1.3, RecognizedActivity.WALKING to 90, RecognizedActivity.ON_FOOT to 90)
            .move(seconds = 900, speed = 1.3, *UNKNOWN)

        assertThat(track.decode().kinds()).containsExactly(SegmentKind.ON_FOOT)
    }

    @Test
    fun `on a stretch the recognition is silent about, vehicle speed beats the cheaper entry`() {
        // Записи 41 и 46: пешком до остановки, дальше распознавание молчит всю дорогу.
        // Велосипед сюда въезжает дешевле — пересадку он не платит, — и пределы видов не
        // спорят: 13 м/с ему разрешены, у него предел 25. Возражает только скорость, на
        // которой в городе едет транспорт, а велосипед не едет.
        val track = Track()
            .move(seconds = 300, speed = 1.3, RecognizedActivity.WALKING to 90, RecognizedActivity.ON_FOOT to 90)
            .move(seconds = 600, speed = 13.0, *UNKNOWN)

        assertThat(track.decode().kinds()).containsExactly(SegmentKind.ON_FOOT, SegmentKind.VEHICLE)
    }

    @Test
    fun `alighting counts as a stop even when doppler never settles`() {
        // Запись 41, 17:54–18:06: автобус тормозит под высадку, но доплер стоящего приёмника
        // шумит и ниже 0.5 м/с проваливается одной точкой. Раньше кредита за остановку не
        // было, выход из транспорта стоил полной пересадки, и финальная прогулка пропадала.
        val track = Track().move(seconds = 600, speed = 12.0, RecognizedActivity.IN_VEHICLE to 90)
        listOf(0.7, 0.4, 1.1, 0.6).forEach { track.move(seconds = 4, speed = it, *UNKNOWN) }
        track.move(seconds = 150, speed = 1.2, RecognizedActivity.WALKING to 90, RecognizedActivity.ON_FOOT to 90)

        assertThat(track.decode().kinds()).containsExactly(SegmentKind.VEHICLE, SegmentKind.ON_FOOT)
    }

    @Test
    fun `a stop in the same epoch does not erase the vehicle verdict before it`() {
        // Запись 46: Play Services отдаёт IN_VEHICLE и следом STILL в ту же секунду —
        // транспорт встал на светофоре. Раньше в эпохе побеждал последний ответ, и четыре
        // вердикта о транспорте из пяти пропадали вместе с автобусом. Скорость здесь ниже
        // велосипедной, так что вытянуть транспорт может только сам вердикт.
        val track = Track()
            .move(seconds = 120, speed = 1.3, RecognizedActivity.WALKING to 90, RecognizedActivity.ON_FOOT to 90)
            .move(seconds = 60, speed = 0.1, RecognizedActivity.STILL to 100)
            .move(seconds = 600, speed = 6.0, RecognizedActivity.IN_VEHICLE to 90, shadowedByStop = true)

        assertThat(track.decode().kinds()).containsExactly(SegmentKind.ON_FOOT, SegmentKind.VEHICLE)
    }

    @Test
    fun `nothing is decoded without a single piece of evidence`() {
        // Стоит, шагов нет, распознавание говорит только STILL — судить не по чему.
        val track = Track().stepSensor().move(seconds = 300, speed = 0.0, RecognizedActivity.STILL to 100)

        assertThat(track.decode()).isEmpty()
    }

    @Test
    fun `without recognition, moving at 20 kmh without steps is not on foot`() {
        // Шагомер у записи был — первое показание есть, — но на ходу он молчит.
        val track = Track().stepSensor().move(seconds = 600, speed = 5.5)

        val kinds = track.decode().kinds()
        assertThat(kinds).isNotEmpty()
        kinds.forEach { assertThat(it).isIn(SegmentKind.CYCLING, SegmentKind.VEHICLE) }
    }

    @Test
    fun `without recognition, 5 kmh at a walking cadence is on foot`() {
        val track = Track().move(seconds = 600, speed = 1.4, cadence = 100)

        assertThat(track.decode().kinds()).containsExactly(SegmentKind.ON_FOOT)
    }

    @Test
    fun `without recognition, 35 kmh without steps is a vehicle`() {
        val track = Track().stepSensor().move(seconds = 600, speed = 9.7)

        assertThat(track.decode().kinds()).containsExactly(SegmentKind.VEHICLE)
    }

    @Test
    fun `a recording without a single step reading gets no step-free evidence`() {
        // Нет показаний — нет шагомера, а не ноль шагов. Иначе отсутствие датчика читалось
        // бы как «едет». Пешие виды на 2.5 м/с ничто не запрещает.
        val track = Track().move(seconds = 600, speed = 2.5)

        val withoutSensor = track.decode().kinds()
        assertThat(withoutSensor).isNotEmpty()
        withoutSensor.forEach { assertThat(it).isIn(SegmentKind.ON_FOOT, SegmentKind.RUNNING) }
        // С шагомером, который молчит на той же скорости, пешком уже не идут.
        val silent = Track().stepSensor().move(seconds = 600, speed = 2.5).decode().kinds()
        assertThat(silent).isNotEmpty()
        silent.forEach { assertThat(it).isIn(SegmentKind.CYCLING, SegmentKind.VEHICLE) }
    }

    private fun List<ActivitySegment>.kinds() = map(ActivitySegment::kind)

    private companion object {
        /** Ровно то, чем Play Services отвечает «не знаю»: вердикт UNKNOWN и ничья под ним. */
        val UNKNOWN = arrayOf(
            RecognizedActivity.UNKNOWN to 41,
            RecognizedActivity.IN_VEHICLE to 10,
            RecognizedActivity.ON_BICYCLE to 10,
            RecognizedActivity.ON_FOOT to 10,
        )
    }

    /** Запись по участкам: точка каждые 4 с, ответ распознавания каждые 10 с. */
    private inner class Track {
        var now = 0L
            private set
        private val points = mutableListOf<GeoPoint>()
        private val recognitions = mutableListOf<RecognitionSample>()
        private val steps = mutableListOf<DetectorSignal.StepCounter>()
        private var latitude = 55.0
        private var stepCount = 0.0

        fun move(
            seconds: Int,
            speed: Double,
            vararg confidences: Pair<RecognizedActivity, Int>,
            cadence: Int? = null,
            /** За каждым вердиктом в ту же секунду приходит `STILL` — как на остановке. */
            shadowedByStop: Boolean = false,
        ): Track {
            val end = now + seconds * 1_000L
            var nextRecognition = now
            while (now < end) {
                if (now >= nextRecognition) {
                    recognitions += RecognitionSample(timestampEpochMillis = now, confidences = confidences.toMap())
                    if (shadowedByStop) {
                        recognitions += RecognitionSample(
                            timestampEpochMillis = now,
                            confidences = mapOf(RecognizedActivity.STILL to 100),
                        )
                    }
                    nextRecognition += 10_000L
                }
                points += GeoPoint(
                    activityId = 1L,
                    latitude = latitude,
                    longitude = 37.0,
                    timestampEpochMillis = now,
                    speedMetersPerSecond = speed.toFloat(),
                    recordingStatus = RecordingStatus.RECORDING,
                )
                if (cadence != null) {
                    steps += DetectorSignal.StepCounter(now, stepCount.toLong())
                    stepCount += cadence * 4 / 60.0
                }
                latitude += speed * 4 / 111_000.0
                now += 4_000L
            }
            now = end
            return this
        }

        /** Шагомер у записи есть: первое показание, как его пишет координатор, — сразу. */
        fun stepSensor(): Track {
            steps += DetectorSignal.StepCounter(now, stepCount.toLong())
            return this
        }

        fun gap(seconds: Int): Track {
            now += seconds * 1_000L
            return this
        }

        fun decode(manualSegments: List<ActivitySegment> = emptyList()) = decoder.decode(
            ModeDecoderInput(
                startMillis = 0L,
                endMillis = now,
                points = points,
                recognitions = recognitions,
                steps = steps,
                manualSegments = manualSegments,
            ),
        )
    }
}
