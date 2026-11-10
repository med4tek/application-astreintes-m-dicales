package fr.astreinte.relais.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.astreinte.relais.core.Codes
import fr.astreinte.relais.core.Planner
import fr.astreinte.relais.core.Snapshot
import fr.astreinte.relais.sys.Perms
import fr.astreinte.relais.sys.Relay
import fr.astreinte.relais.sys.Sender
import fr.astreinte.relais.sys.Ussd
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

private data class SimChoice(val subscriptionId: Int, val label: String)

@Composable
fun SettingsScreen(
    snap: Snapshot,
    resumeTick: Int,
    onEdit: ((Snapshot) -> Snapshot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // `resumeTick` change quand on revient des écrans système : on relit alors l'état des permissions
    val callOk = remember(resumeTick) { Perms.call(context) }
    val phoneStateOk = remember(resumeTick) { Perms.phoneState(context) }
    val notifOk = remember(resumeTick) { Perms.notifications(context) }
    val exactOk = remember(resumeTick) { Perms.exactAlarms(context) }
    val batteryOk = remember(resumeTick) { Perms.batteryUnrestricted(context) }

    // Au retour de la boîte de dialogue, l'activité repasse par onResume => resumeTick change
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    var confirmEnable by remember { mutableStateOf(false) }
    var confirmDisableForwarding by remember { mutableStateOf(false) }
    var queryResult by remember { mutableStateOf<String?>(null) }
    var queryRunning by remember { mutableStateOf(false) }
    var showDefaultTime by remember { mutableStateOf(false) }
    var showSlots by remember { mutableStateOf(false) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModeCard(snap, onEdit)

        if (snap.settings.isRelay) {
            // --- Interrupteur principal ---
            Card(colors = CardDefaults.cardColors(
                containerColor = if (snap.settings.enabled) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Bascule automatique", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            if (snap.settings.enabled) "Active : le renvoi suit le planning."
                            else "Désactivée : rien n'est envoyé à l'opérateur.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = snap.settings.enabled,
                        onCheckedChange = { wanted ->
                            if (!wanted) {
                                onEdit { it.copy(settings = it.settings.copy(enabled = false)) }
                            } else if (!Perms.call(context)) {
                                permLauncher.launch(requiredPermissions())
                            } else {
                                confirmEnable = true
                            }
                        },
                    )
                }
            }

            // --- Autorisations ---
            Text("Autorisations", style = MaterialTheme.typography.titleMedium)
            PermRow("Téléphone (envoyer le code de renvoi)", callOk, required = true) {
                permLauncher.launch(requiredPermissions())
            }
            PermRow("Notifications (confirmation / alerte d'échec)", notifOk, required = true) {
                permLauncher.launch(requiredPermissions())
            }
            PermRow("Alarmes exactes (basculer à la minute près)", exactOk, required = true) {
                context.startSafely(Perms.exactAlarmSettingsIntent(context))
            }
            PermRow("Batterie : ne pas restreindre l'app", batteryOk, required = false) {
                context.startSafely(Perms.batteryIntent(context))
            }
            TextButton(onClick = { context.startSafely(Perms.appDetailsIntent(context)) }) {
                Text("Ouvrir les réglages Android de l'app")
            }

            HorizontalDivider()

            // --- Préférences ---
            Text("Préférences", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { showSlots = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Horaires de la journée : " + snap.settings.slots.sortedBy { it.time }.joinToString(" ") { it.time.shortHour() })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Heure par défaut d'un changement à heure libre", Modifier.weight(1f))
                OutlinedButton(onClick = { showDefaultTime = true }) { Text(snap.settings.defaultTime.hhmm()) }
            }
            val sims = remember(resumeTick, phoneStateOk) { listSims(context) }
            if (sims.size > 1) {
                SimPicker(sims, snap.settings.subscriptionId) { id -> onEdit { it.copy(settings = it.settings.copy(subscriptionId = id)) } }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Envoi USSD direct (expérimental)")
                    Text(
                        "À essayer seulement si la méthode par défaut (composition par le téléphone) ne marche pas. " +
                            "Beaucoup de téléphones répondent « échec réseau/opérateur (code -1) » pour les codes de renvoi.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = snap.settings.useDirectUssd,
                    onCheckedChange = { v -> onEdit { it.copy(settings = it.settings.copy(useDirectUssd = v)) } },
                )
            }

            HorizontalDivider()

            SmsSection(snap, resumeTick, onEdit)

            HorizontalDivider()

            MessagesSection(snap, resumeTick, onEdit)

            HorizontalDivider()

            // --- Outils ---
            Text("Outils de vérification", style = MaterialTheme.typography.titleMedium)
            Text(
                "À essayer une fois avant de compter sur l'automatisme. Le téléphone compose le code et Android affiche lui-même la réponse de l'opérateur dans une fenêtre.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                enabled = callOk && !queryRunning,
                onClick = {
                    queryRunning = true
                    Sender.send(context, Codes.QUERY, snap.settings) { r ->
                        queryRunning = false
                        queryResult = when (r) {
                            is Ussd.Result.Ok -> r.text.ifBlank { "(réponse vide)" }
                            is Ussd.Result.Fail -> "Échec : ${r.reason}"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (queryRunning) "Interrogation…" else "Vérifier le renvoi actuellement en place") }

            val current = Planner.currentShift(snap, Instant.now(), ZoneId.systemDefault())
            OutlinedButton(
                enabled = callOk && current != null,
                onClick = { Relay.reconcile(context, Relay.Trigger.MANUAL, force = true) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Appliquer maintenant l'astreinte en cours") }

            OutlinedButton(
                enabled = callOk,
                onClick = { confirmDisableForwarding = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Désactiver le renvoi maintenant") }

            HorizontalDivider()

            // --- Journal ---
            Text("Journal (les 20 derniers envois)", style = MaterialTheme.typography.titleMedium)
            if (snap.log.isEmpty()) Text("Rien pour l'instant.", style = MaterialTheme.typography.bodySmall)
            val fmt = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE) }
            snap.log.take(20).forEach { e ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        if (e.ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                        contentDescription = if (e.ok) "Réussi" else "Échec",
                        tint = if (e.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("${fmt.format(Date(e.at))} · ${e.trigger}", style = MaterialTheme.typography.labelMedium)
                        Text(e.command, fontWeight = FontWeight.Bold)
                        if (e.message.isNotBlank()) Text(e.message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            ViewerSettings(snap, resumeTick, callOk = notifOk, permLauncher = { permLauncher.launch(it) })
        }

        HorizontalDivider()
        HistorySection(snap, onEdit)
    }

    if (confirmEnable) {
        val current = Planner.currentShift(snap, Instant.now(), ZoneId.systemDefault())
        val who = current?.let { snap.doctor(it.doctorId)?.name ?: "aucun renvoi" }
        AlertDialog(
            onDismissRequest = { confirmEnable = false },
            title = { Text("Activer la bascule automatique ?") },
            text = {
                Text(
                    if (who != null) "L'astreinte en cours est « $who » : le renvoi va être appliqué TOUT DE SUITE, puis à chaque changement du planning."
                    else "Aucune astreinte n'est en cours : rien ne sera envoyé avant le prochain changement du planning."
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmEnable = false
                    onEdit { it.copy(settings = it.settings.copy(enabled = true)) }
                }) { Text("Activer") }
            },
            dismissButton = { TextButton(onClick = { confirmEnable = false }) { Text("Annuler") } },
        )
    }
    if (confirmDisableForwarding) {
        AlertDialog(
            onDismissRequest = { confirmDisableForwarding = false },
            title = { Text("Désactiver le renvoi ?") },
            text = {
                Text(
                    "Les appels ne seront plus renvoyés (code ${Codes.DEACTIVATE})." +
                        if (snap.settings.enabled) " Attention : la bascule automatique est active, elle réappliquera le renvoi au prochain changement ou à la prochaine ouverture de l'app." else ""
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmDisableForwarding = false
                    Relay.sendNow(context, Codes.DEACTIVATE, "Aucun renvoi")
                }) { Text("Désactiver") }
            },
            dismissButton = { TextButton(onClick = { confirmDisableForwarding = false }) { Text("Annuler") } },
        )
    }
    queryResult?.let { text ->
        AlertDialog(
            onDismissRequest = { queryResult = null },
            title = { Text("Vérification du renvoi") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { queryResult = null }) { Text("OK") } },
        )
    }
    if (showSlots) {
        SlotsEditorDialog(
            snap = snap,
            onDismiss = { showSlots = false },
            onSave = { slots ->
                onEdit { it.copy(settings = it.settings.copy(slots = slots)) }
                showSlots = false
            },
        )
    }
    if (showDefaultTime) {
        TimeDialog("Heure de relève par défaut", snap.settings.defaultTime, { showDefaultTime = false }) { t ->
            onEdit { it.copy(settings = it.settings.copy(defaultTime = t)) }
            showDefaultTime = false
        }
    }
}

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.CALL_PHONE)
    add(Manifest.permission.READ_PHONE_STATE)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@Composable
private fun PermRow(label: String, granted: Boolean, required: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = when {
                granted -> MaterialTheme.colorScheme.primary
                required -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.tertiary
            },
        )
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.weight(1f))
        if (!granted) TextButton(onClick = onFix) { Text("Autoriser") }
    }
}

@Composable
private fun SimPicker(sims: List<SimChoice>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = sims.firstOrNull { it.subscriptionId == selected }?.label ?: "SIM par défaut pour les appels"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("SIM utilisée", Modifier.weight(1f))
        androidx.compose.foundation.layout.Box {
            OutlinedButton(onClick = { open = true }) { Text(label) }
            androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("SIM par défaut pour les appels") },
                    onClick = { onSelect(-1); open = false },
                )
                sims.forEach { s ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(s.label) },
                        onClick = { onSelect(s.subscriptionId); open = false },
                    )
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun listSims(context: Context): List<SimChoice> {
    if (!Perms.phoneState(context)) return emptyList()
    return try {
        context.getSystemService(SubscriptionManager::class.java)
            ?.activeSubscriptionInfoList.orEmpty()
            .map { SimChoice(it.subscriptionId, "SIM ${it.simSlotIndex + 1} · ${it.displayName}") }
    } catch (e: SecurityException) {
        emptyList()
    }
}

