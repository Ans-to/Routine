pluginManagement {
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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Routine"

include(":app")
include(":core:model")
include(":core:permissions")
include(":core:engine")
include(":core:data")
include(":privilege:api")
include(":privilege:adb-bridge")
include(":privilege:one-time-grant")
include(":trigger:standard")
include(":action:standard")
include(":action:privileged")
include(":feature:templates")
include(":feature:rules")
include(":feature:onboarding")
include(":feature:settings")
