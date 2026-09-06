plugins {
    `kotlin-dsl`
}

group = "com.ians.korno.buildlogic"

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.compose.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
    implementation(libs.room.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "korno.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "korno.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "korno.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "korno.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("androidKoin") {
            id = "korno.android.koin"
            implementationClass = "AndroidKoinConventionPlugin"
        }
        register("androidKtor") {
            id = "korno.android.ktor"
            implementationClass = "AndroidKtorConventionPlugin"
        }
        register("androidRoom") {
            id = "korno.android.room"
            implementationClass = "AndroidRoomConventionPlugin"
        }
        register("kotlinDomain") {
            id = "korno.kotlin.domain"
            implementationClass = "KotlinDomainConventionPlugin"
        }
        register("kotlinSerialization") {
            id = "korno.kotlin.serialization"
            implementationClass = "KotlinSerializationConventionPlugin"
        }
    }
}
