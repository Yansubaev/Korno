plugins {
    id("korno.android.library")
    id("korno.android.koin")
}

dependencies {
    implementation(project(":feature:onboarding:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:domain"))
    implementation(project(":data:location-tracking"))
}
