import java.io.StringReader
import java.util.Properties

plugins {
    id("korno.android.library")
    id("korno.android.compose")
    id("korno.android.koin")
}

/**
 * Vector tiles come from OpenFreeMap by default: OpenStreetMap data, no API key,
 * no vendor account. Другой провайдер подключается без правок кода — достаточно
 * положить в `local.properties` (или передать как `-P`) одно из свойств:
 *
 * - `korno.maptiler.key` — ключ MapTiler, стили подставятся автоматически;
 * - `korno.map.styleUrl` / `korno.map.styleUrlDark` — явные URL style.json.
 */
private val localProperties: Provider<Properties> = providers
    .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText
    .map { text -> Properties().apply { load(StringReader(text)) } }
    .orElse(Properties())

fun mapProperty(key: String): String? =
    (providers.gradleProperty(key).orNull ?: localProperties.get().getProperty(key))
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private val maptilerKey: String? = mapProperty("korno.maptiler.key")

private val lightStyleUrl: String = mapProperty("korno.map.styleUrl")
    ?: maptilerKey?.let { "https://api.maptiler.com/maps/streets-v2/style.json?key=$it" }
    ?: "https://tiles.openfreemap.org/styles/positron"

private val darkStyleUrl: String = mapProperty("korno.map.styleUrlDark")
    ?: maptilerKey?.let { "https://api.maptiler.com/maps/streets-v2-dark/style.json?key=$it" }
    ?: "https://tiles.openfreemap.org/styles/dark"

android {
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        buildConfigField("String", "MAP_STYLE_URL_LIGHT", "\"$lightStyleUrl\"")
        buildConfigField("String", "MAP_STYLE_URL_DARK", "\"$darkStyleUrl\"")
    }
}

dependencies {
    implementation(project(":core:domain"))

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.maplibre.android.sdk)
}
