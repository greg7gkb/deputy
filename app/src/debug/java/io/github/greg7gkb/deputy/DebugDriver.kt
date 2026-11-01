package io.github.greg7gkb.deputy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek

/**
 * `adb shell am broadcast -a io.github.greg7gkb.deputy.DRIVE --es cmd <cmd> [--es text <request>]`
 *
 * cmd: seed | pair_zane | ask <text> | probe | zane_tue | zane_wed | zane_sat | stranger | zane_week | dump
 * Deliberately has no approve/decline/revoke: the user's authority is only reachable by tapping.
 */
class DebugDriver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as DeputyApp
        val cmd = intent.getStringExtra("cmd") ?: return
        val pending = goAsync()
        app.scope.launch {
            try {
                val result: Any = when (cmd) {
                    "seed" -> app.lab.seedCalendar()
                    "pair_zane" -> app.lab.pairZane()
                    "ask" -> { app.scope.launch { app.planner.handle(intent.getStringExtra("text") ?: "") }; "started" }
                    "probe" -> { app.scope.launch { app.runProbe() }; "started" }
                    "zane_tue" -> app.lab.zaneAsks(DayOfWeek.TUESDAY, 19)
                    "zane_wed" -> app.lab.zaneAsks(DayOfWeek.WEDNESDAY, 19)
                    "zane_sat" -> app.lab.zaneAsks(DayOfWeek.SATURDAY, 14)
                    "stranger" -> app.lab.strangerAsks()
                    "zane_week" -> app.lab.zaneAsksAllWeek()
                    "dump" -> app.transcript.steps.value.joinToString("\n") { "${it.kind}: ${it.text}" }
                    else -> "unknown cmd $cmd"
                }
                Log.i(TAG, "$cmd -> $result")
            } catch (e: Exception) {
                Log.e(TAG, "$cmd failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object { const val TAG = "DeputyDriver" }
}
