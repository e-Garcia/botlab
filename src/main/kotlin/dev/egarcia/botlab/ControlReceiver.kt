package dev.egarcia.botlab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.egarcia.botlab.analysis.BehaviorAnalyzer
import dev.egarcia.botlab.env.RaspBridge

/**
 *   adb shell am broadcast -a dev.egarcia.botlab.RESET -n dev.egarcia.botlab/.ControlReceiver
 *   adb shell am broadcast -a dev.egarcia.botlab.SAVE  -n dev.egarcia.botlab/.ControlReceiver --es label harness-api
 *   adb shell am broadcast -a dev.egarcia.botlab.RASP  -n dev.egarcia.botlab/.ControlReceiver --ez prod true   (then restart)
 */
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "dev.egarcia.botlab.RESET" -> {
                Lab.reset()
                Log.i("BotLab", "reset")
            }
            "dev.egarcia.botlab.RASP" -> {
                val prod = intent.getBooleanExtra("prod", true)
                RaspBridge.setProductionMode(context.applicationContext as android.app.Application, prod)
                Log.i("BotLab", "freeRASP mode set to ${if (prod) "production" else "debug"}; restart the app to apply")
            }
            "dev.egarcia.botlab.SAVE" -> {
                val file = Lab.save(context, intent.getStringExtra("label").orEmpty())
                val report = BehaviorAnalyzer.analyze(Lab.events())
                Log.i("BotLab", "saved ${file.name} score=${report.score} verdict=${report.verdict.label} events=${report.eventCount}")
            }
        }
    }
}
