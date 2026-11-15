# Minuteur d'applis

Appli Android qui impose un temps d'attente avant d'ouvrir les applis de ton choix
(Instagram, TikTok…), sans les bloquer.

## Fonctionnement

1. Tu ouvres une appli surveillée : un écran d'attente s'affiche avec un compte à rebours
   (5 min par défaut, réglable de 10 s à 60 min).
2. Le minuteur continue même si tu retournes à l'accueil.
3. Une fois terminé, l'appli est en accès libre pendant la « durée d'accès »
   (15 min par défaut, réglable de 1 à 60 min).
4. Ensuite, le cycle recommence.

L'appli n'a pas la permission Internet : rien ne sort du téléphone.

## Obtenir l'APK sans ordinateur

1. Mets ce projet dans un dépôt GitHub (branche `main`).
2. L'onglet **Actions** compile l'APK automatiquement (2 à 4 minutes).
3. Télécharge `minuteur-applis.apk` depuis l'onglet **Releases** du dépôt, sur ton téléphone.
4. Ouvre le fichier et autorise l'installation depuis ton navigateur.

Avec Android Studio : ouvre le dossier, puis *Build > Build APK(s)*.

## Première configuration

1. Ouvre l'appli, puis « Ouvrir Accessibilité » et active le service « Minuteur d'applis ».
2. Si Android affiche « Paramètre restreint » (Android 13 et plus, applis installées hors Play Store) :
   « Infos de l'appli » > menu ⋮ > « Autoriser les paramètres restreints », puis recommence l'étape 1.
3. Règle les durées et coche les applis concernées.

Sur certains téléphones (Xiaomi, Samsung, Huawei…), exclus l'appli de l'optimisation de batterie
pour éviter que le service soit coupé.

## Technique

- Java, sans dépendance. Android 8.0 minimum (API 26), cible API 34.
- `GuardService` : service d'accessibilité qui détecte l'appli au premier plan et affiche l'écran d'attente.
- `MainActivity` : écran de configuration.
- `Prefs` : réglages.
