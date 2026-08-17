pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "OpenChat"
include(":domain")
include(":android")
include(":server")
