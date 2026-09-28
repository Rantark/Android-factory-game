import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val gdxVersion: String by project

// libGDX ships its native .so files inside "natives-<abi>" jars. This configuration
// collects them so the copyAndroidNatives task can unpack them into libs/<abi>/.
val natives: Configuration by configurations.creating

// Optional release signing, driven by environment variables (set from GitHub Secrets
// in CI). When they are absent the release APK is signed with the debug key instead.
val keystorePath: String? = System.getenv("FF_KEYSTORE_PATH")
val hasReleaseKey = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

// CI passes the build number so every published APK has an increasing versionCode.
val buildNumber = (System.getenv("FF_BUILD_NUMBER") ?: "1").toInt()

android {
    namespace = "io.github.rantark.factoryflow"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.rantark.factoryflow"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("FF_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FF_KEY_ALIAS")
                keyPassword = System.getenv("FF_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/robovm/**")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation("com.badlogicgames.gdx:gdx-backend-android:$gdxVersion")
    natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-armeabi-v7a")
    natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-arm64-v8a")
    natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-x86")
    natives("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-x86_64")
}

// Unpack libGDX native libraries into android/libs/<abi>/ before packaging.
val copyAndroidNatives by tasks.registering {
    val abis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    inputs.files(natives)
    outputs.dir(layout.projectDirectory.dir("libs"))
    doFirst {
        natives.files.forEach { jar ->
            val abi = abis.firstOrNull { jar.name.endsWith("natives-$it.jar") } ?: return@forEach
            val outDir = file("libs/$abi").apply { mkdirs() }
            project.copy {
                from(project.zipTree(jar))
                into(outDir)
                include("*.so")
            }
        }
    }
}

tasks.matching { it.name.contains("merge") && it.name.contains("JniLibFolders") }.configureEach {
    dependsOn(copyAndroidNatives)
}
tasks.named("preBuild") { dependsOn(copyAndroidNatives) }
