# Journal — Castor

## 2026-11-24 — Création
**Fait**
- Appli complète en Kotlin + Jetpack Compose : accueil, navigateur avec détection de vidéos et blocage des pubs,
  écran de lecture (télécommande, sous-titres, mode HLS), choix de la télé, réglages, notification.
- Moteur : découverte SSDP, commandes SOAP, relais HTTP (en-têtes, HLS continu avec AES-128, HLS réécrit,
  fichiers locaux), conversion des sous-titres.
- 7 langues ; vérification de l'affichage en de et nl par captures.
- 9 tests unitaires (HLS, sous-titres, fausse télé SOAP) ; test de bout en bout sur émulateur avec un faux site
  qui exige un Referer et un flux HLS chiffré : le flux servi à la télé est lisible (vérifié par ffprobe), en debug
  et en release.

**Pas fait / à faire**
- Essai réel avec la télé Philips Titan OS du testeur (télé en veille pendant le développement).
- Vérifier si cette télé affiche les sous-titres envoyés en DLNA.
- Clé de signature du studio, fiche Play Store, pages légales (si publication).
