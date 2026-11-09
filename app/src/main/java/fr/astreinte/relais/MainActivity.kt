package fr.astreinte.relais

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import fr.astreinte.relais.core.Snapshot
import fr.astreinte.relais.data.Store
import fr.astreinte.relais.sys.Relay
import fr.astreinte.relais.ui.AppTheme
import fr.astreinte.relais.ui.DoctorsScreen
import fr.astreinte.relais.ui.ImportScreen
import fr.astreinte.relais.ui.PlanningScreen
import fr.astreinte.relais.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    /** Incrémenté à chaque retour au premier plan, pour relire l'état des permissions. */
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Store.init(this)
        setContent {
            AppTheme {
                val snap by Store.snapshot.collectAsState()
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            listOf(
                                "Planning" to Icons.Filled.DateRange,
                                "Médecins" to Icons.Filled.Person,
                                "Import" to Icons.Filled.Share,
                                "Réglages" to Icons.Filled.Settings,
                            ).forEachIndexed { i, (label, icon) ->
                                NavigationBarItem(
                                    selected = tab == i,
                                    onClick = { tab = i },
                                    icon = { Icon(icon, contentDescription = null) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    val m = Modifier.padding(padding)
                    when (tab) {
                        0 -> PlanningScreen(snap, ::edit, onGoToSettings = { tab = 3 }, modifier = m)
                        1 -> DoctorsScreen(snap, ::edit, modifier = m)
                        2 -> ImportScreen(snap, ::edit, modifier = m)
                        else -> SettingsScreen(snap, resumeTick, ::edit, modifier = m)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
        // Rattrape un éventuel retard (téléphone éteint à l'heure de la relève) et réarme l'alarme
        Relay.reconcile(this, Relay.Trigger.APP)
    }

    /** Toute modification passe par ici : on enregistre, puis on réévalue ce qu'il faut appliquer. */
    private fun edit(block: (Snapshot) -> Snapshot) {
        Store.update(block)
        Relay.reconcile(this, Relay.Trigger.EDIT)
    }
}
