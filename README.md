# Astreinte Relais

Application Android personnelle (hors Play Store) qui change toute seule le **renvoi d'appel** du téléphone d'astreinte médicale selon un planning mois par mois.

À l'heure de chaque relève, l'app envoie à l'opérateur le code `**21*NUMÉRO#` (sans ouvrir le composeur). Pour désactiver, elle envoie `##21#`.

## Utilisation

1. **Médecins** : ajoutez chaque médecin avec son numéro de portable.
2. **Planning** : touchez un jour, choisissez le médecin et l'heure de relève. « Ajouter un changement » permet plusieurs relèves dans la même journée. « Sélectionner des jours » (ou appui long sur un jour) permet de cocher plusieurs jours, même non consécutifs (raccourcis : Tout le mois, Lun–ven, Week-ends), puis « Assigner… » pose le même médecin à la même heure sur tous. « Remplir une période » fait de même du jour A au jour B.
   Un changement reste valable **jusqu'au suivant** : un jour sans entrée garde le médecin de la veille (affiché en gris, « suite »).
3. **Réglages** : accordez les autorisations, **testez** avec « Vérifier le renvoi actuellement en place » (`*#21#`), puis activez la **Bascule automatique**.

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
