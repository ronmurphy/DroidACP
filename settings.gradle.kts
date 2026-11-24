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

rootProject.name = "DroidACP"

// :core — plain Kotlin (no Android): the ACP protocol, chat state and markdown parser.
// :app  — the Android app (Jetpack Compose UI, foreground service, home-screen widget).
include(":core", ":app")
