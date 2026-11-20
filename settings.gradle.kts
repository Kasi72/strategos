pluginManagement {
    repositories {
        google()
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
rootProject.name = "Strategos"
// The server image has no Android SDK: `-Pstrategos.serverOnly=true` builds only :server (it compiles the engine from app's sources).
if (providers.gradleProperty("strategos.serverOnly").orNull != "true") include(":app")
include(":server")
