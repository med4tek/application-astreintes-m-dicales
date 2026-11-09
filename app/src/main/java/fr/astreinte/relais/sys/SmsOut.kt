package fr.astreinte.relais.sys

import android.content.Context
import android.telephony.SmsManager
import android.util.Log

/** Envoi de SMS (permission « envoyer des SMS » requise). */
object SmsOut {
    private const val TAG = "SmsOut"

    /** true si le système a accepté l'envoi (pas une preuve de livraison). */
    fun send(app: Context, to: String, text: String): Boolean {
        if (to.isBlank() || text.isBlank() || !Perms.sendSms(app)) return false
        return try {
            val sms = app.getSystemService(SmsManager::class.java)
            sms.sendMultipartTextMessage(to, null, sms.divideMessage(text), null, null)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Envoi SMS impossible", e)
            false
        }
    }
}
