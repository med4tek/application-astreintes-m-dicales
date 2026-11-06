package fr.astreinte.relais.sys

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import fr.astreinte.relais.core.Settings

/**
 * Envoie un code de renvoi (**21*…#, ##21#, *#21#) avec la méthode choisie dans les réglages.
 *
 *  - Par défaut : le téléphone COMPOSE le code (TelecomManager.placeCall), exactement comme le
 *    composeur quand on tape le code à la main. C'est la voie qui marche pour les codes de renvoi.
 *    L'app ne peut pas lire la réponse : Android l'affiche dans une fenêtre système.
 *  - Option : API USSD directe (lit la réponse, mais souvent refusée pour ces codes -> code -1).
 *
 * [callback] est appelé une fois, sur le thread principal.
 */
object Sender {

    fun send(context: Context, code: String, settings: Settings, callback: (Ussd.Result) -> Unit) {
        if (settings.useDirectUssd) {
            Ussd.send(context, code, settings.subscriptionId, callback = callback)
        } else {
            callback(placeCall(context, code, settings.subscriptionId))
        }
    }

    @SuppressLint("MissingPermission")
    private fun placeCall(context: Context, code: String, subscriptionId: Int): Ussd.Result {
        if (!Perms.has(context, Manifest.permission.CALL_PHONE)) {
            return Ussd.Result.Fail("Permission « Téléphone / appels » refusée")
        }
        // '#' est encodé en %23 par Uri.fromParts : c'est ce qu'attend le composeur
        val uri = Uri.fromParts("tel", code, null)
        val ok = Ussd.Result.Ok("Code $code composé par le téléphone. La réponse de l'opérateur s'affiche dans la fenêtre du système (non lue par l'app).")

        // 1) Appel direct au service Télécom : non soumis aux restrictions de lancement d'activité en arrière-plan
        try {
            val extras = Bundle()
            phoneAccountFor(context, subscriptionId)?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
            context.getSystemService(TelecomManager::class.java).placeCall(uri, extras)
            return ok
        } catch (e: Exception) {
            // 2) Repli : intent d'appel classique (peut être bloqué si l'app n'est pas au premier plan)
            return try {
                context.startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ok
            } catch (e2: Exception) {
                Ussd.Result.Fail("Impossible de composer le code : ${e.message ?: e2.message}")
            }
        }
    }

    /** Compte d'appel (SIM) correspondant à l'abonnement choisi, ou null = SIM par défaut. */
    @SuppressLint("MissingPermission")
    private fun phoneAccountFor(context: Context, subscriptionId: Int): PhoneAccountHandle? {
        if (subscriptionId < 0 || Build.VERSION.SDK_INT < 30 || !Perms.phoneState(context)) return null
        return try {
            val tm = context.getSystemService(TelephonyManager::class.java)
            context.getSystemService(TelecomManager::class.java).callCapablePhoneAccounts
                .firstOrNull { tm.getSubscriptionId(it) == subscriptionId }
        } catch (e: Exception) {
            null
        }
    }
}
