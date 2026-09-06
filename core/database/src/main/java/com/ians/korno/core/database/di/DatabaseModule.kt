package com.ians.korno.core.database.di

import androidx.room.Room
import com.ians.korno.core.database.KornoDatabase
import org.koin.dsl.module

val databaseModule = module {
    single {
        Room.databaseBuilder(
            get(),
            KornoDatabase::class.java,
            KornoDatabase.DATABASE_NAME,
        ).build()
    }
    single { get<KornoDatabase>().trackDao() }
}
