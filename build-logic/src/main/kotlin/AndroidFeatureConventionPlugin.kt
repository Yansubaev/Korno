import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("korno.android.library")
        pluginManager.apply("korno.android.compose")
        pluginManager.apply("korno.android.koin")
        pluginManager.apply("korno.kotlin.serialization")

        val featureDomainPath = "${path.substringBeforeLast(':')}:domain"
        dependencies {
            add("implementation", project(featureDomainPath))
            add("implementation", project(":core:domain"))
            add("implementation", project(":core:presentation"))
            add("implementation", project(":core:design-system"))
            add("implementation", libs.findLibrary("androidx-lifecycle-runtime-compose").get())
            add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
            add("implementation", libs.findLibrary("androidx-navigation-compose").get())
            add("implementation", libs.findLibrary("koin-androidx-compose").get())
        }
    }
}
