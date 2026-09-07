plugins {
    id("korno.kotlin.domain")
    application
}

application {
    mainClass = "com.ians.korno.tools.trackcalibration.MainKt"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:track-processing"))
    implementation(project(":data:gpx-export"))
}
