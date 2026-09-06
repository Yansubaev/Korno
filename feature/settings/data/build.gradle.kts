plugins {
    id("korno.android.library")
    id("korno.android.koin")
}

dependencies {
    implementation(project(":feature:settings:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:domain"))
}
