package fr.astreinte.relais.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.astreinte.relais.core.Holidays
import fr.astreinte.relais.core.CellEdit
import fr.astreinte.relais.core.Planner
import fr.astreinte.relais.core.Shift
import fr.astreinte.relais.core.Slot
import fr.astreinte.relais.core.Snapshot
import fr.astreinte.relais.core.SyncLogic
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

private val DAY_COL = 44.dp

private enum class CellKind { EXPLICIT, DEFAULT, INHERITED, EMPTY }
private data class Cell(val text: String, val kind: CellKind)

private fun newId() = UUID.randomUUID().toString()

private fun defaultLabelFor(snap: Snapshot, slot: Slot, date: LocalDate? = null): String =
    if (date != null && Holidays.isOffDay(date)) "Aucun changement (même personne toute la journée)"
    else slot.defaultTargetId?.let { "Par défaut : ${snap.doctor(it)?.name ?: "?"}" } ?: "Aucun changement"

private fun shortName(name: String) = name.replace(Regex("^(Docteur|Doc)\\.?\\s+", RegexOption.IGNORE_CASE), "Dr ")

/** Contenu de chaque case (jour × créneau) du mois affiché. */
private fun buildCells(snap: Snapshot, slots: List<Slot>, month: YearMonth, zone: ZoneId): Map<LocalDate, List<Cell>> {
    val first = month.atDay(1)
    val effective = Planner.effective(snap.shifts, slots, first.minusDays(8), month.atEndOfMonth())
    val explicit = snap.shifts.mapTo(HashSet()) { it.date to it.time }
    val byKey = effective.associateBy { it.date to it.time }
    fun nameOf(id: String?) = if (id == null) "Aucun" else shortName(snap.doctor(id)?.name ?: "?")
    return (1..month.lengthOfMonth()).map { month.atDay(it) }.associateWith { date ->
        slots.map { slot ->
            val s = byKey[date to slot.time]
            if (s != null) {
                Cell(nameOf(s.doctorId), if ((date to slot.time) in explicit) CellKind.EXPLICIT else CellKind.DEFAULT)
            } else {
                val prev = Planner.inForceBefore(effective, ZonedDateTime.of(date, slot.time, zone).toInstant(), zone)
                if (prev != null) Cell(nameOf(prev.doctorId), CellKind.INHERITED) else Cell("—", CellKind.EMPTY)
            }
        }
    }
}

