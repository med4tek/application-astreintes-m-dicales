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
import fr.astreinte.relais.core.SmsAction
import fr.astreinte.relais.core.SmsCommands
import fr.astreinte.relais.core.SmsParse
import fr.astreinte.relais.core.SyncCodec
import fr.astreinte.relais.core.Mode
import fr.astreinte.relais.core.Doctor
import fr.astreinte.relais.core.MessageRule
import fr.astreinte.relais.core.DEFAULT_MESSAGE_RULES
import fr.astreinte.relais.core.Messages
import java.util.UUID
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.material3.RadioButton
import fr.astreinte.relais.sys.Perms
import fr.astreinte.relais.sys.SmsHandler
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.util.Date
import java.util.Locale

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

    // ------------------------------------------------------------------ diagnostic
    var testText by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE) }
    val allowed = SmsHandler.allowedNumbers(snap)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Diagnostic", fontWeight = FontWeight.Bold)
            Text(
                if (snap.state.lastSmsAt == 0L) "Aucun SMS n'a encore été reçu par l'app depuis l'installation."
                else "Dernier SMS vu par l'app : ${fmt.format(Date(snap.state.lastSmsAt))} (" +
                    (if (snap.state.lastSmsWasCommand) "commande AST" else "autre SMS, ignoré") + ")",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Permission de lecture des SMS : " + if (receiveOk) "accordée" else "REFUSÉE", style = MaterialTheme.typography.bodySmall)
            Text(
                "Numéros autorisés à commander : " + if (allowed.isEmpty()) "aucun (ajoutez-en ci-dessus)" else allowed.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = testText,
                onValueChange = { testText = it; testResult = null },
                label = { Text("Collez ici un SMS pour le simuler") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { testResult = simulate(snap, testText) }, enabled = testText.isNotBlank()) {
                Text("Simuler (rien n'est modifié)")
            }
            testResult?.let { Text(it, fontWeight = FontWeight.Bold) }
            Text(
                "Si le dernier SMS vu ne change pas quand vous envoyez une commande, le SMS n'arrive pas jusqu'à l'app : " +
                    "permission refusée, ou message envoyé en « chat » (RCS) au lieu d'un vrai SMS.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
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
            Text("AST PIN ? demain", fontFamily = code)
            Text("le planning de ces jours en réponse (7 jours max)", style = MaterialTheme.typography.bodySmall)
            Text("AST PIN ? 12/11-14/11", fontFamily = code)
            Text("idem sur une période ; « ? semaine » = les 7 prochains jours", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun Context.openAppInfo() {
    runCatching { startActivity(Perms.appDetailsIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Dit ce que l'app comprendrait d'un SMS, sans rien modifier (l'expéditeur n'est pas simulé). */
private fun simulate(snap: Snapshot, text: String): String =
    when (val env = SmsCommands.parseEnvelope(text)) {
        SmsParse.NotCommand -> "Ce texte ne commence pas par « AST » : l'app l'ignorerait."
        is SmsParse.Malformed -> env.why
        is SmsParse.Command ->
            if (env.pin != snap.settings.smsPin) {
                "Le PIN du SMS est différent de celui des réglages : la commande serait refusée."
            } else when (val a = SmsCommands.interpret(env.args, snap.doctors, snap.settings.slots, LocalDate.now())) {
                is SmsAction.Error -> "Erreur : ${a.why}"
                SmsAction.Query -> "Compris : demande d'état du renvoi."
                is SmsAction.Sync -> "Compris : demande de synchronisation (le relais renverrait tout le planning)."
                is SmsAction.Push ->
                    if (a.error != null) "Erreur : ${a.error}"
                    else "Compris : modifications ${a.part}/${a.total}, ${SyncCodec.countCells(a.groups)} case(s)."
                is SmsAction.Plan -> "Réponse qui serait envoyée :\n" +
                    SmsCommands.planText(snap, a.dates, java.time.ZoneId.systemDefault())
                is SmsAction.Edit -> "Compris : " + SmsCommands.describe(a.groups, snap.doctors)
            }
    }

// ====================================================================== rôle du téléphone

/** Choix du rôle : relais (téléphone d'astreinte) ou consultation (téléphone personnel). */
@Composable
fun ModeCard(snap: Snapshot, onEdit: ((Snapshot) -> Snapshot) -> Unit) {
    val mode = snap.settings.mode
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Rôle de ce téléphone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ModeOption(
                selected = mode == Mode.RELAY,
                title = "Relais (téléphone d'astreinte)",
                subtitle = "Applique le renvoi d'appel, reçoit les commandes par SMS, prévient les médecins.",
            ) { onEdit { it.copy(settings = it.settings.copy(mode = Mode.RELAY)) } }
            ModeOption(
                selected = mode == Mode.VIEWER,
                title = "Consultation (téléphone personnel)",
                subtitle = "Consulte et modifie le planning, synchronisé par SMS avec le relais. N'applique jamais de renvoi.",
            ) { onEdit { it.copy(settings = it.settings.copy(mode = Mode.VIEWER)) } }
        }
    }
}

@Composable
private fun ModeOption(selected: Boolean, title: String, subtitle: String, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(top = 2.dp, end = 12.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Premier lancement : impossible à ignorer, le rôle détermine tout le comportement. */
@Composable
fun ModeChooserDialog(onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Quel est le rôle de ce téléphone ?") },
        text = {
            Text(
                "• Relais : le téléphone d'astreinte. Il applique le renvoi d'appel aux heures prévues.\n\n" +
                    "• Consultation : votre téléphone personnel. Il sert à voir et modifier le planning, " +
                    "et se synchronise par SMS avec le relais.\n\nVous pourrez changer plus tard dans les Réglages."
            )
        },
        confirmButton = { Button(onClick = { onPick(Mode.RELAY) }) { Text("Relais") } },
        dismissButton = { OutlinedButton(onClick = { onPick(Mode.VIEWER) }) { Text("Consultation") } },
    )
}

// ====================================================================== messages automatiques

/** Messages SMS envoyés automatiquement aux médecins par le téléphone relais. */
@Composable
fun MessagesSection(snap: Snapshot, resumeTick: Int, onEdit: ((Snapshot) -> Snapshot) -> Unit) {
    val context = LocalContext.current
    val st = snap.settings
    val sendOk = remember(resumeTick) { Perms.sendSms(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    var editing by remember { mutableStateOf<MessageRule?>(null) }
    var creating by remember { mutableStateOf(false) }

    Text("Messages automatiques aux médecins", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Prévenir les médecins par SMS")
            Text(
                "Ex. à 12h : « tu es d'astreinte, la bascule est faite ». Envoyé depuis ce téléphone au médecin concerné.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = st.msgEnabled,
            onCheckedChange = { v ->
                if (v && !sendOk) launcher.launch(arrayOf(Manifest.permission.SEND_SMS))
                onEdit { it.copy(settings = it.settings.copy(msgEnabled = v)) }
            },
        )
    }
    if (!st.msgEnabled) return

    if (!sendOk) {
        Text("Permission d'envoi de SMS manquante : aucun message ne partira.", color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { launcher.launch(arrayOf(Manifest.permission.SEND_SMS)) }) { Text("Autoriser") }
    }
    if (!st.enabled) {
        Text(
            "Les messages ne partent que lorsque la « Bascule automatique » est active.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
        )
    }

    st.msgRules.forEach { rule ->
        Card(
            modifier = Modifier.fillMaxWidth().clickable { editing = rule },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${Messages.hourText(rule.at)} → médecin du créneau ${Messages.hourText(rule.slotTime)}" +
                            if (rule.confirmsSwitch) " (si bascule faite)" else "",
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        rule.text,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { v ->
                        onEdit { s -> s.copy(settings = s.settings.copy(msgRules = s.settings.msgRules.map { if (it.id == rule.id) it.copy(enabled = v) else it })) }
                    },
                )
            }
        }
    }
    Row {
        TextButton(onClick = { creating = true }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Ajouter un message")
        }
        TextButton(onClick = { onEdit { it.copy(settings = it.settings.copy(msgRules = DEFAULT_MESSAGE_RULES)) } }) {
            Text("Rétablir ceux d'origine")
        }
    }

    // Changement de l'astreinte EN COURS (hors changements programmés aux heures des créneaux)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Prévenir si l'astreinte en cours change", fontWeight = FontWeight.Bold)
                    Text(
                        "Quand une modification (application, SMS, synchro, import) change le médecin d'astreinte EN COURS, " +
                            "le nouveau médecin est prévenu qu'il est d'astreinte et l'ancien qu'il ne l'est plus.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = st.changeNotify,
                    onCheckedChange = { v -> onEdit { it.copy(settings = it.settings.copy(changeNotify = v)) } },
                )
            }
            if (st.changeNotify) {
                OutlinedTextField(
                    value = st.changeTextNew,
                    onValueChange = { v -> onEdit { it.copy(settings = it.settings.copy(changeTextNew = v)) } },
                    label = { Text("Message au nouveau médecin") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = st.changeTextOld,
                    onValueChange = { v -> onEdit { it.copy(settings = it.settings.copy(changeTextOld = v)) } },
                    label = { Text("Message à l'ancien médecin") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Variables : {nom} {heure}. Un changement programmé à l'heure d'un créneau ne déclenche pas ce message.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (creating || editing != null) {
        val rule = editing ?: MessageRule(
            UUID.randomUUID().toString(), true,
            st.slots.minByOrNull { it.time }?.time ?: LocalTime.of(12, 0),
            st.slots.minByOrNull { it.time }?.time ?: LocalTime.of(12, 0),
            true, "Bonjour {nom}, tu es d'astreinte de {debut} a {fin}.",
        )
        val isNew = editing == null
        MessageRuleDialog(
            snap = snap,
            initial = rule,
            canDelete = !isNew,
            onDismiss = { creating = false; editing = null },
            onDelete = {
                onEdit { s -> s.copy(settings = s.settings.copy(msgRules = s.settings.msgRules.filter { it.id != rule.id })) }
                creating = false; editing = null
            },
            onSave = { saved ->
                onEdit { s ->
                    val rules = s.settings.msgRules
                    s.copy(settings = s.settings.copy(msgRules = if (isNew) rules + saved else rules.map { if (it.id == saved.id) saved else it }))
                }
                creating = false; editing = null
            },
        )
    }
}

@Composable
private fun MessageRuleDialog(
    snap: Snapshot,
    initial: MessageRule,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (MessageRule) -> Unit,
) {
    var at by remember { mutableStateOf(initial.at) }
    var slotTime by remember { mutableStateOf(initial.slotTime) }
    var confirms by remember { mutableStateOf(initial.confirmsSwitch) }
    var text by remember { mutableStateOf(initial.text) }
    var pickAt by remember { mutableStateOf(false) }
    var slotMenu by remember { mutableStateOf(false) }
    val slots = snap.settings.slots.sortedBy { it.time }
    val example = snap.doctors.firstOrNull { it.id != STANDARD_ID } ?: Doctor("x", "Dr Exemple", "0600000000")
    val preview = Messages.render(text, example, LocalDate.now(), slotTime, slots)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (canDelete) "Modifier le message" else "Nouveau message") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledRow("Envoyé à") { OutlinedButton(onClick = { pickAt = true }) { Text(at.hhmm()) } }
                LabeledRow("Pour") {
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { slotMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("le médecin de ${Messages.hourText(slotTime)}")
                        }
                        DropdownMenu(expanded = slotMenu, onDismissRequest = { slotMenu = false }) {
                            slots.forEach { sl ->
                                DropdownMenuItem(
                                    text = { Text("${Messages.hourText(sl.time)} · ${sl.label}") },
                                    onClick = { slotTime = sl.time; slotMenu = false },
                                )
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Seulement si la bascule vers lui est faite", Modifier.weight(1f))
                    Switch(checked = confirms, onCheckedChange = { confirms = it })
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Texte du message") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Variables : {nom} {debut} {fin} {date}. Évitez les accents et symboles spéciaux : un SMS avec " +
                        "caractères spéciaux est découpé en messages plus courts.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Aperçu : $preview", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                Text(
                    "Le message n'est jamais envoyé au standard, ni s'il n'y a personne d'astreinte à ce créneau, ni plus de 90 minutes après l'heure prévue.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (canDelete) {
                    TextButton(onClick = onDelete) { Text("Supprimer ce message", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = text.isNotBlank(),
                onClick = { onSave(initial.copy(at = at, slotTime = slotTime, confirmsSwitch = confirms, text = text.trim())) },
            ) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
    if (pickAt) TimeDialog("Heure d'envoi", at, { pickAt = false }) { at = it; pickAt = false }
}

// ====================================================================== historique du planning

/** Qui a modifié le planning, quand et comment : consultable, partageable, conservé dans le fichier de données. */
@Composable
fun HistorySection(snap: Snapshot, onEdit: ((Snapshot) -> Snapshot) -> Unit) {
    val context = LocalContext.current
    val fmt = remember { java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRANCE) }
    var showAll by remember { mutableStateOf(false) }
    val dateOf: (Long) -> String = { fmt.format(java.util.Date(it)) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Historique des modifications du planning", style = MaterialTheme.typography.titleMedium)
        Text(
            "Chaque modification est enregistrée : date, auteur, canal (saisie, import photo, SMS, synchro) et cases changées. " +
                "Sur le relais, les SMS indiquent le médecin et son numéro ; sur ce téléphone, c'est le nom ci-dessous.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = snap.settings.userName,
            onValueChange = { v -> onEdit { it.copy(settings = it.settings.copy(userName = v.take(60))) } },
            label = { Text("Mon nom (inscrit dans l'historique)") },
            placeholder = { Text("Ex. Dr Martin") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (snap.history.isEmpty()) Text("Aucune modification enregistrée.", style = MaterialTheme.typography.bodySmall)
        snap.history.take(8).forEach { HistoryRow(it, dateOf) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (snap.history.size > 8) TextButton(onClick = { showAll = true }) { Text("Tout afficher (${snap.history.size})") }
            if (snap.history.isNotEmpty()) TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, fr.astreinte.relais.core.History.asText(snap.history, dateOf))
                }
                context.startActivity(Intent.createChooser(send, "Partager l'historique").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Partager l'historique") }
        }
    }

    if (showAll) {
        AlertDialog(
            onDismissRequest = { showAll = false },
            title = { Text("Historique (${snap.history.size})") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    snap.history.forEach { HistoryRow(it, dateOf) }
                }
            },
            confirmButton = { TextButton(onClick = { showAll = false }) { Text("Fermer") } },
        )
    }
}

@Composable
private fun HistoryRow(e: fr.astreinte.relais.core.HistoryEntry, dateOf: (Long) -> String) {
    Column {
        Text("${dateOf(e.at)} · ${e.who}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        Text("${e.via} · ${e.cells} case(s)", style = MaterialTheme.typography.labelMedium)
        e.lines.take(6).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (e.lines.size > 6) Text("… +${e.lines.size - 6} ligne(s)", style = MaterialTheme.typography.bodySmall)
    }
}
