package com.ians.korno

import android.app.Application
import com.ians.korno.core.data.di.coreDataModule
import com.ians.korno.core.database.di.databaseModule
import com.ians.korno.core.domain.repository.MapCacheRepository
import com.ians.korno.core.logging.AppLogger
import com.ians.korno.core.logging.LogCategory
import com.ians.korno.core.logging.info
import com.ians.korno.core.logging.loggingModule
import com.ians.korno.core.map.di.mapModule
import com.ians.korno.core.trackprocessing.ManualCorrectionUseCase
import com.ians.korno.core.trackprocessing.PrivacyZoneUseCase
import com.ians.korno.core.trackprocessing.StatisticsCalculatorUseCase
import com.ians.korno.core.trackprocessing.TrackProcessor
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
import com.ians.korno.feature.tracking.domain.usecase.RecoverActiveSessionUseCase
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
    factoryOf(::ManualCorrectionUseCase)
    factoryOf(::PrivacyZoneUseCase)
    factoryOf(::StatisticsCalculatorUseCase)
}

private val appModule = module {
    viewModelOf(::KornoAppViewModel)
}

class KornoApplication : Application() {

    private val recoverActiveSession: RecoverActiveSessionUseCase by inject()
    private val mapCacheRepository: MapCacheRepository by inject()
    private val pendingSummaryNotifier: PendingSummaryNotifier by inject()
    private val logger: AppLogger by inject()

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

        // Если процесс был убит системой во время записи — незавершённая Activity (end_time IS NULL)
        // в Room служит сигналом восстановить foreground-сервис и продолжить ту же запись.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            recoverActiveSession()
            // Кэш тайлов ограничивается один раз за запуск: настройка живёт в базе
            // MapLibre, но применяется только после явного вызова.
            mapCacheRepository.configure()
        }

        // Записи, остановленные кнопкой в уведомлении, сохраняются без подтверждения —
        // пока такие есть, о них напоминает отдельное уведомление.
        pendingSummaryNotifier.startWatching()
    }

    private companion object {
        const val TAG = "KornoApplication"
    }
}
