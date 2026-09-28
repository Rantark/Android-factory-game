import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Desktop launcher: NOT part of the shipped product. It exists so the game can be
// run and screenshotted on a PC (e.g. under Xvfb) while developing.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

val gdxVersion: String by project

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

application {
    mainClass.set("io.github.rantark.factoryflow.desktop.DesktopLauncherKt")
}

dependencies {
    implementation(project(":core"))
    implementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:$gdxVersion")
    implementation("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.file("desktop/run").apply { mkdirs() }
    // Pass game flags through: ./gradlew :desktop:run --args="--demo --shot out.png"
}
