package fr.astreinte.relais.sys

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import fr.astreinte.relais.core.Messages
import fr.astreinte.relais.core.Planner
import fr.astreinte.relais.core.Snapshot
import java.time.Instant
import java.time.ZoneId

/** Une seule alarme "prochain changement" + une alarme "nouvel essai". */
object Alarms {
    const val ACTION_SHIFT = "fr.astreinte.relais.SHIFT"
    const val ACTION_RETRY = "fr.astreinte.relais.RETRY"
    private const val RC_SHIFT = 1
    private const val RC_RETRY = 2
    private const val TAG = "Alarms"

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** (Re)programme l'alarme du prochain changement d'astreinte, ou l'annule s'il n'y en a plus. */
    fun scheduleNext(context: Context, snap: Snapshot) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context, RC_SHIFT, ACTION_SHIFT)
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val nextShift = Planner.nextShift(snap, now, zone)?.let { Planner.startOf(it, zone) }
        // Le réveil sert aussi aux messages automatiques (2 s de marge : l'état du renvoi doit être à jour)
        val nextMessage = Messages.nextDue(snap, now, zone)?.plusSeconds(2)
        val at = listOfNotNull(nextShift, nextMessage).minOrNull()
        if (at == null) {
            am.cancel(pi)
            return
        }
        set(am, at.toEpochMilli(), pi)
        Log.i(TAG, "Prochain réveil programmé : $at")
    }

    fun scheduleRetry(context: Context, delayMs: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        set(am, System.currentTimeMillis() + delayMs, pending(context, RC_RETRY, ACTION_RETRY))
    }

    fun cancelRetry(context: Context) {
        context.getSystemService(AlarmManager::class.java)
            .cancel(pending(context, RC_RETRY, ACTION_RETRY))
    }

    fun cancelAll(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(pending(context, RC_SHIFT, ACTION_SHIFT))
        am.cancel(pending(context, RC_RETRY, ACTION_RETRY))
    }

    private fun set(am: AlarmManager, atMillis: Long, pi: PendingIntent) {
        if (canScheduleExactSafe(am)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        } else {
            // Permission "alarmes exactes" absente : on se rabat sur une alarme approximative
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    private fun canScheduleExactSafe(am: AlarmManager) =
        Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

    private fun pending(context: Context, requestCode: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
