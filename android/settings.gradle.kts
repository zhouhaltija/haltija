rootProject.name = "haltija"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

// app 会在后续里程碑加入
include(":core-data")
include(":core-prompt")
include(":core-provider")
include(":app")
