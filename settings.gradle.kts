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
        // La librería que lee YouTube (NewPipe Extractor) se publica en JitPack
        maven(url = "https://jitpack.io")
    }
}
rootProject.name = "Vista"
include(":app")
