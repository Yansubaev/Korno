plugins {
    id("korno.android.library")
    id("korno.android.koin")
    id("korno.android.ktor")
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:domain"))
}
