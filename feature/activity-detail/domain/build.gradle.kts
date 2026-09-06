plugins {
    id("korno.kotlin.domain")
}

dependencies {
    implementation(project(":core:domain"))
    api(project(":core:track-processing"))
    api(project(":core:track-analytics"))
}
