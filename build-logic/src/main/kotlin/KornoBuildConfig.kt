import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

internal const val KORNO_COMPILE_SDK = 37
internal const val KORNO_MIN_SDK = 24
internal const val KORNO_TARGET_SDK = 37

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun Project.configureAndroid(
    extension: CommonExtension,
) {
    extension.apply {
        compileSdk = KORNO_COMPILE_SDK
        defaultConfig.minSdk = KORNO_MIN_SDK
        compileOptions.sourceCompatibility = JavaVersion.VERSION_17
        compileOptions.targetCompatibility = JavaVersion.VERSION_17
        testOptions.unitTests.all {
            it.useJUnitPlatform()
        }
    }

    dependencies {
        add("implementation", libs.findLibrary("androidx-core-ktx").get())
        addUnitTestDependencies(libs)
        add("androidTestImplementation", libs.findLibrary("androidx-junit").get())
        add("androidTestImplementation", libs.findLibrary("androidx-espresso-core").get())
        add("androidTestImplementation", libs.findLibrary("assertk").get())
        add("androidTestImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
    }
}

internal fun Project.configureKotlinJvm() {
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
    dependencies {
        add("implementation", libs.findLibrary("kotlinx-coroutines-core").get())
        addUnitTestDependencies(libs)
    }
}

private fun org.gradle.api.artifacts.dsl.DependencyHandler.addUnitTestDependencies(
    catalog: VersionCatalog,
) {
    add("testImplementation", catalog.findLibrary("junit-jupiter").get())
    add("testImplementation", catalog.findLibrary("assertk").get())
    add("testImplementation", catalog.findLibrary("kotlinx-coroutines-test").get())
    add("testImplementation", catalog.findLibrary("turbine").get())
    add("testRuntimeOnly", catalog.findLibrary("junit-platform-launcher").get())
}

internal fun Project.generatedNamespace(): String =
    "com.ians.korno." + path
        .split(':')
        .filter(String::isNotBlank)
        .joinToString(separator = ".") { segment ->
            segment.replace("-", "")
        }
