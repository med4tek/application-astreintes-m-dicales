# Astreinte Relais

Application Android personnelle (hors Play Store) qui change toute seule le **renvoi d'appel** du téléphone d'astreinte médicale selon un planning mois par mois.

À l'heure de chaque changement, le téléphone compose le code `**21*NUMÉRO#` (comme le composeur). Pour désactiver, `##21#`.

## Utilisation

1. **Médecins** : ajoutez chaque médecin avec son numéro. Le **Standard 8311** (`0100000000`) existe d'office : vous pouvez changer son numéro (jour férié, etc.).
2. **Planning** : une grille, une ligne par jour, une colonne par créneau de la journée (par défaut **9h, 12h, 14h, 17h, 18h**).
   - **9h et 14h** : standard par défaut, sans rien saisir. Pour un jour particulier (férié…), touchez la case et choisissez autre chose.
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
3. Collez la réponse dans l'app, **Analyser** : un aperçu jour par jour s'affiche. Les noms non reconnus se rattachent à un médecin de votre liste (ou s'ignorent).
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

Le nom du médecin est reconnu sans titre ni accents (« martin » → Dr Martin) ; un nom ambigu est refusé. Si la modification touche l'astreinte en cours, le renvoi est appliqué immédiatement.
**Sécurité** : le PIN voyage en clair par SMS et un numéro d'expéditeur peut être usurpé : choisissez un PIN que vous n'utilisez nulle part ailleurs.
Android 13+ peut bloquer la permission SMS d'une app installée à la main : infos de l'app → ⋮ → « Autoriser les paramètres restreints ».

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
