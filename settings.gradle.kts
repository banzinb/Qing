pluginManagement {
    repositories {
        // Qing: Aliyun mirrors first for builds in CN networks; upstream as fallback.
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Qing: Aliyun mirrors first for builds in CN networks; upstream as fallback.
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Aether"
include(":shared")

val sharedOnly = providers.gradleProperty("aether.sharedOnly").orNull.toBoolean()
if (!sharedOnly) {
    include(":app")
    include(":terminal-emulator")
    include(":terminal-view")

    project(":terminal-emulator").projectDir = file("third_party/termux/terminal-emulator")
    project(":terminal-view").projectDir = file("third_party/termux/terminal-view")
}