private fun Context.startSafely(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        // Écran indisponible sur ce téléphone : on retombe sur la fiche de l'app
        runCatching { startActivity(Perms.appDetailsIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** Réglages d'un téléphone en mode consultation : rien d'automatique, seulement l'état des autorisations. */
@Composable
private fun ViewerSettings(
    snap: Snapshot,
    resumeTick: Int,
    callOk: Boolean,
    permLauncher: (Array<String>) -> Unit,
) {
    val context = LocalContext.current
    val sendOk = remember(resumeTick) { Perms.sendSms(context) }
    val receiveOk = remember(resumeTick) { Perms.receiveSms(context) }
    Text("Mode consultation", style = MaterialTheme.typography.titleMedium)
    Text(
        "Ce téléphone n'applique aucun renvoi d'appel et n'envoie aucun message automatique. " +
            "Utilisez l'onglet « Synchro » pour échanger le planning avec le relais.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text("Autorisations", style = MaterialTheme.typography.titleMedium)
    PermRow("Envoyer des SMS (demandes au relais)", sendOk, required = true) {
        permLauncher(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS))
    }
    PermRow("Recevoir des SMS (réponses du relais)", receiveOk, required = true) {
        permLauncher(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS))
    }
    PermRow("Notifications (« planning à jour »)", callOk, required = false) {
        if (Build.VERSION.SDK_INT >= 33) permLauncher(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }
    TextButton(onClick = { context.startSafely(Perms.appDetailsIntent(context)) }) {
        Text("Ouvrir les réglages Android de l'app")
    }
}
