package io.github.rantark.factoryflow.desktop

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import io.github.rantark.factoryflow.FactoryGame
import io.github.rantark.factoryflow.Platform

fun main(args: Array<String>) {
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("Factory Flow (desktop dev build)")
        setWindowedMode(1280, 720)
        useVsync(true)
        setForegroundFPS(60)
    }
    Lwjgl3Application(FactoryGame(object : Platform {}), config)
}
