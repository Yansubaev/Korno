package com.ians.korno.data.locationtracking.diagnostics

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import com.ians.korno.core.logging.AppLogger
import com.ians.korno.core.logging.LogCategory
import com.ians.korno.core.logging.info
import com.ians.korno.core.logging.warn
import com.ians.korno.data.locationtracking.permission.LocationPermissionChecker
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Подписка на распознавание активности. У Play Services нет колбэка: результат приходит
 * broadcast-интентом, поэтому приёмник регистрируется динамически и живёт ровно столько,
 * сколько собирается поток — в манифесте ему делать нечего.
 *
 * Подписка стоит батареи, поэтому включается только под запись трека — в авторежиме
 * или при диагностическом логировании (см. вызывающий сервис). Подписываться дважды
 * нельзя: обе подписки делят один PendingIntent, и отписка одной сняла бы обе.
 */
class ActivityRecognitionSignalSource(
    private val context: Context,
    private val permissionChecker: LocationPermissionChecker,
    private val logger: AppLogger,
) : TrackingSignalSource {

    private val subscriptionCounter = AtomicInteger()

    /**
     * Распознавание вообще может работать: разрешение выдано и Play Services на месте.
     * Без этого авторежим записи не предлагается — эвристики по одной скорости нет.
     */
    fun isAvailable(): Boolean =
        permissionChecker.hasActivityRecognitionPermission() &&
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    // Разрешение проверяется первой же строкой, но lint не видит проверку через
    // собственный интерфейс и требует либо аннотации, либо подавления на месте вызова.
    @SuppressLint("MissingPermission")
    override fun observeSignals(): Flow<TrackingSignal> {
        if (!permissionChecker.hasActivityRecognitionPermission()) {
            return flowOf(TrackingSignal.ActivityRecognitionSubscription(STATUS_NO_PERMISSION))
        }
        return callbackFlow {
            val subscription = subscriptionCounter.incrementAndGet()
            val broadcasts = AtomicInteger()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    broadcasts.incrementAndGet()
                    val result = intent.takeIf(ActivityRecognitionResult::hasResult)
                        ?.let(ActivityRecognitionResult::extractResult)
                    val now = SystemClock.elapsedRealtime()
                    trySend(
                        TrackingSignal.ActivityRecognitionBroadcast(
                            subscription = subscription,
                            hasResult = result != null,
                            action = intent.action,
                            extrasKeys = runCatching { intent.extras?.keySet()?.sorted() }
                                .getOrNull()
                                .orEmpty(),
                            resultAgeMillis = result?.let { now - it.elapsedRealtimeMillis },
                            receivedAtElapsedMillis = now,
                        ),
                    )
                    result?.let { trySend(it.toSignal()) }
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(ACTION_ACTIVITY_UPDATE),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(ACTION_ACTIVITY_UPDATE).setPackage(context.packageName),
                pendingIntentFlags(),
            )
            val client = ActivityRecognition.getClient(context)
            // Отказ Play Services закрывает поток ошибкой: сервис пишет её в лог, запись трека
            // идёт дальше. Отказ приходит двумя путями — исключением сразу и упавшим Task
            // позже; второй раньше не слушался, и подписка умирала без следа.
            val request = runCatching {
                client.requestActivityUpdates(UPDATE_INTERVAL_MILLIS, pendingIntent)
                    .addOnSuccessListener {
                        trySend(
                            TrackingSignal.ActivityRecognitionSubscription(
                                status = STATUS_SUBSCRIBED,
                                subscription = subscription,
                            ),
                        )
                    }
                    .addOnFailureListener { error -> close(error) }
            }
            request.exceptionOrNull()?.let { error ->
                runCatching { context.unregisterReceiver(receiver) }
                close(error)
                return@callbackFlow
            }

            // Отсутствие вердиктов раньше выражалось только отсутствием строк — и его нельзя
            // было отличить от «сборка такого не пишет». Молчание пишется явно.
            launch {
                delay(SILENCE_WARNING_MILLIS.milliseconds)
                if (broadcasts.get() == 0) {
                    logger.warn(
                        category = LogCategory.SYSTEM,
                        tag = TAG,
                        fields = mapOf(
                            "subscription" to subscription.toString(),
                            "waitedMillis" to SILENCE_WARNING_MILLIS.toString(),
                        ),
                    ) { "Activity recognition silent" }
                }
            }

            awaitClose {
                // Итог снятия приходит уже после закрытия потока, поэтому пишется
                // напрямую. Снятие, опередившее следующую подписку на тот же
                // PendingIntent, иначе было бы не видно.
                val fields = mapOf(
                    "subscription" to subscription.toString(),
                    "broadcastCount" to broadcasts.get().toString(),
                )
                runCatching {
                    client.removeActivityUpdates(pendingIntent)
                        .addOnSuccessListener {
                            logger.info(LogCategory.SYSTEM, TAG, fields) {
                                "Activity recognition updates removed"
                            }
                        }
                        .addOnFailureListener { error ->
                            logger.warn(LogCategory.SYSTEM, TAG, error, fields) {
                                "Activity recognition removal failed"
                            }
                        }
                }
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
            // Распознавание повторяет один и тот же вердикт каждые несколько секунд;
            // в логе нужен момент смены, а не подтверждение прежнего состояния. Дедупликация
            // по типу: вердикты чередуются с broadcast'ами и подряд не идут никогда.
            .distinctPerSignalType()
    }

    /**
     * Подписка живёт в Play Services, а не в процессе: если процесс убили посреди записи,
     * `awaitClose` не выполнился, и распознавание продолжает жечь батарею до перезагрузки.
     * `FLAG_NO_CREATE` находит PendingIntent, только если его всё ещё кто-то держит.
     *
     * Зависшая подписка переживает только смерть процесса, поэтому вызывать — один раз при
     * старте процесса, до любой записи. PendingIntent не отменяется: когда вызов делался при
     * каждом открытии экрана с `cancel()`, все следующие подписки процесса молчали (записи 4
     * и 5 — ни одного вердикта, запись 2 в том же процессе до вызова работала). Механизм в
     * Play Services не доказан, но это единственное отличие от прежних рабочих серий.
     */
    @SuppressLint("MissingPermission")
    fun removeStaleUpdates(): Boolean {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(ACTION_ACTIVITY_UPDATE).setPackage(context.packageName),
            pendingIntentFlags() or PendingIntent.FLAG_NO_CREATE,
        ) ?: return false
        runCatching {
            ActivityRecognition.getClient(context).removeActivityUpdates(pendingIntent)
                .addOnFailureListener { error ->
                    logger.warn(LogCategory.SYSTEM, TAG, error) {
                        "Stale activity recognition removal failed"
                    }
                }
        }
        return true
    }

    /**
     * В вердикт идёт самый вероятный вариант, но `ON_FOOT` — не вариант, а зонтик над
     * `WALKING` и `RUNNING`, и Play Services почти всегда ставит его первым. В логе
     * прогулки из 57 записей 53 оказались `ON_FOOT`, а `STILL` не появился ни разу — при
     * том, что человек четыре минуты стоял на месте. Поэтому зонтик уступает место более
     * конкретному варианту, если тот назван с сопоставимой уверенностью, а альтернативы
     * пишутся всегда: по ним видно, что система на самом деле различала.
     */
    private fun ActivityRecognitionResult.toSignal(): TrackingSignal.ActivityRecognized {
        val ranked = probableActivities.sortedByDescending(DetectedActivity::getConfidence)
        val mostProbable = ranked.firstOrNull()
        val specific = ranked.firstOrNull { it.type in SPECIFIC_ON_FOOT_TYPES }
        val chosen = when {
            mostProbable == null -> null
            mostProbable.type != DetectedActivity.ON_FOOT -> mostProbable
            specific != null && specific.confidence >= mostProbable.confidence - ON_FOOT_MARGIN -> specific
            else -> mostProbable
        }
        return TrackingSignal.ActivityRecognized(
            activityType = chosen?.type?.activityName() ?: "UNKNOWN",
            confidencePercent = chosen?.confidence ?: 0,
            alternatives = ranked.filterNot { it === chosen }
                .take(MAX_ALTERNATIVES)
                .associate { it.type.activityName() to it.confidence },
        )
    }

    private fun pendingIntentFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Play Services дописывает результат в интент, поэтому MUTABLE обязателен.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun Int.activityName(): String = when (this) {
        DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
        DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
        DetectedActivity.ON_FOOT -> "ON_FOOT"
        DetectedActivity.RUNNING -> "RUNNING"
        DetectedActivity.WALKING -> "WALKING"
        DetectedActivity.STILL -> "STILL"
        DetectedActivity.TILTING -> "TILTING"
        else -> "UNKNOWN"
    }

    private companion object {
        const val TAG = "ActivityRecognition"
        const val SILENCE_WARNING_MILLIS = 60_000L
        const val ACTION_ACTIVITY_UPDATE = "com.ians.korno.ACTION_ACTIVITY_RECOGNITION_UPDATE"
        const val REQUEST_CODE = 4201
        const val UPDATE_INTERVAL_MILLIS = 15_000L
        const val MAX_ALTERNATIVES = 3
        const val STATUS_SUBSCRIBED = "SUBSCRIBED"
        const val STATUS_NO_PERMISSION = "NO_PERMISSION"

        /** Варианты, ради которых стоит отодвинуть зонтичный `ON_FOOT`. */
        val SPECIFIC_ON_FOOT_TYPES = setOf(DetectedActivity.WALKING, DetectedActivity.RUNNING)

        /**
         * На сколько процентов уверенности конкретный вариант может уступать зонтичному
         * и всё равно попасть в вердикт. `ON_FOOT` набирает сумму `WALKING` и `RUNNING`,
         * поэтому при честной ходьбе он опережает `WALKING` заведомо.
         */
        const val ON_FOOT_MARGIN = 40
    }
}
