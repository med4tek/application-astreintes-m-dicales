package fr.astreinte.relais.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.astreinte.relais.core.Doctor
import fr.astreinte.relais.core.Planner
import fr.astreinte.relais.core.Shift
import fr.astreinte.relais.core.Snapshot
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.DayOfWeek
import java.util.UUID

@Composable
fun PlanningScreen(
    snap: Snapshot,
    onEdit: ((Snapshot) -> Snapshot) -> Unit,
    onGoToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = ZoneId.systemDefault()
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = Instant.now()
        }
    }

    // Mois affiché, sauvegardé à la rotation de l'écran
    val todayMonth = YearMonth.now()
    var monthIndex by rememberSaveable { mutableIntStateOf(todayMonth.year * 12 + todayMonth.monthValue - 1) }
    val month = YearMonth.of(monthIndex / 12, monthIndex % 12 + 1)

    var editingDay by remember { mutableStateOf<LocalDate?>(null) }
    var filling by remember { mutableStateOf(false) }

    // Sélection multiple : jours choisis (en "epoch day"), conservés même en changeant de mois
    var selectMode by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<Set<Long>>(emptySet()) }
    var assigning by remember { mutableStateOf(false) }
    fun exitSelection() { selectMode = false; selected = emptySet() }
    fun toggle(date: LocalDate) {
        val k = date.toEpochDay()
        selected = if (k in selected) selected - k else selected + k
    }
    BackHandler(enabled = selectMode) { exitSelection() }

    val today = LocalDate.now(zone)
    val listState = rememberLazyListState()
    LaunchedEffect(monthIndex) {
        listState.scrollToItem(if (month == YearMonth.from(today)) (today.dayOfMonth - 1).coerceAtLeast(0) else 0)
    }

    Column(modifier.fillMaxSize()) {
        StatusCard(snap, now, zone, onGoToSettings, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { monthIndex-- }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Mois précédent")
            }
            Text(
                month.label(),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).clickable { monthIndex = todayMonth.year * 12 + todayMonth.monthValue - 1 },
            )
            IconButton(onClick = { monthIndex++ }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Mois suivant")
            }
        }

        val monthDays = (1..month.lengthOfMonth()).map { month.atDay(it) }
        if (!selectMode) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { selectMode = true }, modifier = Modifier.weight(1f)) {
                    Text("Sélectionner des jours")
                }
                OutlinedButton(onClick = { filling = true }, modifier = Modifier.weight(1f)) {
                    Text("Remplir une période")
                }
            }
        } else {
            SelectionBar(
                count = selected.size,
                onAllMonth = { selected = selected + monthDays.map { it.toEpochDay() } },
                onWeekends = {
                    selected = selected + monthDays.filter {
                        it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY
                    }.map { it.toEpochDay() }
                },
                onWeekdays = {
                    selected = selected + monthDays.filter {
                        it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY
                    }.map { it.toEpochDay() }
                },
                onClear = { selected = emptySet() },
                onCancel = { exitSelection() },
                onAssign = { assigning = true },
            )
        }

        val doctorsById = remember(snap.doctors) { snap.doctors.associateBy { it.id } }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(monthDays, key = { it.toEpochDay() }) { date ->
                DayRow(
                    date = date,
                    isToday = date == today,
                    shifts = Planner.shiftsOn(snap.shifts, date),
                    carried = Planner.carriedInto(snap.shifts, date, zone),
                    doctors = doctorsById,
                    selectMode = selectMode,
                    selected = date.toEpochDay() in selected,
                    onClick = { if (selectMode) toggle(date) else editingDay = date },
                    onLongClick = {
                        // Appui long : entre en mode sélection avec ce jour coché
                        if (!selectMode) selectMode = true
                        toggle(date)
                    },
                )
            }
        }
    }

    editingDay?.let { date ->
        DayEditorDialog(
            date = date,
            snap = snap,
            onDismiss = { editingDay = null },
            onSave = { newShifts ->
                onEdit { s -> s.copy(shifts = Planner.replaceDay(s.shifts, date, newShifts)) }
                editingDay = null
            },
        )
    }
    if (assigning && selected.isNotEmpty()) {
        val dates = selected.map { LocalDate.ofEpochDay(it) }.toSortedSet()
        AssignDialog(
            snap = snap,
            dates = dates,
            onDismiss = { assigning = false },
            onApply = { time, doctorId ->
                onEdit { s ->
                    s.copy(shifts = Planner.assignDates(s.shifts, dates, time, doctorId) { UUID.randomUUID().toString() })
                }
                assigning = false
                exitSelection()
            },
            onClearDays = {
                onEdit { s -> s.copy(shifts = Planner.clearDates(s.shifts, dates)) }
                assigning = false
                exitSelection()
            },
        )
    }
    if (filling) {
        FillPeriodDialog(
            snap = snap,
            initialMonth = month,
            onDismiss = { filling = false },
            onFill = { from, to, time, doctorId ->
                onEdit { s ->
                    s.copy(shifts = Planner.fillRange(s.shifts, from, to, time, doctorId) { UUID.randomUUID().toString() })
                }
                filling = false
            },
        )
    }
}

