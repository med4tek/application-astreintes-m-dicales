package fr.astreinte.relais

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import fr.astreinte.relais.core.Author
import fr.astreinte.relais.core.Mode
import fr.astreinte.relais.core.Snapshot
import fr.astreinte.relais.data.Store
import fr.astreinte.relais.sys.Relay
import fr.astreinte.relais.ui.AppTheme
import fr.astreinte.relais.ui.DoctorsScreen
import fr.astreinte.relais.ui.HelpScreen
import fr.astreinte.relais.ui.ImportScreen
import fr.astreinte.relais.ui.ModeChooserDialog
import fr.astreinte.relais.ui.PlanningScreen
import fr.astreinte.relais.ui.SettingsScreen
import fr.astreinte.relais.ui.SyncScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    PLANNING("Planning", Icons.Filled.DateRange),
    DOCTORS("Médecins", Icons.Filled.Person),
    IMPORT("Import", Icons.Filled.Share),
    SYNC("Synchro", Icons.Filled.Refresh),
    SETTINGS("Réglages", Icons.Filled.Settings),
    HELP("Aide", Icons.Filled.Info),
}

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
                val viewer = snap.settings.isViewer
                // L'onglet « Synchro » n'existe qu'en mode consultation
                val tabs = if (viewer) Tab.entries else Tab.entries.filter { it != Tab.SYNC }
                var tabName by rememberSaveable { mutableStateOf(Tab.PLANNING.name) }
                val tab = tabs.firstOrNull { it.name == tabName } ?: Tab.PLANNING

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            tabs.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = { tabName = t.name },
                                    icon = { Icon(t.icon, contentDescription = null) },
                                    label = { Text(t.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    val m = Modifier.padding(padding)
                    when (tab) {
                        Tab.PLANNING -> PlanningScreen(
                            snap, ::edit,
                            onGoToSettings = { tabName = Tab.SETTINGS.name },
                            onGoToSync = { tabName = Tab.SYNC.name },
                            modifier = m,
                        )
                        Tab.DOCTORS -> DoctorsScreen(snap, ::edit, modifier = m, readOnly = viewer)
                        Tab.IMPORT -> ImportScreen(snap, { block -> editAs(Author(Author.VIA_IMPORT), block) }, modifier = m)
                        Tab.SYNC -> SyncScreen(snap, resumeTick, ::edit, modifier = m)
                        Tab.SETTINGS -> SettingsScreen(snap, resumeTick, ::edit, modifier = m)
                        Tab.HELP -> HelpScreen(snap, modifier = m)
                    }
                }

                // Premier lancement : le rôle du téléphone doit être choisi
                if (snap.settings.mode == Mode.NONE) {
                    ModeChooserDialog { mode -> edit { it.copy(settings = it.settings.copy(mode = mode)) } }
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
    private fun edit(block: (Snapshot) -> Snapshot) = editAs(null, block)

    private fun editAs(by: Author?, block: (Snapshot) -> Snapshot) {
        Store.update(by, block)
        Relay.reconcile(this, Relay.Trigger.EDIT)
    }
}
