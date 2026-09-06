plugins {
    id("korno.kotlin.domain")
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:track-processing"))
}
