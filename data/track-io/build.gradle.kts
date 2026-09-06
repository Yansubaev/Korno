plugins {
    id("korno.android.library")
    id("korno.android.koin")
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:logging"))
    implementation(project(":core:track-processing"))
    implementation(project(":data:gpx-export"))

    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.core)
}
