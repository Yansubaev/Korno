plugins {
    id("korno.android.library")
    id("korno.android.koin")
}

dependencies {
    implementation(project(":feature:tracking:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(project(":core:domain"))
    implementation(project(":core:logging"))
    implementation(project(":core:track-processing"))
    implementation(project(":data:location-tracking"))
}
