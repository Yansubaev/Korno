package com.ians.korno

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import androidx.core.content.getSystemService
import com.ians.korno.core.data.di.coreDataModule
import com.ians.korno.core.database.di.databaseModule
import com.ians.korno.core.domain.repository.MapCacheRepository
import com.ians.korno.core.domain.repository.TrackRepository
import com.ians.korno.core.logging.AppLogger
import com.ians.korno.core.logging.LogCategory
import com.ians.korno.core.logging.info
import com.ians.korno.core.logging.loggingModule
import com.ians.korno.core.map.di.mapModule
import com.ians.korno.core.trackprocessing.FinalizeTrackUseCase
import com.ians.korno.core.trackprocessing.PrivacyZoneUseCase
import com.ians.korno.core.trackprocessing.StatisticsCalculatorUseCase
import com.ians.korno.core.trackprocessing.TrackProcessor
import com.ians.korno.data.locationtracking.diagnostics.ActivityRecognitionSignalSource
import com.ians.korno.data.locationtracking.di.locationTrackingModule
import com.ians.korno.data.trackio.di.trackIoModule
import com.ians.korno.feature.activitydetail.presentation.di.activityDetailPresentationModule
import com.ians.korno.feature.activitylist.presentation.di.activityListPresentationModule
import com.ians.korno.feature.home.presentation.di.homePresentationModule
import com.ians.korno.feature.onboarding.data.di.onboardingDataModule
import com.ians.korno.feature.onboarding.presentation.di.onboardingPresentationModule
import com.ians.korno.feature.settings.presentation.di.settingsPresentationModule
import com.ians.korno.feature.tracking.data.di.trackingDataModule
import com.ians.korno.feature.tracking.domain.repository.PendingSummaryNotifier
import com.ians.korno.feature.tracking.presentation.di.trackingPresentationModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

private val trackProcessingModule = module {
    // Lambda намеренная: внутренние этапы TrackProcessor имеют default-конструкторы
    // и не должны отдельно становиться частью DI-графа.
    single { TrackProcessor() }
    factory { FinalizeTrackUseCase(get()) }
    factoryOf(::PrivacyZoneUseCase)
    factoryOf(::StatisticsCalculatorUseCase)
}

private val appModule = module {
    viewModelOf(::KornoAppViewModel)
}

class KornoApplication : Application() {

    private val mapCacheRepository: MapCacheRepository by inject()
    private val trackRepository: TrackRepository by inject()
    private val pendingSummaryNotifier: PendingSummaryNotifier by inject()
    private val logger: AppLogger by inject()
    private val activityRecognition: ActivityRecognitionSignalSource by inject()

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@KornoApplication)
            modules(
                databaseModule,
                loggingModule,
                coreDataModule,
                mapModule,
                appModule,
                trackProcessingModule,
                locationTrackingModule,
                trackIoModule,
                onboardingDataModule,
                onboardingPresentationModule,
                trackingDataModule,
                trackingPresentationModule,
                homePresentationModule,
                activityListPresentationModule,
                activityDetailPresentationModule,
                settingsPresentationModule,
            )
        }

        logger.info(LogCategory.APP, TAG) {
            "Application started"
        }
        logPreviousProcessExit()

        // Подписка на распознавание, оставшаяся от убитого процесса. Только здесь: в свежем
        // процессе записи ещё нет, а позже вызов снял бы живую подписку.
        if (activityRecognition.removeStaleUpdates()) {
            logger.info(LogCategory.SYSTEM, TAG) { "Stale activity recognition subscription removed" }
        }

        // Восстановление незавершённой записи — в KornoAppViewModel: процесс стартует и в фоне
        // (WorkManager, уведомления), а foreground-сервис оттуда запускать нельзя.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Кэш тайлов ограничивается один раз за запуск: настройка живёт в базе
            // MapLibre, но применяется только после явного вызова.
            mapCacheRepository.configure()
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Превью миниатюр для записей, сохранённых до их появления. Разово: дальше запрос
            // пустой, превью пишутся вместе с треком.
            trackRepository.backfillRoutePreviews()
        }

        // Записи, остановленные кнопкой в уведомлении, сохраняются без подтверждения —
        // пока такие есть, о них напоминает отдельное уведомление.
        pendingSummaryNotifier.startWatching()
    }

    /**
     * Почему умер прошлый процесс: смахнули из недавних, крэш, ANR, нехватка памяти.
     * Крэш, который логгер не успел сбросить на диск, виден только здесь, а «процесс
     * умер через 11 секунд после стопа» без этой строки превращается в переписку.
     */
    private fun logPreviousProcessExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val exit = runCatching {
            getSystemService<ActivityManager>()
                ?.getHistoricalProcessExitReasons(packageName, 0, 1)
                ?.firstOrNull()
        }.getOrNull() ?: return
        logger.info(
            category = LogCategory.APP,
            tag = TAG,
            fields = mapOf(
                "reason" to exitReasonName(exit.reason),
                "description" to exit.description.toString(),
                "status" to exit.status.toString(),
                "importance" to exit.importance.toString(),
                "exitTimestamp" to exit.timestamp.toString(),
            ),
        ) { "Previous process exit" }
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "UNKNOWN($reason)"
    }

    private companion object {
        const val TAG = "KornoApplication"
    }
}
