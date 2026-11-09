package fr.astreinte.relais.sys

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import fr.astreinte.relais.MainActivity
import fr.astreinte.relais.R

object Notifier {
    private const val CH_INFO = "relay_info"
    private const val CH_ALERT = "relay_alert"
    private const val ID_INFO = 10
    private const val ID_ALERT = 11
    private const val ID_SMS = 12

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_INFO, "Renvoi appliqué", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Confirmation à chaque changement d'astreinte"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Échec du renvoi", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerte quand le renvoi d'appel n'a pas pu être appliqué"
            }
        )
    }

    fun success(context: Context, title: String, text: String) {
        // Le renvoi a fini par passer : on retire l'alerte d'échec éventuelle
        cancelAlert(context)
        post(context, ID_INFO, CH_INFO, title, text, openApp(context), null)
    }

    /** [final] = true quand les essais automatiques sont épuisés : alerte forte + bouton manuel. */
    fun failure(context: Context, command: String, title: String, text: String, final: Boolean) {
        val dial = PendingIntent.getActivity(
            context, 3,
            Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", command, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        post(
            context, ID_ALERT, if (final) CH_ALERT else CH_INFO, title, text, openApp(context),
            if (final && command.isNotBlank()) NotificationCompat.Action(0, "Composer $command", dial) else null,
            ongoing = final
        )
    }

    /** Information ponctuelle (ex. planning modifié par SMS) ; n'efface pas une alerte d'échec. */
    fun info(context: Context, title: String, text: String) =
        post(context, ID_SMS, CH_INFO, title, text, openApp(context), null)

    fun cancelAlert(context: Context) = NotificationManagerCompat.from(context).cancel(ID_ALERT)

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 2, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun post(
        context: Context,
        id: Int,
        channel: String,
        title: String,
        text: String,
        content: PendingIntent,
        action: NotificationCompat.Action?,
        ongoing: Boolean = false,
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        createChannels(context)
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(content)
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            .setPriority(if (channel == CH_ALERT) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .apply { if (action != null) addAction(action) }
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }
}
