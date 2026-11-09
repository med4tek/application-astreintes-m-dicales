package fr.astreinte.relais.sys

import android.content.Context
import android.util.Log
import fr.astreinte.relais.core.ApplyState
import fr.astreinte.relais.core.Codes
import fr.astreinte.relais.core.LogEntry
import fr.astreinte.relais.core.Planner
import fr.astreinte.relais.data.Store
import java.time.Instant
import java.time.ZoneId

/**
 * Cerveau de l'app : compare "qui est d'astreinte maintenant" avec "ce qui est réellement
 * programmé sur le téléphone" et envoie le code de renvoi si nécessaire.
 *
 * Tout se passe sur le thread principal (récepteurs, UI, callbacks USSD).
 */
object Relay {
    private const val TAG = "Relay"
    private const val MAX_ATTEMPTS = 5
    private const val RETRY_DELAY_MS = 60_000L
    private const val STALE_AFTER_MS = 6 * 60 * 60 * 1000L

    enum class Trigger(val label: String, val isRetry: Boolean = false) {
        ALARM("changement d'astreinte"),
        RETRY("nouvel essai", true),
        SYSTEM("redémarrage / changement d'heure"),
        APP("ouverture de l'app"),
        EDIT("modification du planning"),
        MANUAL("action manuelle"),
    }

    private var busy = false
    private var rerunWanted = false

    /**
     * Point d'entrée unique.
     *  - réarme toujours l'alarme du prochain changement ;
     *  - envoie le code si [force], ou si la situation voulue diffère de la dernière appliquée,
     *    ou si le dernier essai a échoué ;
     *  - [onDone] est appelé dès que le travail immédiat est fini (sert à libérer le récepteur).
     */
    fun reconcile(context: Context, trigger: Trigger, force: Boolean = false, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        Store.init(app)
        val snap = Store.snapshot.value

        if (!snap.settings.enabled) {
            Alarms.cancelAll(app)
            onDone()
            return
        }
        Alarms.scheduleNext(app, snap)

        // Une alarme peut sonner quelques ms avant l'heure pile : on garde 5 s de marge
        val now = Instant.now().let { if (trigger == Trigger.ALARM || trigger.isRetry) it.plusSeconds(5) else it }
        val current = Planner.currentShift(snap, now, ZoneId.systemDefault())
        if (current == null) {
            onDone()
            return
        }

        val doctor = snap.doctor(current.doctorId)
        val label = if (current.doctorId == null) "Aucun renvoi" else doctor?.name ?: "médecin supprimé"
        val command = if (current.doctorId != null && doctor == null) null else Codes.forDoctor(doctor)
        if (command == null) {
            val msg = "Numéro de $label invalide : corrigez la fiche du médecin."
            record(app, trigger, "?", false, msg, attempts = MAX_ATTEMPTS)
            Notifier.failure(app, "", "Renvoi NON appliqué", msg, final = true)
            onDone()
            return
        }

        val st = snap.state
        // À chaque alarme on réaffirme le renvoi s'il date de plus de 6 h (sécurité), sans rappeler
        // inutilement quand deux créneaux consécutifs visent la même cible.
        val stale = trigger == Trigger.ALARM && System.currentTimeMillis() - st.lastAt > STALE_AFTER_MS
        val needed = force || stale || st.lastCommand != command || !st.lastOk
        if (!needed) {
            onDone()
            return
        }
        send(app, command, label, trigger, onDone)
    }

    /** Envoi direct d'un code arbitraire (boutons de test). Passe par le même verrou et le même journal. */
    fun sendNow(context: Context, command: String, label: String, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        Store.init(app)
        send(app, command, label, Trigger.MANUAL, onDone)
    }

    private fun send(app: Context, command: String, label: String, trigger: Trigger, onDone: () -> Unit) {
        if (busy) {
            // Une session USSD est déjà en cours : on réévaluera juste après
            rerunWanted = true
            onDone()
            return
        }
        busy = true
        var released = false
        fun release() {
            if (!released) {
                released = true
                onDone()
            }
        }
        // Un récepteur ne doit pas rester bloqué > 10 s ; le résultat sera enregistré quand même
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ release() }, 9_000)

        Log.i(TAG, "Envoi $command ($label) – ${trigger.label}")
        Sender.send(app, command, Store.snapshot.value.settings) { result ->
            val ok = result is Ussd.Result.Ok
            val message = when (result) {
                is Ussd.Result.Ok -> result.text.ifBlank { "Code envoyé (réponse vide)" }
                is Ussd.Result.Fail -> result.reason
            }
            onResult(app, command, label, trigger, ok, message)
            busy = false
            release()
            if (rerunWanted) {
                rerunWanted = false
                reconcile(app, Trigger.APP)
            }
        }
    }

    private fun onResult(
        app: Context, command: String, label: String, trigger: Trigger, ok: Boolean, message: String,
    ) {
        val previousAttempts = Store.snapshot.value.state.attempts
        val attempts = when {
            ok -> 0
            trigger.isRetry -> previousAttempts + 1
            else -> 1
        }
        record(app, trigger, command, ok, message, attempts)

        val what = if (command == Codes.DEACTIVATE) "Renvoi désactivé" else "Renvoi vers $label"
        when {
            ok -> {
                Alarms.cancelRetry(app)
                if (trigger != Trigger.MANUAL) Notifier.success(app, what, message)
            }
            attempts < MAX_ATTEMPTS -> {
                Alarms.scheduleRetry(app, RETRY_DELAY_MS)
                Notifier.failure(
                    app, command, "$what : échec ($attempts/$MAX_ATTEMPTS)",
                    "$message\nNouvel essai dans 1 minute.", final = false
                )
            }
            else -> {
                Alarms.cancelRetry(app)
                Notifier.failure(
                    app, command, "$what : ÉCHEC DÉFINITIF",
                    "$message\nLe renvoi n'est PAS en place. Appuyez pour composer le code à la main.",
                    final = true
                )
            }
        }
    }

    private fun record(
        app: Context, trigger: Trigger, command: String, ok: Boolean, message: String, attempts: Int,
    ) {
        val now = System.currentTimeMillis()
        Store.update { s ->
            s.copy(
                state = ApplyState(command, ok, now, message, attempts),
                log = (listOf(LogEntry(now, trigger.label, command, ok, message)) + s.log).take(100),
            )
        }
    }
}
