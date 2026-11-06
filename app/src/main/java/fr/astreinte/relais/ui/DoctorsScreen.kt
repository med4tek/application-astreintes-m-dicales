package fr.astreinte.relais.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fr.astreinte.relais.core.Codes
import fr.astreinte.relais.core.Doctor
import fr.astreinte.relais.core.Snapshot
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Composable
fun DoctorsScreen(
    snap: Snapshot,
    onEdit: ((Snapshot) -> Snapshot) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<Doctor?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Doctor?>(null) }
    val now = Instant.now()
    val zone = ZoneId.systemDefault()

    Box(modifier.fillMaxSize()) {
        if (snap.doctors.isEmpty()) {
            Column(Modifier.align(Alignment.Center).padding(32.dp)) {
                Text("Aucun médecin enregistré", style = MaterialTheme.typography.titleMedium)
                Text("Ajoutez les médecins d'astreinte avec leur numéro de portable : vous les choisirez ensuite dans le planning.")
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(snap.doctors.sortedBy { it.name.lowercase() }, key = { it.id }) { d ->
                val valid = Codes.normalizePhone(d.phone) != null
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                    containerColor = if (valid) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.errorContainer
                )) {
                    Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(if (valid) d.phone else "${d.phone}  (numéro invalide)")
                        }
                        IconButton(onClick = { editing = d }) { Icon(Icons.Filled.Edit, contentDescription = "Modifier") }
                        IconButton(onClick = { deleting = d }) { Icon(Icons.Filled.Delete, contentDescription = "Supprimer") }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Médecin") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (creating) {
        DoctorDialog(
            initial = null,
            onDismiss = { creating = false },
            onSave = { name, phone ->
                onEdit { s -> s.copy(doctors = s.doctors + Doctor(UUID.randomUUID().toString(), name, phone)) }
                creating = false
            },
        )
    }
    editing?.let { d ->
        DoctorDialog(
            initial = d,
            onDismiss = { editing = null },
            onSave = { name, phone ->
                onEdit { s -> s.copy(doctors = s.doctors.map { if (it.id == d.id) it.copy(name = name, phone = phone) else it }) }
                editing = null
            },
        )
    }
    deleting?.let { d ->
        // Un médecin encore utilisé dans le planning ne peut pas disparaître en silence
        val usedFuture = snap.shifts.count {
            it.doctorId == d.id && fr.astreinte.relais.core.Planner.startOf(it, zone) >= now
        }
        val usedAny = snap.shifts.count { it.doctorId == d.id }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Supprimer ${d.name} ?") },
            text = {
                Text(
                    when {
                        usedFuture > 0 -> "$usedFuture astreinte(s) à venir sont prévues pour ce médecin. Changez-les d'abord dans le planning (ou modifiez simplement son numéro ici)."
                        usedAny > 0 -> "Ses $usedAny astreinte(s) passée(s) apparaîtront comme « médecin supprimé »."
                        else -> "Cette fiche sera supprimée."
                    }
                )
            },
            confirmButton = {
                Button(
                    enabled = usedFuture == 0,
                    onClick = {
                        onEdit { s -> s.copy(doctors = s.doctors.filter { it.id != d.id }) }
                        deleting = null
                    },
                ) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun DoctorDialog(initial: Doctor?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var phone by remember { mutableStateOf(initial?.phone ?: "") }
    val normalized = Codes.normalizePhone(phone)
    val canSave = name.isNotBlank() && normalized != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nouveau médecin" else "Modifier le médecin") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Nom (ex. Dr Martin)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it },
                    label = { Text("Téléphone (portable)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    isError = phone.isNotBlank() && normalized == null,
                    supportingText = {
                        Text(
                            if (normalized != null) "Code envoyé : ${Codes.activate(normalized)}"
                            else if (phone.isBlank()) "Ex. 06 12 34 56 78 ou +33 6 12 34 56 78"
                            else "Numéro non reconnu"
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(enabled = canSave, onClick = { onSave(name.trim(), phone.trim()) }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
