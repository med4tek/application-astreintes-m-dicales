package fr.astreinte.relais.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.astreinte.relais.core.Doctor
import fr.astreinte.relais.core.DoctorList
import fr.astreinte.relais.core.NameMatcher
import fr.astreinte.relais.core.ImportParser
import fr.astreinte.relais.core.ImportResult
import fr.astreinte.relais.core.Planner
import fr.astreinte.relais.core.PromptBuilder
import fr.astreinte.relais.core.STANDARD_ID
import fr.astreinte.relais.core.Snapshot
import fr.astreinte.relais.core.Target
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

@Composable
fun ImportScreen(
    snap: Snapshot,
    onEdit: ((Snapshot) -> Snapshot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Par défaut : le mois suivant si on est en fin de mois, sinon le mois courant
    var monthIndex by rememberSaveable {
        val t = LocalDate.now()
        val m = if (t.dayOfMonth >= 20) YearMonth.from(t).plusMonths(1) else YearMonth.from(t)
        mutableIntStateOf(m.year * 12 + m.monthValue - 1)
    }
    val month = YearMonth.of(monthIndex / 12, monthIndex % 12 + 1)
    // Précisions mémorisées dans les réglages : on les saisit une fois pour toutes pour ce format de planning
    val notes = snap.settings.importNotes
    fun setNotes(v: String) = onEdit { it.copy(settings = it.settings.copy(importNotes = v)) }
    var pasted by rememberSaveable { mutableStateOf("") }
    var showFullPrompt by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ImportResult?>(null) }
    // nom inconnu -> identifiant de médecin choisi (KEY_KEEP = ignorer)
    var mapping by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var appliedMessage by remember { mutableStateOf<String?>(null) }
    // Médecins (nom + numéro) lus sur la photo par l'assistant, et identifiants prévus pour les nouveaux
    var photoDoctors by remember { mutableStateOf<List<DoctorList.Entry>>(emptyList()) }
    var idPool by remember { mutableStateOf<List<String>>(emptyList()) }
    var addDoctors by remember { mutableStateOf(true) }
    // nom lu sur la photo -> médecin existant choisi (KEY_NEW = ajouter, KEY_KEEP = ignorer)
    var docChoice by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }

    fun idGen(): () -> String {
        val it = idPool.iterator()
        return { if (it.hasNext()) it.next() else UUID.randomUUID().toString() }
    }
    // Les noms de la photo que la liste ne reconnaît pas (ex. « Dr Durand » alors que l'app connaît « Alice »)
    fun unmatched(base: List<Doctor>) = photoDoctors.filter { NameMatcher.find(it.name, base) == null }
    fun choiceOf(e: DoctorList.Entry): String = docChoice[e.name] ?: KEY_NEW
    /** Liste de médecins après l'import : noms complétés pour les liens choisis, nouveaux médecins ajoutés. */
    fun mergedDoctors(base: List<Doctor>, withPhoto: Boolean): List<Doctor> {
        if (!withPhoto || photoDoctors.isEmpty()) return base
        var list = base
        val toAdd = ArrayList<DoctorList.Entry>()
        for (e in unmatched(base)) {
            when (val c = choiceOf(e)) {
                KEY_KEEP -> {}
                KEY_NEW -> toAdd += e
                else -> list = list.map { if (it.id == c) it.copy(name = DoctorList.completeName(it.name, e.name)) else it }
            }
        }
        return DoctorList.merge(list, toAdd, idGen()).doctors
    }
    fun analyze(withPhoto: Boolean) {
        result = ImportParser.parse(pasted, mergedDoctors(snap.doctors, withPhoto), snap.settings.slots, snap.aliases)
    }

    val prompt = remember(snap.doctors, snap.settings.slots, monthIndex, notes) {
        PromptBuilder.build(month, snap.doctors, snap.settings.slots, notes)
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Remplir le mois avec une photo", style = MaterialTheme.typography.titleLarge)
        Text(
            "1. Copiez la consigne ci-dessous.  2. Envoyez-la avec la photo de votre planning à un assistant IA " +
                "(Claude, ChatGPT…).  3. Collez sa réponse ici : le planning se remplit tout seul, après vérification.",
            style = MaterialTheme.typography.bodyMedium,
        )

        // ---------------------------------------------------------------- étape 1
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. La consigne pour l'IA", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { monthIndex-- }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Mois précédent")
                    }
                    Text(month.label(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { monthIndex++ }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Mois suivant")
                    }
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { setNotes(it) },
                    label = { Text("Précisions sur votre planning (mémorisées)") },
                    placeholder = { Text("Ex. : la colonne « Garde » correspond à 18:00") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { setNotes(if (notes.isBlank()) PromptBuilder.LAYOUT_TEMPLATE else notes.trimEnd() + "\n\n" + PromptBuilder.LAYOUT_TEMPLATE) }) {
                    Text("Insérer le modèle « tableau à cases colorées »")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { copyToClipboard(context, prompt) }, modifier = Modifier.weight(1f)) {
                        Text("Copier")
                    }
                    OutlinedButton(onClick = { share(context, prompt) }, modifier = Modifier.weight(1f)) {
                        Text("Partager…")
                    }
                }
                Text(
                    prompt,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (showFullPrompt) Int.MAX_VALUE else 5,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { showFullPrompt = !showFullPrompt }) {
                    Text(if (showFullPrompt) "Réduire" else "Voir toute la consigne")
                }
            }
        }

        // ---------------------------------------------------------------- étape 2
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2. La réponse de l'IA", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it; result = null; appliedMessage = null },
                    label = { Text("Collez ici la réponse") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            readClipboard(context)?.let { pasted = it; result = null; appliedMessage = null }
                                ?: Toast.makeText(context, "Presse-papiers vide", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Coller") }
                    Button(
                        enabled = pasted.isNotBlank(),
                        onClick = {
                            photoDoctors = ImportParser.parse(pasted, snap.doctors, snap.settings.slots, snap.aliases).doctors
                            idPool = photoDoctors.map { UUID.randomUUID().toString() }
                            addDoctors = true
                            docChoice = emptyMap()
                            analyze(true)
                            mapping = emptyMap()
                            appliedMessage = null
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Analyser") }
                }
            }
        }

        // ---------------------------------------------------------------- étape 3 : aperçu
        result?.let { r ->
            val photoUnmatched = unmatched(snap.doctors)
            PreviewCard(
                snap = snap.copy(doctors = mergedDoctors(snap.doctors, addDoctors)),
                result = r,
                mapping = mapping,
                onMap = { name, id -> mapping = mapping + (name to id) },
                photoDoctorCount = photoDoctors.size,
                photoUnmatched = photoUnmatched,
                docChoice = { choiceOf(it) },
                pickerDoctors = snap.doctors.filter { it.id != STANDARD_ID },
                onChoose = { e, id -> docChoice = docChoice + (e.name to id); analyze(addDoctors) },
                addDoctors = addDoctors,
                onToggleDoctors = { v -> addDoctors = v; analyze(v) },
                onApply = {
                    // Les noms inconnus peuvent devenir de nouveaux médecins (numéro à compléter ensuite)
                    val created = r.unknownNames.filter { mapping[it] == KEY_NEW }
                        .associateWith { Doctor(UUID.randomUUID().toString(), DoctorList.prettyName(it), "") }
                    val resolved = LinkedHashMap<String, String>()
                    mapping.forEach { (k, v) -> if (v != null && v != KEY_KEEP && v != KEY_NEW) resolved[k] = v }
                    created.forEach { (k, d) -> resolved[k] = d.id }
                    val edits = ImportParser.toEdits(r.items, resolved)
                    // Ce que l'utilisateur a associé est mémorisé : le prochain import le reconnaîtra tout seul
                    var learned = resolved.mapKeys { NameMatcher.normalize(it.key) }.filterKeys { it.isNotEmpty() }
                    // Idem pour les médecins de la photo associés à un médecin existant (« Dr Durand » = Alice)
                    var addedDoctors = 0
                    var linkedDoctors = 0
                    if (addDoctors) unmatched(snap.doctors).forEach { e ->
                        when (val c = choiceOf(e)) {
                            KEY_KEEP -> {}
                            KEY_NEW -> addedDoctors++
                            else -> { linkedDoctors++; NameMatcher.normalize(e.name).takeIf { it.isNotEmpty() }?.let { learned = learned + (it to c) } }
                        }
                    }
                    onEdit { s ->
                        s.copy(
                            doctors = mergedDoctors(s.doctors, addDoctors) + created.values,
                            shifts = Planner.applyEdits(s.shifts, edits) { UUID.randomUUID().toString() },
                            aliases = s.aliases + learned,
                        )
                    }
                    val skipped = r.items.size - edits.size
                    appliedMessage = "${edits.size} case(s) enregistrée(s) dans le planning" +
                        (if (skipped > 0) " ($skipped ignorée(s))" else "") + "." +
                        (if (linkedDoctors > 0) " $linkedDoctors médecin(s) reliés à leur nom (mémorisé)." else "") +
                        (if (addedDoctors > 0) " $addedDoctors médecin(s) ajouté(s) sans numéro : complétez-les dans l'onglet Médecins." else "") +
                        (if (created.isNotEmpty()) " ${created.size} médecin(s) ajouté(s) SANS numéro : complétez-les dans l'onglet Médecins." else "")
                    result = null
                    pasted = ""
                },
            )
        }
        appliedMessage?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Text(
                    "$it Vérifiez le résultat dans l'onglet Planning.",
                    Modifier.padding(12.dp),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun PreviewCard(
    snap: Snapshot,
    result: ImportResult,
    mapping: Map<String, String?>,
    onMap: (String, String?) -> Unit,
    photoDoctorCount: Int,
    photoUnmatched: List<DoctorList.Entry>,
    docChoice: (DoctorList.Entry) -> String,
    pickerDoctors: List<Doctor>,
    onChoose: (DoctorList.Entry, String?) -> Unit,
    addDoctors: Boolean,
    onToggleDoctors: (Boolean) -> Unit,
    onApply: () -> Unit,
) {
    val byDate = result.items.groupBy { it.date }
    val unresolved = result.unknownNames.count { (mapping[it] ?: KEY_KEEP) == KEY_KEEP }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("3. Vérification", style = MaterialTheme.typography.titleMedium)
            val doctorsOnly = result.items.isEmpty() && addDoctors && photoUnmatched.any { docChoice(it) != KEY_KEEP }
            if (result.items.isEmpty()) {
                Text(if (photoDoctorCount > 0) "Aucune affectation de planning dans cette réponse." else "Aucune affectation reconnue.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            } else {
                Text("${result.items.size} affectation(s) sur ${result.dayCount} jour(s).", fontWeight = FontWeight.Bold)
            }
            result.problems.forEach {
                Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            // Médecins lus sur la photo : ceux que la liste ne reconnaît pas sont à associer ou à ajouter
            if (photoDoctorCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = addDoctors, onCheckedChange = onToggleDoctors)
                    Column {
                        Text("Médecins lus sur la photo", fontWeight = FontWeight.Bold)
                        Text(
                            "${photoDoctorCount - photoUnmatched.size} déjà reconnu(s) · ${photoUnmatched.size} à associer ou ajouter",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (addDoctors && photoUnmatched.isNotEmpty()) {
                    Text(
                        "Sur la photo, les médecins sont désignés par leur nom, alors que votre liste peut ne contenir que des prénoms. " +
                            "Pour chaque nom, choisissez le médecin déjà enregistré qui correspond : son nom est alors complété (« Alice » devient « Alice Durand ») " +
                            "et le lien est mémorisé pour les prochains imports. Sinon, ajoutez-le comme nouveau médecin.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    photoUnmatched.forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("« ${e.name} »", Modifier.weight(1f))
                            TargetPicker(
                                doctors = pickerDoctors,
                                value = docChoice(e),
                                onChange = { onChoose(e, it) },
                                keepLabel = "Ignorer",
                                allowOff = false,
                                newLabel = "Ajouter (sans numéro)",
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (photoUnmatched.any { docChoice(it) == KEY_NEW }) {
                        Text(
                            "Les médecins ajoutés n'ont pas de numéro : complétez-les dans l'onglet Médecins (aucun renvoi ne part vers un médecin sans numéro).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // Correspondances faites automatiquement (« Alice » -> Alice Durand…)
            val recognized = result.items.mapNotNull { item -> (item.target as? Target.Doc)?.let { item.raw to it.id } }
                .distinctBy { NameMatcher.normalize(it.first) }
                .mapNotNull { (raw, id) ->
                    snap.doctor(id)?.takeIf { NameMatcher.normalize(it.name) != NameMatcher.normalize(raw) }?.let { raw to it.name }
                }
            if (recognized.isNotEmpty()) {
                Text("Correspondances reconnues", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                recognized.forEach { (raw, name) -> Text("« $raw » → $name", style = MaterialTheme.typography.bodySmall) }
            }

            if (result.unknownNames.isNotEmpty()) {
                Text(
                    "Noms non reconnus : associez-les à un médecin de votre liste, ajoutez-les comme nouveaux médecins, ou ignorez-les. Votre choix est mémorisé pour les prochains imports.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                result.unknownNames.forEach { name ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("« $name »", Modifier.weight(1f))
                        TargetPicker(
                            doctors = snap.doctors,
                            value = mapping[name] ?: KEY_KEEP,
                            onChange = { onMap(name, it) },
                            keepLabel = "Ignorer",
                            allowOff = false,
                            newLabel = "Ajouter comme nouveau médecin",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            byDate.entries.sortedBy { it.key }.forEach { (date, items) ->
                val line = items.joinToString("  ·  ") { item ->
                    val who = when (val t = item.target) {
                        Target.Off -> "aucun renvoi"
                        Target.Clear -> "défaut"
                        is Target.Doc -> snap.doctor(t.id)?.name ?: "?"
                        is Target.Unknown -> when (val v = mapping[t.name]) {
                            null, KEY_KEEP -> "« ${t.name} » ?"
                            KEY_NEW -> "+ nouveau : ${DoctorList.prettyName(t.name)}"
                            else -> snap.doctor(v)?.name ?: "« ${t.name} » ?"
                        }
                    }
                    "${item.time.shortHour()} $who"
                }
                Text("${date.shortLabel()} — $line", style = MaterialTheme.typography.bodySmall)
            }

            if (result.items.isNotEmpty() || doctorsOnly) {
                Text(
                    "L'application remplace uniquement les créneaux présents dans cette réponse.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when {
                            doctorsOnly -> "Enregistrer les médecins"
                            unresolved > 0 -> "Appliquer (en ignorant $unresolved nom(s))"
                            else -> "Appliquer au planning"
                        }
                    )
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("Consigne planning", text))
    Toast.makeText(context, "Consigne copiée", Toast.LENGTH_SHORT).show()
}

private fun readClipboard(context: Context): String? {
    val cm = context.getSystemService(ClipboardManager::class.java)
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Envoyer la consigne à…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
