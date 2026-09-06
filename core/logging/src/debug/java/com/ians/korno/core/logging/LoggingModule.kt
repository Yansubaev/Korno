package com.ians.korno.core.logging

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val loggingModule = module {
    single { DebugLogger(androidContext()) }
    single<AppLogger> { get<DebugLogger>() }
    single<LogManager> { get<DebugLogger>() }
    single<ActivityLogScope> { get<DebugLogger>() }
}
