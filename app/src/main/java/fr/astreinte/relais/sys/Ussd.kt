package fr.astreinte.relais.sys

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/** Envoi d'un code USSD/MMI (ex. **21*0612345678#) sans ouvrir le composeur. */
object Ussd {
    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Fail(val reason: String) : Result()
    }

    /**
     * Envoie [code]. [callback] est TOUJOURS appelé exactement une fois, sur le thread principal
     * (réponse de l'opérateur, échec, ou délai dépassé).
     */
    fun send(
        context: Context,
        code: String,
        subscriptionId: Int,
        timeoutMs: Long = 20_000,
        callback: (Result) -> Unit,
    ) {
        val main = Handler(Looper.getMainLooper())
        val done = AtomicBoolean(false)
        fun finish(r: Result) {
            if (done.compareAndSet(false, true)) main.post { callback(r) }
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            finish(Result.Fail("Permission « Téléphone / appels » refusée"))
            return
        }

        try {
            var tm = context.getSystemService(TelephonyManager::class.java)
                ?: return finish(Result.Fail("Pas de service téléphonie sur cet appareil"))
            if (subscriptionId >= 0) tm = tm.createForSubscriptionId(subscriptionId)

            tm.sendUssdRequest(code, object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(
                    telephonyManager: TelephonyManager,
                    request: String,
                    response: CharSequence,
                ) = finish(Result.Ok(response.toString().trim()))

                override fun onReceiveUssdResponseFailed(
                    telephonyManager: TelephonyManager,
                    request: String,
                    failureCode: Int,
                ) = finish(Result.Fail(describeFailure(failureCode)))
            }, main)

            main.postDelayed({
                finish(Result.Fail("Pas de réponse de l'opérateur après ${timeoutMs / 1000} s"))
            }, timeoutMs)
        } catch (e: SecurityException) {
            finish(Result.Fail("Permission refusée : ${e.message}"))
        } catch (e: Exception) {
            finish(Result.Fail("Erreur : ${e.message}"))
        }
    }

    private fun describeFailure(code: Int) = when (code) {
        TelephonyManager.USSD_RETURN_FAILURE -> "Échec côté réseau/opérateur (code $code)"
        TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL -> "Service USSD indisponible (code $code) : pas de réseau ?"
        else -> "Échec USSD (code $code)"
    }
}