@Composable
fun PlanningScreen(
    snap: Snapshot,
    onEdit: ((Snapshot) -> Snapshot) -> Unit,
    onGoToSettings: () -> Unit,
    onGoToSync: () -> Unit,
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

    val slots = remember(snap.settings.slots) { snap.settings.slots.sortedBy { it.time } }
    var editingDay by remember { mutableStateOf<LocalDate?>(null) }
    var editingCell by remember { mutableStateOf<Pair<LocalDate, Slot>?>(null) }

    // Sélection multiple : jours choisis (en "epoch day"), conservés en changeant de mois
    var selectMode by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<Set<Long>>(emptySet()) }
    var assigning by remember { mutableStateOf(false) }
    var rangeStep by remember { mutableIntStateOf(0) }
    var rangeStart by remember { mutableStateOf(LocalDate.now()) }
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

    val monthDays = remember(monthIndex) { (1..month.lengthOfMonth()).map { month.atDay(it) } }
    val cellsByDate = remember(snap.shifts, snap.doctors, slots, monthIndex) { buildCells(snap, slots, month, zone) }

    Column(modifier.fillMaxSize()) {
        StatusCard(snap, now, zone, onGoToSettings, onGoToSync, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))

        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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

        if (!selectMode) {
            OutlinedButton(
                onClick = { selectMode = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            ) { Text("Sélectionner plusieurs jours") }
        } else {
            SelectionBar(
                count = selected.size,
                onAllMonth = { selected = selected + monthDays.map { it.toEpochDay() } },
                onWeekends = {
                    selected = selected + monthDays.filter {
                        Holidays.isOffDay(it)
                    }.map { it.toEpochDay() }
                },
                onWeekdays = {
                    selected = selected + monthDays.filter {
                        !Holidays.isOffDay(it)
                    }.map { it.toEpochDay() }
                },
                onRange = { rangeStep = 1 },
                onClear = { selected = emptySet() },
                onCancel = { exitSelection() },
                onAssign = { assigning = true },
            )
        }

        // En-tête des colonnes : une par créneau de la journée
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
            Spacer(Modifier.width(DAY_COL))
            slots.forEach {
                Text(
                    it.time.shortHour(),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(monthDays, key = { it.toEpochDay() }) { date ->
                DayRow(
                    date = date,
                    isToday = date == today,
                    cells = cellsByDate[date].orEmpty(),
                    extra = snap.shifts.count { s -> s.date == date && slots.none { it.time == s.time } },
                    todo = Holidays.isOffDay(date) && !date.isBefore(LocalDate.now()) && snap.shifts.none { it.date == date },
                    selectMode = selectMode,
                    selected = date.toEpochDay() in selected,
                    onDayClick = { if (selectMode) toggle(date) else editingDay = date },
                    onCellClick = { i ->
                        if (selectMode) toggle(date) else slots.getOrNull(i)?.let { editingCell = date to it }
                    },
                    onLongClick = {
                        // Appui long : entre en mode sélection avec ce jour coché
                        if (!selectMode) selectMode = true
                        toggle(date)
                    },
                )
            }
            item {
                Text(
                    "Fond coloré : saisi à la main · fond gris : valeur par défaut · texte pâle : hérité du créneau précédent. Week-ends et jours fériés : une seule personne toute la journée (saisie à 9h), pas de standard.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }

    editingCell?.let { (date, slot) ->
        CellDialog(
            snap = snap,
            date = date,
            slot = slot,
            onDismiss = { editingCell = null },
            onPick = { edit ->
                onEdit { s -> s.copy(shifts = Planner.editCells(s.shifts, setOf(date), mapOf(slot.time to edit), ::newId)) }
                editingCell = null
            },
        )
    }
    editingDay?.let { date ->
        DayEditorDialog(
            date = date,
            snap = snap,
            slots = slots,
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
            slots = slots,
            dates = dates,
            onDismiss = { assigning = false },
            onApply = { edits ->
                onEdit { s -> s.copy(shifts = Planner.editCells(s.shifts, dates, edits, ::newId)) }
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
    when (rangeStep) {
        1 -> DateDialog(
            initial = monthDays.first(),
            title = "Du…",
            onDismiss = { rangeStep = 0 },
            onConfirm = { rangeStart = it; rangeStep = 2 },
        )
        2 -> DateDialog(
            initial = rangeStart,
            title = "Au… (inclus)",
            onDismiss = { rangeStep = 0 },
            onConfirm = { end ->
                val from = minOf(rangeStart, end)
                val to = minOf(maxOf(rangeStart, end), from.plusDays(61))
                selected = selected + generateSequence(from) { d -> d.plusDays(1).takeIf { !it.isAfter(to) } }
                    .map { it.toEpochDay() }.toSet()
                rangeStep = 0
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
    onGoToSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (snap.settings.isViewer) {
        ViewerCard(snap, onGoToSync, modifier)
        return
    }
    val current = Planner.currentShift(snap, now, zone)
    val next = Planner.nextShift(snap, now, zone)
    val problem = !snap.state.lastOk && snap.state.lastAt > 0
    fun name(id: String?) = if (id == null) "aucun renvoi" else snap.doctor(id)?.name ?: "?"

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
                    if (current == null) "— (rien en cours)" else name(current.doctorId).replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (current != null) {
                    Text("depuis ${current.date.shortLabel()} ${current.time.hhmm()}", style = MaterialTheme.typography.bodySmall)
                }
                if (next != null) {
                    Text(
                        "Prochain changement : ${next.date.shortLabel()} ${next.time.hhmm()} → ${name(next.doctorId)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
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

/** Carte d'état d'un téléphone de consultation : pas de renvoi ici, mais l'état de la synchro. */
@Composable
private fun ViewerCard(snap: Snapshot, onGoToSync: () -> Unit, modifier: Modifier) {
    val unsent = SyncLogic.unsentCount(snap)
    val never = snap.sync.lastPullAt == 0L
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (unsent > 0 || never) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Mode consultation", style = MaterialTheme.typography.labelMedium)
            Text(
                when {
                    never -> "Planning pas encore reçu du relais"
                    unsent > 0 -> "$unsent modification(s) à envoyer"
                    else -> "✓ À jour avec le relais"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            TextButton(onClick = onGoToSync) { Text("Synchroniser") }
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onAllMonth: () -> Unit,
    onWeekends: () -> Unit,
    onWeekdays: () -> Unit,
    onRange: () -> Unit,
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
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                val pad = PaddingValues(horizontal = 8.dp)
                TextButton(onClick = onAllMonth, contentPadding = pad) { Text("Tout le mois") }
                TextButton(onClick = onWeekdays, contentPadding = pad) { Text("Jours ouvrés") }
                TextButton(onClick = onWeekends, contentPadding = pad) { Text("WE + fériés") }
                TextButton(onClick = onRange, contentPadding = pad) { Text("Période…") }
                TextButton(onClick = onClear, enabled = count > 0, contentPadding = pad) { Text("Vider") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayRow(
    date: LocalDate,
    isToday: Boolean,
    cells: List<Cell>,
    extra: Int,
    /** Week-end ou férié à venir sans personne désignée pour la journée. */
    todo: Boolean,
    selectMode: Boolean,
    selected: Boolean,
    onDayClick: () -> Unit,
    onCellClick: (Int) -> Unit,
    onLongClick: () -> Unit,
) {
    val weekend = Holidays.isOffDay(date)
    val holiday = Holidays.isHoliday(date)
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = when {
            selected -> BorderStroke(3.dp, scheme.primary)
            isToday -> BorderStroke(2.dp, scheme.primary)
            else -> null
        },
        colors = CardDefaults.cardColors(
            containerColor = when {
                selected -> scheme.primaryContainer
                weekend -> scheme.secondaryContainer.copy(alpha = 0.55f)
                else -> scheme.surfaceContainerLow
            }
        ),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.width(DAY_COL).combinedClickable(onClick = onDayClick, onLongClick = onLongClick),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(date.weekdayShort(), style = MaterialTheme.typography.labelMedium)
                    if (selectMode && selected) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = "Sélectionné", modifier = Modifier.size(14.dp), tint = scheme.primary)
                    }
                }
                Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (extra > 0) Text("+$extra", fontSize = 10.sp, color = scheme.tertiary)
                if (holiday && !todo) Text("férié", fontSize = 10.sp, color = scheme.tertiary)
                if (todo) Text("à saisir", fontSize = 10.sp, color = scheme.error, fontWeight = FontWeight.Bold)
            }
            cells.forEachIndexed { i, cell ->
                val (bg, fg) = when (cell.kind) {
                    CellKind.EXPLICIT -> scheme.tertiaryContainer to scheme.onTertiaryContainer
                    CellKind.DEFAULT -> scheme.surfaceVariant to scheme.onSurfaceVariant
                    CellKind.INHERITED -> Color.Transparent to scheme.onSurfaceVariant.copy(alpha = 0.6f)
                    CellKind.EMPTY -> Color.Transparent to scheme.onSurfaceVariant.copy(alpha = 0.5f)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(2.dp)
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(bg)
                        .combinedClickable(onClick = { onCellClick(i) }, onLongClick = onLongClick)
                        .padding(horizontal = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        cell.text,
                        color = fg,
                        fontSize = 11.sp,
                        lineHeight = 12.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (cell.kind == CellKind.EXPLICIT) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (cell.kind == CellKind.INHERITED) FontStyle.Italic else FontStyle.Normal,
                    )
                }
            }
        }
    }
}

/** Choix rapide pour UNE case : par défaut, un médecin, ou aucun renvoi. */
@Composable
private fun CellDialog(snap: Snapshot, date: LocalDate, slot: Slot, onDismiss: () -> Unit, onPick: (CellEdit) -> Unit) {
    val explicit = snap.shifts.firstOrNull { it.date == date && it.time == slot.time }
    val current: String? = if (explicit == null) KEY_DEFAULT else explicit.doctorId
    val options: List<Pair<String?, String>> =
        listOf<Pair<String?, String>>(KEY_DEFAULT to defaultLabelFor(snap, slot, date)) +
            snap.doctors.map { it.id to it.name } +
            listOf<Pair<String?, String>>(null to "Aucun renvoi")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${date.shortLabel()} · ${slot.time.hhmm()}\n${slot.label}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (Holidays.isOffDay(date)) {
                    val first = snap.settings.slots.minOfOrNull { it.time }
                    Text(
                        "Week-end / jour férié : une seule personne pour toute la journée." +
                            (if (first != null && slot.time != first) " Pour la désigner, modifiez plutôt le créneau de ${first.hhmm()} ; ici, vous ne faites qu'un changement en cours de journée." else " Les autres créneaux la reprennent, sauf modification."),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                options.forEach { (key, label) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onPick(if (key == KEY_DEFAULT) CellEdit.Clear else CellEdit.Set(key)) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = key == current, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

private data class CustomDraft(val time: LocalTime, val doctorId: String?)

/** Édition de toute la journée : un sélecteur par créneau + changements à heure libre. */
@Composable
private fun DayEditorDialog(
    date: LocalDate,
    snap: Snapshot,
    slots: List<Slot>,
    onDismiss: () -> Unit,
    onSave: (List<Shift>) -> Unit,
) {
    val choices = remember(date) {
        mutableStateListOf<String?>().apply {
            slots.forEach { slot ->
                val e = snap.shifts.firstOrNull { it.date == date && it.time == slot.time }
                add(if (e == null) KEY_DEFAULT else e.doctorId)
            }
        }
    }
    val customs = remember(date) {
        mutableStateListOf<CustomDraft>().apply {
            snap.shifts.filter { s -> s.date == date && slots.none { it.time == s.time } }
                .sortedBy { it.time }
                .forEach { add(CustomDraft(it.time, it.doctorId)) }
        }
    }
    var timeEditIndex by remember { mutableIntStateOf(-1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(date.longLabel().replaceFirstChar { it.uppercase() }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                slots.forEachIndexed { i, slot ->
                    Text("${slot.time.hhmm()} · ${slot.label}", style = MaterialTheme.typography.labelMedium)
                    TargetPicker(
                        doctors = snap.doctors,
                        value = choices[i],
                        onChange = { choices[i] = it },
                        defaultLabel = defaultLabelFor(snap, slot, date),
                    )
                }
                if (customs.isNotEmpty()) {
                    Text("Autres changements (heure libre)", style = MaterialTheme.typography.labelMedium)
                }
                customs.forEachIndexed { index, d ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { timeEditIndex = index },
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) { Text(d.time.hhmm(), fontWeight = FontWeight.Bold) }
                        TargetPicker(
                            doctors = snap.doctors,
                            value = d.doctorId,
                            onChange = { customs[index] = d.copy(doctorId = it) },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { customs.removeAt(index) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Supprimer ce changement")
                        }
                    }
                }
                TextButton(onClick = { customs.add(CustomDraft(snap.settings.defaultTime, snap.doctors.firstOrNull()?.id)) }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Ajouter un changement à une autre heure")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val shifts = ArrayList<Shift>()
                slots.forEachIndexed { i, slot ->
                    if (choices[i] != KEY_DEFAULT) shifts += Shift(newId(), date, slot.time, choices[i])
                }
                customs.forEach { shifts += Shift(newId(), date, it.time, it.doctorId) }
                onSave(shifts)
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )

    if (timeEditIndex in customs.indices) {
        val i = timeEditIndex
        TimeDialog(
            title = "Heure du changement",
            initial = customs[i].time,
            onDismiss = { timeEditIndex = -1 },
            onConfirm = { t ->
                customs[i] = customs[i].copy(time = t)
                timeEditIndex = -1
            },
        )
    }
}

/** Assigne, pour tous les jours choisis, une cible à chacun des créneaux qu'on veut modifier. */
@Composable
private fun AssignDialog(
    snap: Snapshot,
    slots: List<Slot>,
    dates: Set<LocalDate>,
    onDismiss: () -> Unit,
    onApply: (Map<LocalTime, CellEdit>) -> Unit,
    onClearDays: () -> Unit,
) {
    val values = remember { mutableStateListOf<String?>().apply { slots.forEach { add(KEY_KEEP) } } }
    val shown = dates.take(8).joinToString(", ") { it.shortLabel() }
    val more = dates.size - 8

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assigner ${dates.size} jour(s)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(shown + if (more > 0) " … (+$more)" else "", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Choisissez seulement les créneaux à modifier ; les autres restent tels quels.",
                    style = MaterialTheme.typography.bodySmall,
                )
                slots.forEachIndexed { i, slot ->
                    Text("${slot.time.hhmm()} · ${slot.label}", style = MaterialTheme.typography.labelMedium)
                    TargetPicker(
                        doctors = snap.doctors,
                        value = values[i],
                        onChange = { values[i] = it },
                        keepLabel = "Ne pas modifier",
                        defaultLabel = "Remettre ${defaultLabelFor(snap, slot).replaceFirstChar { it.lowercase() }}",
                    )
                }
                TextButton(onClick = onClearDays) {
                    Text("Plutôt vider ces jours (tout remettre par défaut)", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = values.any { it != KEY_KEEP },
                onClick = {
                    val edits = LinkedHashMap<LocalTime, CellEdit>()
                    slots.forEachIndexed { i, slot ->
                        when (val v = values[i]) {
                            KEY_KEEP -> Unit
                            KEY_DEFAULT -> edits[slot.time] = CellEdit.Clear
                            else -> edits[slot.time] = CellEdit.Set(v)
                        }
                    }
                    onApply(edits)
                },
            ) { Text("Appliquer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
