package fr.astreinte.relais.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fr.astreinte.relais.core.DEFAULT_SLOTS
import fr.astreinte.relais.core.STANDARD_ID
import fr.astreinte.relais.core.Slot
import fr.astreinte.relais.core.Snapshot
import fr.astreinte.relais.sys.Perms
import java.time.LocalTime

private const val MAX_SLOTS = 6

/** Édition des créneaux types de la journée (colonnes du planning) et de leur cible par défaut. */
@Composable
fun SlotsEditorDialog(
    snap: Snapshot,
    onDismiss: () -> Unit,
    onSave: (List<Slot>) -> Unit,
) {
    val drafts = remember { mutableStateListOf<Slot>().apply { addAll(snap.settings.slots.sortedBy { it.time }) } }
    var timeEditIndex by remember { mutableIntStateOf(-1) }
    val duplicate = drafts.map { it.time }.let { it.size != it.toSet().size }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Horaires de la journée") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Chaque créneau est une colonne du planning. La « cible par défaut » s'applique tous les jours " +
                        "tant que vous n'avez rien saisi (ex. standard à 9h et 14h).",
                    style = MaterialTheme.typography.bodySmall,
                )
                drafts.forEachIndexed { i, slot ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = { timeEditIndex = i },
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                ) { Text(slot.time.hhmm(), fontWeight = FontWeight.Bold) }
                                OutlinedTextField(
                                    value = slot.label,
                                    onValueChange = { drafts[i] = slot.copy(label = it) },
                                    singleLine = true,
                                    label = { Text("Intitulé") },
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { drafts.removeAt(i) }, enabled = drafts.size > 1) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Supprimer ce créneau")
                                }
                            }
                            TargetPicker(
                                doctors = snap.doctors,
                                value = slot.defaultTargetId ?: KEY_DEFAULT,
                                onChange = { drafts[i] = slot.copy(defaultTargetId = it?.takeIf { v -> v != KEY_DEFAULT }) },
                                defaultLabel = "Aucune cible par défaut",
                                allowOff = false,
                            )
                        }
                    }
                }
                if (duplicate) {
                    Text("Deux créneaux ont la même heure.", color = MaterialTheme.colorScheme.error)
                }
                Row {
                    TextButton(
                        onClick = { drafts.add(Slot(LocalTime.of(12, 0), "Nouveau créneau", null)) },
                        enabled = drafts.size < MAX_SLOTS,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Ajouter")
                    }
                    TextButton(onClick = { drafts.clear(); drafts.addAll(DEFAULT_SLOTS) }) { Text("Rétablir l'origine") }
                }
                Text(
                    "Les saisies déjà faites à l'ancienne heure d'un créneau deviennent des « changements à heure libre ».",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !duplicate && drafts.isNotEmpty(),
                onClick = { onSave(drafts.map { it.copy(label = it.label.trim().ifEmpty { "Créneau" }) }.sortedBy { it.time }) },
            ) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )

    if (timeEditIndex in drafts.indices) {
        val i = timeEditIndex
        TimeDialog("Heure du créneau", drafts[i].time, { timeEditIndex = -1 }) { t ->
            drafts[i] = drafts[i].copy(time = t)
            timeEditIndex = -1
        }
    }
}

