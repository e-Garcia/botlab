package dev.egarcia.botlab

import android.app.Application
import dev.egarcia.botlab.env.RaspBridge

class BotLabApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RaspBridge.start(this)
    }
}
