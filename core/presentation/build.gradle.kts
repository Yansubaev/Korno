plugins {
    id("korno.android.library")
    id("korno.android.compose")
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:design-system"))
    implementation(libs.androidx.lifecycle.runtime.compose)
}
