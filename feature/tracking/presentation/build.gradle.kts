plugins {
    id("korno.android.feature")
}

dependencies {
    implementation(project(":core:logging"))
    implementation(project(":core:map"))
    implementation(libs.androidx.activity.compose)
}
