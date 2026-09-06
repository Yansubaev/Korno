plugins {
    id("korno.android.application")
    id("korno.android.compose")
    id("korno.android.koin")
    id("korno.kotlin.serialization")
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(project(":core:design-system"))
    implementation(project(":core:domain"))
    implementation(project(":core:logging"))
    implementation(project(":core:map"))
    implementation(project(":core:track-processing"))

    implementation(project(":data:gpx-export"))
    implementation(project(":data:location-tracking"))
    implementation(project(":data:remote"))
    implementation(project(":data:track-io"))

    implementation(project(":feature:activity-detail:data"))
    implementation(project(":feature:activity-detail:presentation"))
    implementation(project(":feature:activity-list:data"))
    implementation(project(":feature:activity-list:presentation"))
    implementation(project(":feature:home:presentation"))
    implementation(project(":feature:onboarding:data"))
    implementation(project(":feature:onboarding:domain"))
    implementation(project(":feature:onboarding:presentation"))
    implementation(project(":feature:settings:data"))
    implementation(project(":feature:settings:presentation"))
    implementation(project(":feature:tracking:data"))
    implementation(project(":feature:tracking:domain"))
    implementation(project(":feature:tracking:presentation"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.koin.androidx.compose)
}
