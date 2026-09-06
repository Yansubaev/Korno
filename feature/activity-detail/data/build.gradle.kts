plugins {
    id("korno.android.library")
    id("korno.android.koin")
}

dependencies {
    implementation(project(":feature:activity-detail:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(project(":core:domain"))
    implementation(project(":core:track-processing"))
    implementation(project(":data:gpx-export"))
}
