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
        // Xposed API (compileOnly)。若该仓库不可达，见 README「Xposed API 兜底」。
        maven { url = uri("https://api.xposed.info/") }
    }
}

rootProject.name = "wxbridge"
include(":app")
