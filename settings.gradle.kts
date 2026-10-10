pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "kinsight"
include(":core:crypto", ":core:model", ":core:sync", ":core:testing")
include(":core:keystore")
include(":core:database")
include(":core:usage")
include(":core:transport-ble")
include(":core:designsystem")
include(":core:data")
include(":feature:onboarding")
include(":feature:group")
include(":feature:dashboard")
include(":app")
