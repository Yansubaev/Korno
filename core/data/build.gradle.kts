plugins {
    id("korno.android.library")
    id("korno.android.koin")
    id("korno.kotlin.serialization")
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:database"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.core)
}
