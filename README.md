# Astreinte Relais

Application Android personnelle (hors Play Store) qui change toute seule le **renvoi d'appel** du téléphone d'astreinte médicale selon un planning mois par mois.

À l'heure de chaque changement, le téléphone compose le code `**21*NUMÉRO#` (comme le composeur). Pour désactiver, `##21#`.

## Utilisation

1. **Médecins** : ajoutez chaque médecin avec son numéro. La fiche **Standard** existe d'office (utilisée par défaut à 9h et 14h) mais **sans numéro** : saisissez-le une fois sur le téléphone relais (onglet Médecins → Standard). Les téléphones de consultation le reçoivent par synchronisation. Vous pouvez le changer ensuite (jour férié, etc.).
2. **Planning** : une grille, une ligne par jour, une colonne par créneau de la journée (par défaut **9h, 12h, 14h, 17h, 18h**).
   - **9h et 14h** : standard par défaut les jours ouvrés, sans rien saisir. Pour un jour particulier, touchez la case et choisissez autre chose.
   - **Week-ends et jours fériés** (calculés par l'app, métropole) : **une seule personne pour toute la journée**, pas de standard. Touchez la case de **9h** et choisissez le médecin : il couvre toute la journée (jusqu'au prochain changement, donc jusqu'au lundi 9h où le standard reprend). Une modification en cours de journée se fait case par case. Un week-end ou férié à venir sans personne saisie est marqué **« à saisir »** ; en attendant, c'est l'astreinte de la veille au soir qui continue.
   - **12h, 17h, 18h** : touchez la case, choisissez le médecin. L'astreinte de 18h dure jusqu'au lendemain 9h, où le standard reprend tout seul.
   - Touchez le **numéro du jour** pour éditer toute la journée (et ajouter un changement à une heure libre).
   - Fond coloré = saisi à la main ; fond gris = valeur par défaut ; texte pâle = hérité du créneau précédent.
   - **Sélectionner plusieurs jours** (ou appui long) : cochez des jours (raccourcis Tout le mois, Lun–ven, Week-ends, Période…), puis **Assigner…** : un sélecteur par créneau, « Ne pas modifier » par défaut.
   - Les heures et intitulés des créneaux se changent dans **Réglages → Horaires de la journée**.
3. **Import** : remplir un mois depuis une photo (voir ci-dessous).
4. **Réglages** : accordez les autorisations, **testez** avec « Vérifier le renvoi actuellement en place » (`*#21#`), puis activez la **Bascule automatique**.

## Import depuis une photo de planning

1. Onglet **Import** : choisissez le mois, ajoutez au besoin des précisions (« la colonne Garde = 18:00 »), puis **Copier** ou **Partager…** la consigne.
2. Envoyez la consigne **avec la photo** à un assistant IA (Claude, ChatGPT…). Il répond par un bloc JSON.
3. Collez la réponse dans l'app, **Analyser** : un aperçu jour par jour s'affiche. Les noms non reconnus se rattachent à un médecin de votre liste (ou s'ignorent, ou deviennent un nouveau médecin).
   La consigne donne à l'assistant les noms (prénom et nom) des médecins déjà enregistrés et lui demande la liste de ceux qu'il lit sur la photo : l'aperçu propose d'**ajouter les nouveaux, sans numéro** (à compléter à la main dans l'onglet Médecins). Les **numéros de téléphone ne sont jamais demandés à l'assistant** ni envoyés avec la consigne. Les correspondances que vous validez (« Alice » = tel médecin) sont mémorisées pour les imports suivants.
4. **Appliquer** : seuls les créneaux présents dans la réponse sont remplacés. Relisez toujours le résultat dans le Planning : un LLM peut mal lire une photo.

## Commande par SMS (optionnelle, désactivée par défaut)

À activer dans **Réglages → Commande par SMS** : un code PIN, la permission SMS, et éventuellement la réponse par SMS.
Un SMS n'est pris en compte que si l'expéditeur est **autorisé** (médecins enregistrés et/ou numéros ajoutés) **et** que le PIN est correct. Les autres SMS sont ignorés ; les refus apparaissent dans le journal.

| SMS | Effet |
|---|---|
| `AST 1234 12/11 18=Martin` | 18h du 12/11 : Martin |
| `AST 1234 12/11 12=Dupont 18=Martin` | plusieurs créneaux |
| `AST 1234 12/11-15/11 18=Martin` | une période (bornes incluses) |
| `AST 1234 12/11,14/11 18=Martin` | plusieurs jours |
| `AST 1234 12/11 18=Martin 13/11 18=Durand` | plusieurs groupes |
| `AST 1234 demain 9=STD 18=OFF` | `STD` = standard, `OFF` = aucun renvoi, `auj`/`demain` |
| `AST 1234 12/11 RAZ` | efface la journée (retour aux valeurs par défaut) ; `12=DEF` n'efface qu'un créneau |
| `AST 1234 ?` | répond avec l'état du renvoi |
| `AST 1234 ? demain` | répond avec le planning de ces jours (7 max) ; accepte `12/11`, `12/11-14/11`, `12/11,14/11`, `semaine` |

Exemple de réponse :
```
lun 09/11: 9h STD 12h Alice 14h STD 17h STD 18h Alice
mar 10/11: 9h STD 12h Bruno 14h STD 17h STD 18h Bruno
```
(`STD` = standard, `OFF` = aucun renvoi ; pour chaque créneau, la personne réellement en vigueur à cette heure.) Les consultations exigent « Répondre par SMS » dans les réglages.

Le nom du médecin est reconnu sans titre ni accents (« martin » → Dr Martin) ; un nom ambigu est refusé. Si la modification touche l'astreinte en cours, le renvoi est appliqué immédiatement.
**Attention RCS** : la commande doit partir en **vrai SMS**. Entre deux téléphones compatibles, Google Messages envoie en « chat » (RCS) sans prévenir, et l'app ne reçoit alors rien. Le message doit s'afficher « SMS » et non « Chat » ; sinon désactivez les fonctionnalités de chat. Le diagnostic des Réglages (date du dernier SMS vu) permet de le vérifier.
**Sécurité** : le PIN voyage en clair par SMS et un numéro d'expéditeur peut être usurpé : choisissez un PIN que vous n'utilisez nulle part ailleurs.
Android 13+ peut bloquer la permission SMS d'une app installée à la main : infos de l'app → ⋮ → « Autoriser les paramètres restreints ».

## Deux rôles : relais et consultation

Au premier lancement, l'app demande le rôle du téléphone (modifiable dans **Réglages → Rôle de ce téléphone**).

| | **Relais** (téléphone d'astreinte) | **Consultation** (téléphones perso) |
|---|---|---|
| Renvoi d'appel | Appliqué aux heures prévues | **Jamais** |
| Commandes SMS | Reçues et exécutées | – |
| Messages aux médecins | Envoyés | – |
| Planning | Modifiable | Modifiable, puis envoyé au relais |
| Médecins | Modifiables (fait foi) | Lecture seule, reçus du relais |
| Onglet « Synchro » | – | Oui |

**iPhone** : l'application n'existe que pour Android, sans version iOS prévue. Un médecin qui a un iPhone n'a donc pas le mode consultation : il se débrouille avec les commandes par SMS (qui fonctionnent depuis n'importe quel téléphone) et reçoit les messages automatiques comme les autres.

Un fichier de données créé par une version d'avant les rôles reste en mode **relais** : rien ne change pour le téléphone d'astreinte existant.

## Synchronisation relais ↔ consultation (par SMS)

Le relais fait foi. Sur le téléphone de consultation, onglet **Synchro** :
1. Renseignez le **numéro du relais** et le **PIN** (le même que celui du relais), autorisez les SMS.
2. **Demander le planning au relais** : le relais répond par quelques SMS (médecins, créneaux, planning des ~6 prochaines semaines) ; le planning local est alors remplacé.
3. Modifiez le planning comme d'habitude (grille, sélection multiple, import photo…).
4. **Envoyer mes modifications au relais** : seules les cases modifiées partent (en commandes `AST … PUSH`). Le relais répond « OK » pour chaque SMS ; quand tout est acquitté, l'app affiche **« À jour avec le relais »** et notifie.

À savoir :
- Le **numéro du consultant doit être autorisé sur le relais** (médecin enregistré, ou « Autres numéros autorisés ») et la commande SMS activée avec un PIN.
- Le relais doit avoir la permission d'envoi de SMS (il répond toujours à une synchro, même si « Répondre par SMS » est coupé).
- Un envoi ou une demande sans réponse est abandonné après 10 minutes ; rien n'est perdu côté consultation.
- Les ajouts de médecins se font **sur le relais** (leurs numéros ne transitent que dans le sens relais → consultation).
- Chaque synchro coûte quelques SMS (≈ 3 par tranche de 430 caractères).
- Vrai SMS obligatoire (pas de RCS), comme pour les autres commandes.

## Historique : qui modifie le planning

Chaque modification du planning est enregistrée dans le fichier de données : **date et heure, auteur, canal, cases modifiées** (avant → après).

| Canal | Auteur inscrit |
|---|---|
| Grille, édition d'une journée, sélection multiple | le nom saisi dans **Réglages → Mon nom** (« Ce téléphone » à défaut) |
| Import depuis une photo | idem, canal « Import photo » |
| Commande par SMS (relais) | le médecin et son numéro d'expéditeur |
| Synchro reçue d'un téléphone de consultation (relais) | le médecin et son numéro |
| Planning reçu du relais (consultation) | « Relais », en une ligne de résumé |

Consultable et partageable dans **Réglages → Historique des modifications du planning** (400 entrées conservées, 40 lignes de détail par entrée). Le relais fait foi : son historique est la référence ; chaque téléphone de consultation garde celui de ses propres modifications. Cet historique est local : il n'est pas transmis par SMS.

## Messages automatiques aux médecins (relais)

**Réglages → Messages automatiques aux médecins** : le relais envoie des SMS aux médecins d'astreinte. Quatre messages sont proposés d'origine :

| Heure | Destinataire | Message |
|---|---|---|
| 12h | médecin de 12h | « Tu es d'astreinte ce midi (12h-14h). La bascule vers ton numéro est faite. » |
| 17h | médecin de 17h | « Tu es d'astreinte cet après-midi (17h-18h). La bascule est faite. » |
| 17h | médecin de **18h** | « Rappel : tu es d'astreinte ce soir à partir de 18h jusqu'à demain 9h. » |
| 18h | médecin de 18h | « La bascule est faite : ton astreinte commence (18h-demain 9h). » |
| 9h, **week-ends et fériés seulement** | médecin du jour | « Tu es d'astreinte toute la journée. La bascule vers ton numéro est faite. » |

Les quatre premiers ne partent que les jours ouvrés, le dernier que les week-ends et jours fériés (réglable message par message).

- Les messages « bascule faite » ne partent que lorsque le renvoi vers ce médecin est **réellement en place**.
- Chacun est activable, modifiable (texte, heure, créneau visé) ; variables `{nom}` `{debut}` `{fin}` `{date}`.
- Jamais envoyé : au standard, s'il n'y a personne d'astreinte à ce créneau, deux fois, ou plus de 90 minutes après l'heure prévue. Un rappel annoncé à l'avance n'est plus envoyé une fois le créneau commencé.
- Nécessite la bascule automatique active et la permission d'envoi de SMS. Le journal indique chaque message (ou la raison de son non-envoi).
- Évitez accents et symboles spéciaux : un SMS en caractères spéciaux est découpé en messages plus courts.

## Documentation

- [Procédure qualité (Word)](docs/Procedure_Astreinte_Relais.docx) : principe, fonctionnement, captures d'écran, traçabilité, mode dégradé, risques, annexe technique. Version publique : les numéros de téléphone et informations internes de l'établissement en sont retirés.

## Installation (Android 15)

1. Copiez `AstreinteRelais.apk` sur le téléphone et ouvrez-le (autoriser l'installation depuis cette source).
2. Au premier lancement, dans l'onglet Réglages, accordez : Téléphone, Notifications, Alarmes exactes, et **Batterie → Non restreinte** (important : sinon le système peut retarder l'alarme).
3. N'utilisez pas « Forcer l'arrêt » sur l'app : Android annule alors toutes ses alarmes jusqu'à la prochaine ouverture.

## Fiabilité

- Alarme exacte à chaque relève ; réarmée après redémarrage, mise à jour de l'app, changement d'heure ou de fuseau.
- Au lancement de l'app, rattrapage si une relève a été manquée (téléphone éteint).
- Échec : nouvel essai chaque minute (5 fois), puis notification d'alerte avec un bouton qui ouvre le composeur prérempli.
- Journal des 20 derniers envois dans les Réglages.
- Interrupteur général : tant qu'il est coupé, l'app n'envoie rien.

## Limites à connaître

- La réponse de l'opérateur est affichée telle quelle ; l'app ne sait pas distinguer un « refus » rédigé en texte libre d'une confirmation. Le test `*#21#` des Réglages permet de vérifier le résultat réel.
- Double SIM : choisissez la SIM dans les Réglages si besoin.
- Un code USSD exige du réseau au moment de la relève.
- Il n'existe pas d'API Android publique pour régler le renvoi sans USSD ; c'est pourquoi l'app s'appuie sur ce mécanisme.

## Compiler

Prérequis : JDK 17+ et SDK Android (plateforme 34).

```bash
./gradlew testDebugUnitTest      # tests unitaires
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
```

### Signature

Si `keystore.properties` existe à la racine (fichier ignoré par git), l'APK est signé avec votre clé. Sinon il est signé avec la clé de debug.
**Sauvegardez `astreinte.keystore` et `keystore.properties`** : sans la même clé, une mise à jour exige de désinstaller l'app (donc de perdre le planning).
