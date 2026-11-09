package fr.astreinte.relais.ui

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fr.astreinte.relais.core.CellEdit
import fr.astreinte.relais.core.Codes
import fr.astreinte.relais.core.Snapshot
import fr.astreinte.relais.core.SyncCodec
import fr.astreinte.relais.core.SyncLogic
import fr.astreinte.relais.data.Store
import fr.astreinte.relais.sys.Perms
import fr.astreinte.relais.sys.SyncClient
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Synchronisation par SMS avec le téléphone relais (mode consultation). */
@Composable
fun SyncScreen(
    snap: Snapshot,
    resumeTick: Int,
    onEdit: ((Snapshot) -> Snapshot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val st = snap.settings
    val sendOk = remember(resumeTick) { Perms.sendSms(context) }
    val receiveOk = remember(resumeTick) { Perms.receiveSms(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    var confirmPull by remember { mutableStateOf(false) }

    // Une demande restée sans réponse est abandonnée au bout de 10 minutes
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            val current = Store.snapshot.value
            if (SyncLogic.expire(current, now) !== current) Store.update { SyncLogic.expire(it, now) }
            delay(5_000)
        }
    }

    val changes = remember(snap.shifts, snap.baseline) { SyncCodec.diff(snap.shifts, snap.baseline) }
    val pending = snap.sync.pending
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE) }
    val configured = Codes.normalizePhone(st.relayNumber) != null && st.smsPin.length >= 4
    fun toast(msg: String?) { if (msg != null) Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Synchronisation avec le relais", style = MaterialTheme.typography.titleLarge)
        Text(
            "Le téléphone relais fait foi. Ici vous pouvez récupérer son planning, le modifier, puis lui renvoyer vos " +
                "modifications : il répond « à jour » quand c'est enregistré. Tout passe par de vrais SMS.",
            style = MaterialTheme.typography.bodyMedium,
        )

        // ------------------------------------------------------------- réglages de liaison
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Liaison", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = st.relayNumber,
                    onValueChange = { v -> onEdit { it.copy(settings = it.settings.copy(relayNumber = v)) } },
                    label = { Text("Numéro du téléphone relais") },
                    singleLine = true,
                    isError = st.relayNumber.isNotBlank() && Codes.normalizePhone(st.relayNumber) == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = st.smsPin,
                    onValueChange = { v -> onEdit { it.copy(settings = it.settings.copy(smsPin = v.filter { c -> !c.isWhitespace() }.take(20))) } },
                    label = { Text("Code PIN (le même que sur le relais)") },
                    singleLine = true,
                    isError = st.smsPin.isNotEmpty() && st.smsPin.length < 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!sendOk || !receiveOk) {
                    Text(
                        "Cette application doit pouvoir envoyer et recevoir des SMS.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    )
                    Row {
                        TextButton(onClick = { launcher.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)) }) {
                            Text("Autoriser les SMS")
                        }
                        TextButton(onClick = { context.startActivity(Perms.appDetailsIntent(context).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                            Text("Infos de l'app")
                        }
                    }
                    Text(
                        "Si Android refuse (« paramètre restreint ») : infos de l'app, menu ⋮, « Autoriser les paramètres restreints ».",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "Votre numéro doit être celui d'un médecin enregistré sur le relais (ou ajouté dans ses « Autres numéros autorisés »).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // ------------------------------------------------------------- état
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("État", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (snap.sync.lastPullAt == 0L) "Planning jamais reçu du relais."
                    else "Planning reçu du relais le ${fmt.format(Date(snap.sync.lastPullAt))}.",
                )
                if (snap.sync.lastPushAt != 0L) Text("Dernier envoi confirmé le ${fmt.format(Date(snap.sync.lastPushAt))}.")
                Text(
                    if (changes.isEmpty()) "✓ Aucune modification en attente d'envoi."
                    else "${changes.size} modification(s) pas encore envoyée(s) au relais.",
                    fontWeight = FontWeight.Bold,
                    color = if (changes.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                )
                if (pending != null) {
                    val progress = when (pending.kind) {
                        "PULL" -> if (pending.total > 0) "${pending.chunks.size}/${pending.total} SMS reçus" else "en attente de la réponse du relais…"
                        else -> "${pending.chunks.size}/${pending.total} accusé(s) reçu(s) du relais"
                    }
                    Text(
                        (if (pending.kind == "PULL") "Demande du planning : " else "Envoi des modifications : ") + progress,
                        fontWeight = FontWeight.Bold,
                    )
                    if (pending.total > 0) {
                        LinearProgressIndicator(progress = { pending.chunks.size.toFloat() / pending.total }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = { onEdit { it.copy(sync = it.sync.copy(pending = null, lastResult = "Attente abandonnée.")) } }) {
                        Text("Abandonner l'attente")
                    }
                }
                if (snap.sync.lastResult.isNotBlank()) {
                    val bad = snap.sync.lastResult.let { it.startsWith("Pas de réponse") || it.contains("refusé") || it.contains("illisible") || it.contains("pas pu") }
                    Text(
                        snap.sync.lastResult,
                        color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        // ------------------------------------------------------------- actions
        Button(
            enabled = configured && sendOk && pending == null,
            onClick = {
                if (changes.isNotEmpty()) confirmPull = true else toast(SyncClient.requestPull(context))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Demander le planning au relais") }

        Button(
            enabled = configured && sendOk && pending == null && changes.isNotEmpty(),
            onClick = { toast(SyncClient.pushChanges(context)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Envoyer mes modifications au relais") }

        if (snap.sync.lastPullAt == 0L && changes.isNotEmpty()) {
            Text(
                "Conseil : commencez par demander le planning au relais, sinon tout ce que vous avez saisi sera envoyé comme « modification ».",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }

        // ------------------------------------------------------------- détail des modifications
        if (changes.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("À envoyer", style = MaterialTheme.typography.titleMedium)
                    changes.take(30).forEach { c ->
                        val what = when (val e = c.edit) {
                            CellEdit.Clear -> "retour au défaut"
                            is CellEdit.Set -> if (e.doctorId == null) "aucun renvoi" else snap.doctor(e.doctorId)?.name ?: "?"
                        }
                        Text("${c.date.shortLabel()} ${c.time.shortHour()} → $what", style = MaterialTheme.typography.bodySmall)
                    }
                    if (changes.size > 30) Text("… et ${changes.size - 30} autre(s)", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (confirmPull) {
        AlertDialog(
            onDismissRequest = { confirmPull = false },
            title = { Text("Écraser vos modifications ?") },
            text = {
                Text("Vous avez ${changes.size} modification(s) non envoyée(s). Récupérer le planning du relais les remplacera. Envoyez-les d'abord si vous voulez les garder.")
            },
            confirmButton = {
                Button(onClick = { confirmPull = false; toast(SyncClient.requestPull(context)) }) { Text("Récupérer quand même") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmPull = false }) { Text("Annuler") } },
        )
    }
}
