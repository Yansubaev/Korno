plugins {
    id("korno.android.feature")
}

dependencies {
    implementation(project(":feature:tracking:domain"))
    implementation(project(":core:map"))
}
