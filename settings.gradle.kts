pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Korno"

include(":app")

include(":core:data")
include(":core:database")
include(":core:design-system")
include(":core:domain")
include(":core:logging")
include(":core:map")
include(":core:presentation")
include(":core:track-analytics")
include(":core:track-processing")

include(":data:gpx-export")
include(":data:location-tracking")
include(":data:remote")
include(":data:track-io")

include(":feature:activity-detail:data")
include(":feature:activity-detail:domain")
include(":feature:activity-detail:presentation")
include(":feature:activity-list:data")
include(":feature:activity-list:domain")
include(":feature:activity-list:presentation")
include(":feature:home:domain")
include(":feature:home:presentation")
include(":feature:onboarding:data")
include(":feature:onboarding:domain")
include(":feature:onboarding:presentation")
include(":feature:settings:data")
include(":feature:settings:domain")
include(":feature:settings:presentation")
include(":feature:tracking:data")
include(":feature:tracking:domain")
include(":feature:tracking:presentation")

include(":tools:track-calibration")
