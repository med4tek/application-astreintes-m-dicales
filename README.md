# Astreinte Relais

Application Android personnelle (hors Play Store) qui change toute seule le **renvoi d'appel** du téléphone d'astreinte médicale selon un planning mois par mois.

À l'heure de chaque changement, le téléphone compose le code `**21*NUMÉRO#` (comme le composeur). Pour désactiver, `##21#`.

## Télécharger l'application

**[⬇ Télécharger la dernière version (APK Android)](https://github.com/med4tek/application-astreintes-m-dicales/raw/main/apk/AstreinteRelais.apk)** : version 3.8.0, pour Android 8 ou plus (testée sous Android 15).

1. Ouvrez le fichier téléchargé sur le téléphone et autorisez l'installation depuis cette source.
2. Play Protect peut afficher un avertissement (l'application n'est pas publiée sur le Play Store) : « Installer quand même ».
3. Android 13 et plus : si la permission SMS est grisée, infos de l'app → ⋮ → « Autoriser les paramètres restreints ».
4. **Mise à jour** : téléchargez la nouvelle version et ouvrez-la par-dessus l'ancienne (même signature, le planning est conservé). Ne désinstallez pas avant.

Vérification (facultative) : la somme de contrôle SHA-256 du fichier et l'empreinte du certificat de signature sont dans [`apk/SHA256.txt`](apk/SHA256.txt) ; le fichier doit donner `efc77ba1f679e33bccde97c5276b6ff9dcfb32f95f2a641027458281cf45e97a`. Le certificat se contrôle avec `apksigner verify --print-certs apk/AstreinteRelais.apk`.


## Aide intégrée

L'onglet **Aide** de l'application explique, en langage simple, la mise en route côté **relais** et côté **consultation**, comment remplir le planning (fichier Excel, photo, à la main), les règles de la journée et des week-ends, comment savoir qui est d'astreinte par SMS, refiler une astreinte, les messages automatiques et le dépannage.

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

## Import direct d'un fichier Excel (sans IA, recommandé si vous avez le .xlsx)

Onglet **Import** → « Choisir le fichier Excel… » (après avoir choisi le mois). L'application lit **les couleurs des cases** dans le fichier, ce qu'un assistant IA fait mal sur une photo. Modèle reconnu : une ligne par jour (colonne A = jour, colonne B = numéro) et, pour chaque médecin (« Dr … »), un bloc de colonnes MATIN, une colonne sans titre (midi), APRES MIDI et parfois 17-18h. Seules les cases **colorées** comptent (un « x » ou « ABS » sans couleur est ignoré) :

| Créneau | Médecin retenu |
|---|---|
| 9h et 14h (jours ouvrés) | toujours le standard : rien à lire |
| 12h | case colorée de la colonne du midi |
| 17h | case colorée de la colonne 17-18h (la jaune s'il y en a deux) |
| 18h | case colorée de la colonne APRES MIDI |
| week-end et jour férié | case MATIN colorée, sur 9h (la personne couvre la journée) |

L'onglet est choisi d'après le titre (« MOIS DE NOVEMBRE 2026 ») ou son numéro. Les cas douteux (plusieurs cases colorées, jour sans case) sont listés dans l'aperçu ; les noms (« Dr Durand ») se rattachent aux médecins de la liste, comme pour une photo.

## Import depuis une photo de planning

1. Onglet **Import** : choisissez le mois, ajoutez au besoin des précisions sur la structure de votre planning (« la colonne Garde = 18:00 »), puis **Copier** ou **Partager…** la consigne. Les précisions sont **mémorisées** : on les saisit une fois pour toutes. Le bouton « Insérer le modèle » propose un texte prêt à adapter pour un tableau à une ligne par jour et un bloc de colonnes par médecin (MATIN, midi, APRES MIDI, 17-18h) où seules les cases **colorées** comptent (9h et 14h : toujours le standard ; 12h, 17h, 18h : le médecin dont la case est colorée dans la colonne correspondante ; week-ends et fériés : le médecin du matin sur 9h). Envoyez à l'assistant une **capture nette du tableau** : les couleurs ne passent pas dans un export texte.
2. Envoyez la consigne **avec la photo** à un assistant IA (Claude, ChatGPT…). Il répond par un bloc JSON.
3. Collez la réponse dans l'app, **Analyser** : un aperçu jour par jour s'affiche. Les noms non reconnus se rattachent à un médecin de votre liste (ou s'ignorent, ou deviennent un nouveau médecin).
   La consigne donne à l'assistant les noms (prénom et nom) des médecins déjà enregistrés et lui demande la liste de ceux qu'il lit sur la photo : l'aperçu propose d'**ajouter les nouveaux, sans numéro** (à compléter à la main dans l'onglet Médecins). Les **numéros de téléphone ne sont jamais demandés à l'assistant** ni envoyés avec la consigne. Sur la photo, les médecins sont souvent désignés par leur **nom** alors que votre liste peut ne contenir que des **prénoms** : pour chaque nom non reconnu, l'aperçu propose de choisir le médecin déjà enregistré (« Dr Durand » = Alice). Son nom est alors complété (« Alice Durand ») et le lien est mémorisé : les imports suivants le reconnaissent tout seuls, et la consigne donne le nom complet à l'assistant.
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
| `AST 1234 demain` | **qui est d'astreinte ce jour-là, avec les bascules** (le code + une ou plusieurs dates suffisent) ; accepte `auj`, `12/11`, `12/11,14/11`, `12/11-14/11`, `semaine` (7 jours max) |
| `AST 1234 ?` | répond avec l'état actuel du renvoi |
| `AST 1234 ? demain` | idem `AST 1234 demain` (le `?` est facultatif) |

Exemple de réponse à `AST 1234 12/11,14/11` :
```
jeu 12/11: 0h STD, 12h Alice (en cours), 14h STD, 17h Bruno, 18h Alice
sam 14/11: Claire toute la journee
```
Chaque ligne liste, dans l'ordre, les **bascules** du jour : `0h` = l'astreinte qui court depuis la veille ; `STD` = standard, `OFF` = aucun renvoi ; `(en cours)` marque la bascule actuelle pour aujourd'hui. Un jour sans bascule (week-end confié à une seule personne) donne « Claire toute la journee ». La réponse est **toujours** envoyée à un expéditeur autorisé avec le bon PIN (le réglage « Répondre par SMS » ne concerne que les confirmations de modification).

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

**Réglages → Messages automatiques aux médecins** : le relais envoie des SMS aux médecins d'astreinte. **Un seul message par période d'astreinte**, qui dit **de quand à quand** :

> Bonjour Dr Martin, tu es d'astreinte du ven 13/11 a 18h au lun 16/11 a 9h. La bascule vers ton numero est faite.

- Une **période** est une suite ininterrompue de créneaux confiés au même médecin. Un week-end confié à la même personne (vendredi 18h, samedi 9h, 14h…) reste **un seul message**, sans rappel à chaque créneau.
- La **fin** est le prochain changement de personne (le standard qui reprend le lundi 9h, un autre médecin…).
- Le message part au **début** de la période, **une fois la bascule vers ce médecin réellement faite**. Jamais pour le standard, jamais s'il n'y a personne d'astreinte, jamais deux fois, jamais plus de 90 minutes après le début.
- **Si l'astreinte en cours change** (modification dans l'application, par SMS, synchro, import) : le **nouveau** médecin est prévenu, avec la fin de son astreinte, et l'**ancien** est prévenu qu'il ne l'est plus. Le message de début n'est alors pas renvoyé en double.
- **Astreinte à venir reprise à un autre** (moins de 7 jours, par exemple samedi midi vous donnez votre dimanche à un collègue) : le **collègue** reçoit « modification du planning : tu es d'astreinte du dim 15/11 a 9h au lun 16/11 a 9h » (et ne reçoit pas le message de début en double), et **vous** recevez « tu n'es plus d'astreinte du dim 15/11 a 9h au lun 16/11 a 9h ». Remplir un planning vide, ré-importer un planning identique ou changer une astreinte lointaine ne prévient personne.
- Textes modifiables : `{nom}` `{debut}` `{fin}` (début d'astreinte), `{nom}` `{heure}` `{fin}` (changement en cours), `{nom}` `{heure}` `{debut}` `{fin}` (reprise ou perte d'une astreinte à venir).
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

## Licence

Ce logiciel est publié sous **licence MIT** (voir le fichier [LICENSE](LICENSE)) — © 2026 Dr Guitton Sébastien - med4tek. Vous pouvez l'utiliser, le copier, le modifier et le redistribuer librement, en conservant la mention de copyright.

**Avertissement : aucune garantie.** Le logiciel est fourni « tel quel ». Ce n'est **pas** un dispositif médical et il n'a pas été validé comme tel. Il compose des codes de renvoi d'appel auprès de l'opérateur, dont le résultat dépend du réseau et de l'opérateur : **testez-le avant tout usage réel** (exercices, vérification du renvoi en place) et gardez une conduite à tenir de secours (appel du 15 en cas de doute).
