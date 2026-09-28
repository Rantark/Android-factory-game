package io.github.rantark.factoryflow

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20

/** Scaffold entry point – replaced by the real game once the pipeline is verified. */
class FactoryGame(val platform: Platform) : ApplicationAdapter() {
    override fun render() {
        Gdx.gl.glClearColor(0.17f, 0.15f, 0.12f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
    }
}

/** Services the platform launcher provides to the platform-independent core. */
interface Platform
