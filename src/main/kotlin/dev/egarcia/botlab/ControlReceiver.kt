package ga.egarcia.botlab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ga.egarcia.botlab.analysis.BehaviorAnalyzer
import ga.egarcia.botlab.env.RaspBridge

/**
 *   adb shell am broadcast -a ga.egarcia.botlab.RESET -n ga.egarcia.botlab/.ControlReceiver
 *   adb shell am broadcast -a ga.egarcia.botlab.SAVE  -n ga.egarcia.botlab/.ControlReceiver --es label harness-api
 *   adb shell am broadcast -a ga.egarcia.botlab.RASP  -n ga.egarcia.botlab/.ControlReceiver --ez prod true   (then restart)
 */
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "ga.egarcia.botlab.RESET" -> {
                Lab.reset()
                Log.i("BotLab", "reset")
            }
            "ga.egarcia.botlab.RASP" -> {
                val prod = intent.getBooleanExtra("prod", true)
                RaspBridge.setProductionMode(context.applicationContext as android.app.Application, prod)
                Log.i("BotLab", "freeRASP mode set to ${if (prod) "production" else "debug"}; restart the app to apply")
            }
            "ga.egarcia.botlab.SAVE" -> {
                val file = Lab.save(context, intent.getStringExtra("label").orEmpty())
                val report = BehaviorAnalyzer.analyze(Lab.events())
                Log.i("BotLab", "saved ${file.name} score=${report.score} verdict=${report.verdict.label} events=${report.eventCount}")
            }
        }
    }
}
