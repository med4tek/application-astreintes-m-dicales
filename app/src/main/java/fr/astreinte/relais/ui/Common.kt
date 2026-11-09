package fr.astreinte.relais.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.astreinte.relais.core.Doctor
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val FR = Locale.FRENCH

fun YearMonth.label(): String =
    DateTimeFormatter.ofPattern("LLLL yyyy", FR).format(this).replaceFirstChar { it.uppercase() }

fun LocalDate.weekdayShort(): String =
    dayOfWeek.getDisplayName(TextStyle.SHORT, FR).trimEnd('.').replaceFirstChar { it.uppercase() }

fun LocalDate.longLabel(): String =
    DateTimeFormatter.ofPattern("EEEE d MMMM", FR).format(this)

fun LocalDate.shortLabel(): String =
    DateTimeFormatter.ofPattern("EEE d MMM", FR).format(this)

fun LocalTime.hhmm(): String = "%02d:%02d".format(hour, minute)

/** "9h", "12h", "14h30" : compact pour les en-têtes de colonnes. */
fun LocalTime.shortHour(): String = if (minute == 0) "${hour}h" else "%dh%02d".format(hour, minute)

/** Valeurs spéciales du sélecteur (en plus d'un identifiant de médecin ou de null = aucun renvoi). */
const val KEY_DEFAULT = "\u0000default"
const val KEY_KEEP = "\u0000keep"

/**
 * Sélecteur de cible du renvoi. [value] : un identifiant de médecin, `null` (= aucun renvoi),
 * [KEY_DEFAULT] ou [KEY_KEEP]. [onChange] reçoit la même convention.
 *
 * @param keepLabel  si non nul, propose « ne pas modifier » (édition en lot)
 * @param defaultLabel si non nul, propose « valeur par défaut / pas de changement »
 * @param allowOff   propose « Aucun renvoi » (désactive le renvoi)
 */
@Composable
fun TargetPicker(
    doctors: List<Doctor>,
    value: String?,
    onChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    keepLabel: String? = null,
    defaultLabel: String? = null,
    allowOff: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    val label = when (value) {
        KEY_KEEP -> keepLabel ?: "—"
        KEY_DEFAULT -> defaultLabel ?: "—"
        null -> "Aucun renvoi"
        else -> doctors.firstOrNull { it.id == value }?.name ?: "(médecin supprimé)"
    }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (keepLabel != null) {
                DropdownMenuItem(text = { Text(keepLabel) }, onClick = { onChange(KEY_KEEP); open = false })
            }
            if (defaultLabel != null) {
                DropdownMenuItem(text = { Text(defaultLabel) }, onClick = { onChange(KEY_DEFAULT); open = false })
            }
            doctors.forEach { d ->
                DropdownMenuItem(text = { Text(d.name) }, onClick = { onChange(d.id); open = false })
            }
            if (allowOff) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Aucun renvoi") }, onClick = { onChange(null); open = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(
    title: String,
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimeInput(state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateDialog(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
    title: String = "",
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let {
                    onConfirm(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    ) {
        DatePicker(
            state = state,
            title = { if (title.isNotEmpty()) Text(title, Modifier.padding(start = 24.dp, top = 16.dp)) },
        )
    }
}

/** Petite ligne "libellé ........ contrôle" réutilisée dans les dialogues. */
@Composable
fun LabeledRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(76.dp))
        content()
    }
}
