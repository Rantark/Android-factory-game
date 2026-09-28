pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FactoryFlow"

// core    – all game code (pure Kotlin + libGDX, platform independent)
// android – the Android launcher that produces the APK
// desktop – an optional LWJGL3 launcher used for local testing/screenshots
include(":core", ":android")
if (System.getenv("CI_ANDROID_ONLY") == null) include(":desktop")
