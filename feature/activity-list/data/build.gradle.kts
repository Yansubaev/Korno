plugins {
    id("korno.android.library")
    id("korno.android.koin")
}

dependencies {
    implementation(project(":feature:activity-list:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(project(":core:domain"))
}
