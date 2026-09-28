package io.github.rantark.factoryflow.android

import android.os.Bundle
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import io.github.rantark.factoryflow.FactoryGame
import io.github.rantark.factoryflow.Platform

class AndroidLauncher : AndroidApplication() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = AndroidApplicationConfiguration().apply {
            useImmersiveMode = true
            numSamples = 2
        }
        initialize(FactoryGame(object : Platform {}), config)
    }
}
