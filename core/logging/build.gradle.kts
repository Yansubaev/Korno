plugins {
    id("korno.android.library")
    id("korno.android.koin")
    id("korno.kotlin.serialization")
}

// Какой сборкой записан лог: `versionName` у всех debug-сборок один и тот же.
// Значения читаются через providers.exec, поэтому configuration cache пересобирается
// при каждом новом коммите или смене «грязности» дерева — и только тогда.
val gitSha = providers.exec {
    commandLine("git", "rev-parse", "--short=10", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }

val gitDirty = providers.exec {
    commandLine("git", "status", "--porcelain", "--untracked-files=no")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.isNotBlank() }

android {
    buildFeatures.buildConfig = true
    buildTypes {
        getByName("debug") {
            buildConfigField("String", "GIT_SHA", "\"${gitSha.get()}\"")
            buildConfigField("boolean", "GIT_DIRTY", gitDirty.get().toString())
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    // The concrete backend is absent from release; only the facade and no-op binding remain.
    debugImplementation(libs.androidx.datastore.preferences)
    debugImplementation(libs.kermit)
    debugImplementation(libs.kotlinx.serialization.json)
}