@Composable
private fun StatusCard(
    snap: Snapshot,
    now: Instant,
    zone: ZoneId,
    onGoToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = Planner.current(snap.shifts, now, zone)
    val next = Planner.next(snap.shifts, now, zone)
    val problem = !snap.state.lastOk && snap.state.lastAt > 0

    val container = when {
        problem -> MaterialTheme.colorScheme.errorContainer
        !snap.settings.enabled -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!snap.settings.enabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Bascule automatique désactivée", fontWeight = FontWeight.Bold)
                }
                Text("Le planning est enregistré mais aucun renvoi ne sera appliqué.")
                TextButton(onClick = onGoToSettings) { Text("Aller aux réglages") }
            } else {
                Text("Appels renvoyés vers", style = MaterialTheme.typography.labelMedium)
                Text(
                    if (current == null) "— (aucune astreinte en cours)" else snap.doctor(current.doctorId)?.name ?: "Aucun renvoi",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (current != null) {
                    Text(
                        "depuis ${current.date.shortLabel()} ${current.time.hhmm()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (next != null) {
                    Text(
                        "Prochain changement : ${next.date.shortLabel()} ${next.time.hhmm()} → ${snap.doctor(next.doctorId)?.name ?: "aucun renvoi"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text("Aucun changement planifié ensuite", style = MaterialTheme.typography.bodyMedium)
                }
                if (problem) {
                    Text(
                        "⚠ Dernier envoi en échec : ${snap.state.lastMessage}",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onAllMonth: () -> Unit,
    onWeekends: () -> Unit,
    onWeekdays: () -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
    onAssign: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (count == 0) "Touchez les jours à choisir" else "$count jour(s) sélectionné(s)",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                TextButton(onClick = onCancel) { Text("Annuler") }
                Button(onClick = onAssign, enabled = count > 0) { Text("Assigner…") }
            }
            Row {
                TextButton(onClick = onAllMonth, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Tout le mois") }
                TextButton(onClick = onWeekdays, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Lun–ven") }
                TextButton(onClick = onWeekends, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Week-ends") }
                TextButton(onClick = onClear, enabled = count > 0, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Vider") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayRow(
    date: LocalDate,
    isToday: Boolean,
    shifts: List<Shift>,
    carried: Shift?,
    doctors: Map<String, Doctor>,
    selectMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        border = when {
            selected -> BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
            isToday -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            else -> null
        },
        colors = CardDefaults.cardColors(
            containerColor = when {
                selected -> MaterialTheme.colorScheme.primaryContainer
                weekend -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                else -> MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selectMode) {
                Checkbox(checked = selected, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(date.weekdayShort(), style = MaterialTheme.typography.labelMedium)
                Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                fun name(id: String?) = if (id == null) "Aucun renvoi" else doctors[id]?.name ?: "(médecin supprimé)"
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                if (shifts.isEmpty()) {
                    if (carried != null) {
                        Text("↳ ${name(carried.doctorId)} (suite)", color = muted, fontStyle = FontStyle.Italic)
                    } else {
                        Text("— rien de prévu", color = muted)
                    }
                } else {
                    if (carried != null && shifts.first().time > LocalTime.MIDNIGHT) {
                        Text(
                            "jusqu'à ${shifts.first().time.hhmm()} : ${name(carried.doctorId)}",
                            color = muted, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    shifts.forEach { s ->
                        Row {
                            Text(s.time.hhmm(), fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
                            Text(name(s.doctorId), fontStyle = if (s.doctorId == null) FontStyle.Italic else FontStyle.Normal)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayEditorDialog(
    date: LocalDate,
    snap: Snapshot,
    onDismiss: () -> Unit,
    onSave: (List<Shift>) -> Unit,
) {
    // État éditable du dialogue : une liste de (heure, médecin)
    val drafts = remember(date) {
        mutableStateListOf<MutableDraft>().apply {
            val existing = Planner.shiftsOn(snap.shifts, date)
            if (existing.isEmpty()) {
                add(MutableDraft(snap.settings.defaultTime, snap.doctors.firstOrNull()?.id))
            } else {
                existing.forEach { add(MutableDraft(it.time, it.doctorId)) }
            }
        }
    }
    var timeEditIndex by remember { mutableIntStateOf(-1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(date.longLabel().replaceFirstChar { it.uppercase() }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (snap.doctors.isEmpty()) {
                    Text("Ajoutez d'abord des médecins dans l'onglet « Médecins ». Sans médecin, vous pouvez seulement désactiver le renvoi.")
                }
                drafts.forEachIndexed { index, d ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { timeEditIndex = index },
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) { Text(d.time.hhmm(), fontWeight = FontWeight.Bold) }
                        DoctorPicker(
                            doctors = snap.doctors,
                            selectedId = d.doctorId,
                            onSelect = { drafts[index] = d.copy(doctorId = it) },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { drafts.removeAt(index) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Supprimer ce changement")
                        }
                    }
                }
                if (drafts.isEmpty()) {
                    Text("Aucun changement ce jour-là (le renvoi du jour précédent continue).", fontStyle = FontStyle.Italic)
                }
                TextButton(onClick = {
                    val last = drafts.lastOrNull()
                    val time = if (last == null) snap.settings.defaultTime else LocalTime.of(20, 0)
                    drafts.add(MutableDraft(time, snap.doctors.firstOrNull()?.id))
                }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Ajouter un changement d'astreinte")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(drafts.map { Shift(UUID.randomUUID().toString(), date, it.time, it.doctorId) })
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )

    if (timeEditIndex in drafts.indices) {
        val i = timeEditIndex
        TimeDialog(
            title = "Heure du changement",
            initial = drafts[i].time,
            onDismiss = { timeEditIndex = -1 },
            onConfirm = { t ->
                drafts[i] = drafts[i].copy(time = t)
                timeEditIndex = -1
            },
        )
    }
}

private data class MutableDraft(val time: LocalTime, val doctorId: String?)

@Composable
private fun FillPeriodDialog(
    snap: Snapshot,
    initialMonth: YearMonth,
    onDismiss: () -> Unit,
    onFill: (from: LocalDate, to: LocalDate, time: LocalTime, doctorId: String?) -> Unit,
) {
    var doctorId by remember { mutableStateOf(snap.doctors.firstOrNull()?.id) }
    val firstDay = if (initialMonth == YearMonth.now()) LocalDate.now() else initialMonth.atDay(1)
    var from by remember { mutableStateOf(firstDay) }
    var to by remember { mutableStateOf(firstDay) }
    var time by remember { mutableStateOf(snap.settings.defaultTime) }
    var pick by remember { mutableIntStateOf(0) } // 1 = début, 2 = fin, 3 = heure

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remplir une période") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Place le même médecin chaque jour, à la même heure de relève. " +
                        "Remplace ce qui existe déjà sur ces jours.",
                    style = MaterialTheme.typography.bodySmall,
                )
                LabeledRow("Médecin") { DoctorPicker(snap.doctors, doctorId, { doctorId = it }, Modifier.weight(1f)) }
                LabeledRow("Du") { OutlinedButton(onClick = { pick = 1 }) { Text(from.shortLabel()) } }
                LabeledRow("Au (inclus)") { OutlinedButton(onClick = { pick = 2 }) { Text(to.shortLabel()) } }
                LabeledRow("Heure") { OutlinedButton(onClick = { pick = 3 }) { Text(time.hhmm()) } }
                if (to.isBefore(from)) {
                    Text("La date de fin est avant le début.", color = MaterialTheme.colorScheme.error)
                } else {
                    val days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1
                    Text("$days jour(s) concerné(s).", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !to.isBefore(from),
                onClick = { onFill(from, to, time, doctorId) },
            ) { Text("Remplir") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )

    when (pick) {
        1 -> DateDialog(from, { pick = 0 }) {
            from = it
            if (to.isBefore(it)) to = it
            pick = 0
        }
        2 -> DateDialog(to, { pick = 0 }) { to = it; pick = 0 }
        3 -> TimeDialog("Heure du changement", time, { pick = 0 }) { time = it; pick = 0 }
    }
}

@Composable
private fun AssignDialog(
    snap: Snapshot,
    dates: Set<LocalDate>,
    onDismiss: () -> Unit,
    onApply: (time: LocalTime, doctorId: String?) -> Unit,
    onClearDays: () -> Unit,
) {
    var doctorId by remember { mutableStateOf(snap.doctors.firstOrNull()?.id) }
    var time by remember { mutableStateOf(snap.settings.defaultTime) }
    var pickTime by remember { mutableStateOf(false) }
    val shown = dates.take(8).joinToString(", ") { it.shortLabel() }
    val more = dates.size - 8

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assigner ${dates.size} jour(s)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    shown + if (more > 0) " … (+$more)" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                LabeledRow("Médecin") { DoctorPicker(snap.doctors, doctorId, { doctorId = it }, Modifier.weight(1f)) }
                LabeledRow("Heure") { OutlinedButton(onClick = { pickTime = true }) { Text(time.hhmm()) } }
                Text(
                    "Remplace ce qui existe déjà sur ces jours : un seul changement par jour, à cette heure.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onClearDays) {
                    Text("Plutôt vider ces jours", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { Button(onClick = { onApply(time, doctorId) }) { Text("Appliquer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
    if (pickTime) {
        TimeDialog("Heure du changement", time, { pickTime = false }) { time = it; pickTime = false }
    }
}