/** Réglages de la commande du planning par SMS. */
@Composable
fun SmsSection(
    snap: Snapshot,
    resumeTick: Int,
    onEdit: ((Snapshot) -> Snapshot) -> Unit,
) {
    val context = LocalContext.current
    val st = snap.settings
    val receiveOk = remember(resumeTick) { Perms.receiveSms(context) }
    val sendOk = remember(resumeTick) { Perms.sendSms(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    Text("Commande par SMS", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Modifier le planning par SMS")
            Text(
                "Un SMS « AST PIN … » envoyé à ce téléphone change le planning. Désactivé par défaut.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = st.smsEnabled,
            onCheckedChange = { v ->
                if (v && !receiveOk) launcher.launch(arrayOf(Manifest.permission.RECEIVE_SMS))
                onEdit { it.copy(settings = it.settings.copy(smsEnabled = v)) }
            },
        )
    }
    if (!st.smsEnabled) return

    if (!receiveOk) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Permission « SMS » manquante : aucun SMS ne sera lu.", fontWeight = FontWeight.Bold)
                Text(
                    "Si Android refuse (« paramètre restreint »), ouvrez les infos de l'app, menu ⋮ en haut à droite, " +
                        "« Autoriser les paramètres restreints », puis revenez ici.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row {
                    TextButton(onClick = { launcher.launch(arrayOf(Manifest.permission.RECEIVE_SMS)) }) { Text("Autoriser") }
                    TextButton(onClick = { context.openAppInfo() }) { Text("Infos de l'app") }
                }
            }
        }
    }

    val pinOk = st.smsPin.length >= 4
    OutlinedTextField(
        value = st.smsPin,
        onValueChange = { v -> onEdit { it.copy(settings = it.settings.copy(smsPin = v.filter { c -> !c.isWhitespace() }.take(20))) } },
        label = { Text("Code PIN (4 caractères minimum)") },
        singleLine = true,
        isError = !pinOk,
        supportingText = {
            Text(
                if (pinOk) "À écrire dans chaque SMS. Il voyage en clair par SMS : n'en faites pas un code sensible."
                else "Sans PIN, tous les SMS sont ignorés."
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Accepter les SMS des médecins enregistrés", Modifier.weight(1f))
        Switch(
            checked = st.smsAllowDoctors,
            onCheckedChange = { v -> onEdit { it.copy(settings = it.settings.copy(smsAllowDoctors = v)) } },
        )
    }
    OutlinedTextField(
        value = st.smsExtraNumbers,
        onValueChange = { v -> onEdit { it.copy(settings = it.settings.copy(smsExtraNumbers = v)) } },
        label = { Text("Autres numéros autorisés (facultatif)") },
        placeholder = { Text("06 12 34 56 78, 06 98 76 54 32") },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Phone),
        minLines = 1,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Répondre par SMS")
            Text(
                "Confirme chaque modification (« OK 12/11 18h→Martin ») ou signale l'erreur. Coûte un SMS.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = st.smsReply,
            onCheckedChange = { v ->
                if (v && !sendOk) launcher.launch(arrayOf(Manifest.permission.SEND_SMS))
                onEdit { it.copy(settings = it.settings.copy(smsReply = v)) }
            },
        )
    }
    if (st.smsReply && !sendOk) {
        Text("Permission d'envoi de SMS manquante : aucune réponse ne partira.", color = MaterialTheme.colorScheme.error)
    }

    val example = snap.doctors.firstOrNull { it.id != STANDARD_ID }?.name?.split(' ')?.last() ?: "Martin"
    val hours = st.slots.sortedBy { it.time }
    val h1 = hours.getOrNull(1)?.time?.hour ?: 12
    val h2 = hours.lastOrNull()?.time?.hour ?: 18
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Exemples de SMS", fontWeight = FontWeight.Bold)
            val code = FontFamily.Monospace
            Text("AST PIN 12/11 ${h2}=$example", fontFamily = code)
            Text("un créneau (le ${h2}h du 12/11)", style = MaterialTheme.typography.bodySmall)
            Text("AST PIN 12/11 ${h1}=Dupont ${h2}=$example", fontFamily = code)
            Text("plusieurs créneaux", style = MaterialTheme.typography.bodySmall)
            Text("AST PIN 12/11-15/11 ${h2}=$example", fontFamily = code)
            Text("une période (bornes incluses)", style = MaterialTheme.typography.bodySmall)
            Text("AST PIN 12/11,14/11 ${h2}=$example", fontFamily = code)
            Text("plusieurs jours", style = MaterialTheme.typography.bodySmall)
            Text("AST PIN demain 9=STD ${h2}=OFF", fontFamily = code)
            Text("STD = standard, OFF = aucun renvoi, demain/auj", style = MaterialTheme.typography.bodySmall)
            Text("AST PIN 12/11 RAZ", fontFamily = code)
            Text("efface la journée (retour au défaut) ; ${h2}=DEF n'efface qu'un créneau", style = MaterialTheme.typography.bodySmall)
            Text("AST PIN ?", fontFamily = code)
            Text("l'état actuel du renvoi (nécessite « Répondre par SMS »)", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun Context.openAppInfo() {
    runCatching { startActivity(Perms.appDetailsIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
