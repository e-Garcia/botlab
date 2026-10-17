package ga.egarcia.botlab

import android.app.Application
import ga.egarcia.botlab.env.RaspBridge

class BotLabApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RaspBridge.start(this)
    }
}
