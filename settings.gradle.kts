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

rootProject.name = "Folio"
include(":app")

// Hermes client library, shared with Hermes Live (git submodule at hermes-live/).
include(":hermes")
project(":hermes").projectDir = file("hermes-live/hermes")
