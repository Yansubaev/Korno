import org.gradle.kotlin.dsl.dependencies

plugins {
    id("korno.android.library")
    id("korno.android.koin")
    id("korno.android.room")
}

dependencies {
    implementation(project(":core:domain"))
}
