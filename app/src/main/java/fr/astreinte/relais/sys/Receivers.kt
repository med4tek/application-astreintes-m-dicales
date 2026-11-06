package fr.astreinte.relais.sys

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

/** Alarmes internes : changement d'astreinte et nouvel essai. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val trigger = when (intent.action) {
            Alarms.ACTION_SHIFT -> Relay.Trigger.ALARM
            Alarms.ACTION_RETRY -> Relay.Trigger.RETRY
            else -> return
        }
        runGuarded(context) { done -> Relay.reconcile(context, trigger, force = true, onDone = done) }
    }
}

/** Redémarrage, mise à jour de l'app, changement d'heure / de fuseau : on réarme tout. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runGuarded(context) { done -> Relay.reconcile(context, Relay.Trigger.SYSTEM, force = false, onDone = done) }
    }
}

/**
 * Garde le CPU éveillé et le récepteur "vivant" (goAsync) le temps que la session USSD réponde.
 */
private fun BroadcastReceiver.runGuarded(context: Context, work: (done: () -> Unit) -> Unit) {
    val pending = goAsync()
    val wl = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "astreinte:relay")
        .apply { acquire(30_000) }
    var finished = false
    val done = {
        if (!finished) {
            finished = true
            if (wl.isHeld) wl.release()
            pending.finish()
        }
    }
    try {
        work(done)
    } catch (e: Exception) {
        done()
        throw e
    }
}
