package fr.astreinte.relais.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.astreinte.relais.core.Mode
import fr.astreinte.relais.core.Snapshot

/** Aide intégrée : comment utiliser l'application, côté relais et côté consultation, et les fonctions principales. */
@Composable
fun HelpScreen(snap: Snapshot, modifier: Modifier = Modifier) {
    val mode = snap.settings.mode
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Aide", style = MaterialTheme.typography.titleLarge)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when (mode) {
                        Mode.RELAY -> "Ce téléphone est le RELAIS (téléphone d'astreinte)."
                        Mode.VIEWER -> "Ce téléphone est en CONSULTATION (téléphone personnel)."
                        else -> "Le rôle de ce téléphone n'est pas encore choisi."
                    },
                    fontWeight = FontWeight.Bold,
                )
                Text("Le rôle se change dans Réglages → Rôle de ce téléphone. Touchez un titre ci-dessous pour ouvrir ou fermer une rubrique.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Section("À quoi sert l'application ?", open = mode == Mode.NONE) {
            P("Elle change TOUTE SEULE le renvoi d'appel du téléphone d'astreinte, selon un planning : à l'heure prévue, le téléphone d'astreinte renvoie les appels vers le médecin d'astreinte (ou vers le standard).")
            P("Deux sortes de téléphones :")
            Bullet("Le RELAIS : le téléphone d'astreinte. Il applique les renvois, reçoit les commandes par SMS et prévient les médecins. Il en faut UN seul.")
            Bullet("Les téléphones de CONSULTATION : vos téléphones personnels. Ils servent à voir et modifier le planning, et se synchronisent par SMS avec le relais. Jamais de renvoi d'appel depuis eux.")
            P("Le relais fait foi : en cas de doute, c'est son planning qui compte.")
        }

        Section("Côté RELAIS : mise en route", open = mode == Mode.RELAY) {
            Step(1, "Réglages → choisissez le rôle « Relais ».")
            Step(2, "Réglages → Autorisations : accordez Téléphone, Notifications, Alarmes exactes, et mettez la Batterie sur « Non restreinte » (sinon Android peut retarder la bascule).")
            Step(3, "Onglet Médecins : ajoutez chaque médecin avec son numéro de portable. Sur la fiche « Standard », saisissez le numéro du standard (une seule fois).")
            Step(4, "Remplissez le planning (voir « Remplir le planning du mois »).")
            Step(5, "Réglages → « Vérifier le renvoi actuellement en place » pour tester, puis activez la « Bascule automatique ». À partir de là, le renvoi suit le planning.")
            Step(6, "Facultatif : Réglages → Commande par SMS (PIN + permission SMS) et Messages automatiques aux médecins.")
            P("Important : n'utilisez jamais « Forcer l'arrêt » sur l'application, Android annulerait toutes les alarmes jusqu'à la prochaine ouverture.")
        }

        Section("Côté CONSULTATION : mise en route", open = mode == Mode.VIEWER) {
            Step(1, "Réglages → choisissez le rôle « Consultation ».")
            Step(2, "Onglet Synchro : saisissez le NUMÉRO du téléphone relais et le PIN (le même que sur le relais), puis autorisez l'envoi de SMS.")
            Step(3, "Votre numéro doit être AUTORISÉ sur le relais (médecin enregistré avec son numéro, ou « Autres numéros autorisés » dans ses réglages).")
            Step(4, "« Demander le planning au relais » : le planning et la liste des médecins arrivent par SMS (quelques messages).")
            Step(5, "Modifiez le planning comme d'habitude. Puis « Envoyer mes modifications au relais » : seules les cases modifiées partent. Attendez « À jour avec le relais ».")
            P("L'application n'existe que pour Android. Un médecin sous iPhone peut quand même interroger et modifier le planning par SMS (voir plus bas).")
        }

        Section("Remplir le planning du mois") {
            P("Trois façons, au choix :")
            Bullet("Fichier Excel (le plus fiable) : onglet Import → « Choisir le fichier Excel… ». L'application lit les cases COLORÉES du tableau : 12h = case colorée du midi, 17h = colonne 17-18h, 18h = colonne APRES MIDI, week-ends et fériés = case MATIN. Vérifiez l'aperçu puis « Appliquer ».")
            Bullet("Photo + assistant IA : onglet Import → copiez la consigne, envoyez-la avec une capture du tableau à un assistant, collez sa réponse, « Analyser ». Relisez toujours le résultat.")
            Bullet("À la main : onglet Planning. Touchez une case pour choisir le médecin, touchez le numéro du jour pour modifier toute la journée, ou « Sélectionner plusieurs jours » (appui long sur un jour) puis « Assigner… ».")
            P("Les noms du fichier ou de la photo (« Dr Martin ») sont rattachés aux médecins de la liste ; si un nom n'est pas reconnu, l'aperçu vous demande de choisir (le choix est mémorisé).")
        }

        Section("Les règles de la journée") {
            Bullet("Jours ouvrés : 9h et 14h = le standard, sans rien saisir. 12h, 17h, 18h = le médecin que vous choisissez. L'astreinte de 18h dure jusqu'au lendemain 9h.")
            Bullet("Week-ends et jours fériés : UNE SEULE personne pour toute la journée, saisie à 9h. Elle couvre la journée entière jusqu'au prochain changement (en général le lundi 9h, où le standard reprend).")
            Bullet("Un week-end ou un férié à venir sans personne saisie est marqué « à saisir » : en attendant, l'astreinte de la veille continue.")
            Bullet("Couleurs de la grille : fond coloré = saisi à la main ; fond gris = valeur par défaut ; texte pâle = hérité du créneau précédent.")
        }

        Section("Qui est d'astreinte ? (par SMS)") {
            P("Depuis n'importe quel téléphone autorisé, envoyez UN SMS au téléphone relais : le code, puis une ou plusieurs dates.")
            Code("AST 1234 demain")
            Code("AST 1234 auj")
            Code("AST 1234 12/11,14/11")
            Code("AST 1234 12/11-15/11")
            Code("AST 1234 semaine")
            P("1234 est à remplacer par votre PIN. La réponse liste, jour par jour, les bascules (7 jours maximum), par exemple :")
            Code("jeu 12/11: 0h STD, 12h Martin (en cours), 14h STD, 17h Durand, 18h Martin")
            Code("sam 14/11: Dupont toute la journee")
            P("« 0h » = l'astreinte qui court depuis la veille. STD = standard, OFF = aucun renvoi. « (en cours) » marque la bascule actuelle pour aujourd'hui.")
            P("Pour connaître seulement l'état du renvoi en ce moment : AST 1234 ?")
            P("Il faut que votre numéro soit autorisé sur le relais et que le SMS parte en VRAI SMS (pas en « Chat » / RCS).")
        }

        Section("Modifier le planning par SMS") {
            Code("AST 1234 12/11 18=Martin")
            P("18h du 12/11 : Martin.")
            Code("AST 1234 12/11 12=Dupont 18=Martin")
            Code("AST 1234 12/11-15/11 18=Martin")
            Code("AST 1234 demain 9=STD 18=OFF")
            Code("AST 1234 12/11 RAZ")
            P("STD = standard, OFF = aucun renvoi, RAZ = efface la journée (retour aux valeurs par défaut). Si la modification touche l'astreinte en cours, le renvoi est appliqué tout de suite.")
        }

        Section("Refiler une astreinte à un collègue") {
            P("Modifiez simplement la case du planning (dans l'application, ou par SMS). Le relais prévient automatiquement :")
            Bullet("le collègue qui reprend : « tu es d'astreinte du … au … » ;")
            Bullet("celui qui cède : « tu n'es plus d'astreinte du … au … ».")
            P("Si c'est l'astreinte EN COURS qui change, le nouveau médecin est prévenu une fois la bascule faite, avec la fin de son astreinte. Pour une astreinte à venir, la prévenance vaut jusqu'à 7 jours.")
        }

        Section("Messages automatiques aux médecins") {
            P("Réglages → Messages automatiques (sur le relais). Un SEUL message par période d'astreinte, au début : « tu es d'astreinte du ven 13/11 à 18h au lun 16/11 à 9h ». Un week-end confié à la même personne reste un seul message.")
            P("Les textes se modifient dans les Réglages. Rien n'est envoyé au standard, ni deux fois, ni si la bascule n'est pas faite.")
        }

        Section("Historique : qui a modifié le planning ?") {
            P("Réglages → Historique des modifications du planning : date, auteur, canal (application, import, SMS, synchro) et cases changées. Saisissez « Mon nom » pour être identifié sur ce téléphone. Sur le relais, les SMS indiquent le médecin et son numéro. Le bouton « Partager l'historique » exporte le texte.")
        }

        Section("Si ça ne marche pas") {
            Bullet("La bascule a échoué : l'application réessaie chaque minute (5 fois), puis une notification rouge propose de composer le code à la main. Journal : Réglages (relais).")
            Bullet("Vérifier ce qui est réellement en place : Réglages → « Vérifier le renvoi actuellement en place ».")
            Bullet("Un SMS de commande est ignoré : vérifiez le PIN, que votre numéro est autorisé, et que le message part en SMS et non en « Chat » (RCS). Réglages → Diagnostic donne la date du dernier SMS vu.")
            Bullet("Android bloque la permission SMS d'une application installée à la main : infos de l'app → menu ⋮ → « Autoriser les paramètres restreints ».")
            Bullet("La bascule est en retard : Batterie → « Non restreinte », alarmes exactes autorisées, téléphone d'astreinte allumé, avec du réseau.")
            Bullet("Double SIM : choisissez la SIM dans les Réglages. Un renvoi d'appel exige du réseau à l'heure de la bascule.")
            Bullet("Avant tout test, vous pouvez couper la « Bascule automatique » : plus rien n'est envoyé à l'opérateur tant qu'elle est coupée.")
        }
    }
}

// ---------------------------------------------------------------------- briques

@Composable
private fun Section(title: String, open: Boolean = false, content: @Composable () -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(open) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Fermer" else "Ouvrir",
                )
            }
            if (expanded) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun P(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)

@Composable
private fun Bullet(text: String) = Text("•  $text", style = MaterialTheme.typography.bodyMedium)

@Composable
private fun Step(n: Int, text: String) = Text("$n.  $text", style = MaterialTheme.typography.bodyMedium)

@Composable
private fun Code(text: String) = Text(
    text,
    fontFamily = FontFamily.Monospace,
    style = MaterialTheme.typography.bodySmall,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(start = 12.dp),
)
