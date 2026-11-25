# Universal TV Cast

Appli Android gratuite, sans pub et sans pistage, de Rollando Studio : envoie n'importe quelle vidéo
sur une télé **DLNA / UPnP** (Philips Titan OS, Samsung, LG, Sony, Panasonic, Hisense…), y compris celles
qui n'ont ni Chromecast ni Miracast.

## Ce que fait l'appli
- **Lecteur externe** : apparaît dans « Ouvrir avec » / « Lecteur externe » / « Partager » des autres applis.
  Reprend les en-têtes (`headers`), le titre et les sous-titres (`subs`, `subs.name`, `subs.enable`, convention
  MX Player ; `subtitles_location`, convention VLC).
- **Navigateur intégré** qui repère les vidéos de la page (requêtes réseau + balises `<video>`), avec blocage
  des régies pub et des fenêtres surgissantes.
- **Vidéos du téléphone** (sélecteur de fichiers) et **lien collé**.
- **Télécommande** : lecture/pause, ±10 s, barre de progression, arrêt, volume de la télé (boutons de volume du
  téléphone aussi), notification avec commandes.
- **Sous-titres** SRT / VTT / ASS convertis en SRT UTF-8 et envoyés à la télé (`CaptionInfo.sec` + DIDL).
- **Favoris** et **historique** gardés sur le téléphone. 7 langues (fr, en, es, de, it, nl, pt-BR).

## Comment ça marche
Le téléphone fait tourner un petit serveur HTTP (`proxy/ProxyServer.kt`) que la télé interroge :
- il relaie la vidéo en ajoutant Referer, cookies et User-Agent exigés par le site ;
- **HLS, mode continu** (par défaut) : assemble les segments en un seul flux MPEG-TS (ou fMP4), en déchiffrant
  l'AES-128 ; c'est ce que presque toutes les télés savent lire. Une avance relance le flux au bon segment ;
- **HLS, mode direct** : réécrit les listes `.m3u8` pour que chaque segment passe par le téléphone ;
- sert les fichiers locaux avec prise en charge des plages (`Range`).

La télé est trouvée par SSDP puis commandée en SOAP UPnP AVTransport / RenderingControl (`dlna/Dlna.kt`).

## Limites connues
- Pas de duplication d'écran (la télé Titan OS ne reçoit ni Miracast ni Google Cast).
- Vidéos protégées par DRM (Netflix, etc.) impossibles par nature.
- Sous-titres : dépend de la télé (beaucoup ignorent les sous-titres externes en DLNA).

## Construire
```bash
./gradlew testDebugUnitTest assembleRelease
```
APK : `app/build/outputs/apk/release/app-release.apk` (signée avec la clé de debug pour l'instant).
Textes : `tools/strings.py` génère `res/values*/strings.xml` (ne pas modifier ces fichiers à la main).

## Licence
Universal TV Cast est un logiciel libre, publié sous la licence **GNU GPL version 3** (voir [LICENSE](LICENSE)).
Vous pouvez l'utiliser, l'étudier, le modifier et le redistribuer ; toute version modifiée distribuée doit rester
sous GPL-3.0 et publier son code source. © 2026 Rollando Studio.
