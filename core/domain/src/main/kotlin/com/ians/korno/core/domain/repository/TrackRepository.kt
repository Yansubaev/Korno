package com.ians.korno.core.domain.repository

import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecognitionSample
import com.ians.korno.core.domain.model.RoutePoint
import com.ians.korno.core.domain.model.StepSample
import kotlinx.coroutines.flow.Flow

interface TrackRepository {
    fun observeActivities(): Flow<Result<List<Activity>, DataError.Local>>

    fun observeActivity(activityId: Long): Flow<Result<Activity, DataError.Local>>

    fun observeGeoPoints(activityId: Long): Flow<Result<List<GeoPoint>, DataError.Local>>

    suspend fun findActiveActivity(): Result<Activity?, DataError.Local>

    /**
     * Упрощённый маршрут для превью: не более [maxPoints] точек, равномерно
     * прореженных по треку. Разовое чтение, без подписки — миниатюра в списке
     * не обязана перерисовываться на каждое изменение трека.
     */
    suspend fun getRoutePreview(
        activityId: Long,
        maxPoints: Int,
    ): Result<List<RoutePoint>, DataError.Local>

    /** Завершённые записи, которые пользователь ещё не подтвердил на экране сохранения. */
    fun observePendingSummaryActivities(): Flow<Result<List<Activity>, DataError.Local>>

    /** Активность создаётся сразу с сегментами: активности без сегмента не бывает. */
    suspend fun createActivity(
        activity: Activity,
        segments: List<ActivitySegment>,
    ): Result<Long, DataError.Local>

    suspend fun updateActivity(activity: Activity): EmptyResult<DataError.Local>

    suspend fun getSegments(activityId: Long): Result<List<ActivitySegment>, DataError.Local>

    /** Сегменты всех записей: списку активностей нужны виды движения по всей базе разом. */
    fun observeAllSegments(): Flow<Result<List<ActivitySegment>, DataError.Local>>

    /**
     * Сохраняет открытый сегмент записи полями [current] (закрывая его, если задан конец)
     * и открывает [next] — одной транзакцией. Открытый сегмент у записи один, поэтому id
     * не нужен.
     */
    suspend fun saveOpenSegment(current: ActivitySegment, next: ActivitySegment?): EmptyResult<DataError.Local>

    suspend fun addGeoPoints(points: List<GeoPoint>): EmptyResult<DataError.Local>

    /** Ответ распознавания в записи авторежима: по всей их последовательности вид определяется при сохранении. */
    suspend fun addRecognitionSample(sample: RecognitionSample): EmptyResult<DataError.Local>

    suspend fun getRecognitionSamples(activityId: Long): Result<List<RecognitionSample>, DataError.Local>

    /** Шаги записи авторежима во времени: без них после смерти процесса и в пересчёте нет каденса. */
    suspend fun addStepSample(sample: StepSample): EmptyResult<DataError.Local>

    suspend fun getStepSamples(activityId: Long): Result<List<StepSample>, DataError.Local>

    /**
     * Atomically stores a complete track that arrived from outside the app.
     * Points are re-bound to the generated activity id, so a failed insert cannot
     * leave an activity without its track.
     */
    suspend fun insertTrack(
        activity: Activity,
        points: List<GeoPoint>,
        segments: List<ActivitySegment>,
    ): Result<Long, DataError.Local>

    /**
     * Atomically replaces an activity's calculated fields and its complete point set.
     * [segments] replace the stored ones; `null` leaves them untouched.
     */
    suspend fun replaceTrack(
        activity: Activity,
        points: List<GeoPoint>,
        segments: List<ActivitySegment>? = null,
    ): EmptyResult<DataError.Local>

    suspend fun deleteActivity(activityId: Long): EmptyResult<DataError.Local>
}
