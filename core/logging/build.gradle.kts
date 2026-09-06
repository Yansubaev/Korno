plugins {
    id("korno.android.library")
    id("korno.android.koin")
    id("korno.kotlin.serialization")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    // The concrete backend is absent from release; only the facade and no-op binding remain.
    debugImplementation(libs.androidx.datastore.preferences)
    debugImplementation(libs.kermit)
    debugImplementation(libs.kotlinx.serialization.json)
}
