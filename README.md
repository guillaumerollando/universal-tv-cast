# Universal TV Cast

Appli Android gratuite, sans pub et sans pistage, de Rollando Studio : envoie n'importe quelle vidéo sur la
télé — **DLNA / UPnP** (Philips Titan OS, Samsung, LG, Sony, Panasonic, Hisense…) et **Google Cast** — ou la lit
sur le téléphone quand il n'y a pas de télé allumée.

## Ce que fait l'appli
- **Navigateur intégré** qui repère les vidéos de la page (requêtes réseau, balises `<video>`, listes HLS chargées
  en coulisse, service workers) et les envoie toutes seules au lecteur ; blocage des pubs. Vidéo pas repérée :
  « Chercher encore » ou « Signaler ce site » ; vidéo protégée (DRM) : dit franchement.
- **Reprendre** : une carte par série, épisode suivant quand on a fini, relancé sans repasser par le site.
- **Chaînes de télé** : listes M3U / Xtream importées, chaînes gratuites, guide des programmes (« En ce moment »,
  grille par chaîne), zapping, pause du direct.
- **Lecteur externe** : « Ouvrir avec » / « Partager » des autres applis (en-têtes, titre et sous-titres, conventions
  MX Player et VLC) ; un lien de page web s'ouvre dans le navigateur.
- **Album** : photos et vidéos du téléphone ; une vidéo seule se lit comme une vidéo, plusieurs éléments font un diaporama.
- **Réseau** : NAS, box et ordinateurs de la maison. Serveurs multimédias DLNA / UPnP trouvés tout seuls (la télé
  lit leurs vidéos directement chez eux) et dossiers partagés Windows / Mac / NAS en SMB 1 à 3.0.2 (le téléphone
  relaie le fichier, rien n'est copié). Appareils retenus, mot de passe chiffré par le Keystore d'Android ; aide
  « partager un dossier » pour Windows, Mac, Linux et NAS (blocs dépliables). Dossiers et appareils en favoris (★).
- **Vidéothèque du réseau** : dossiers d'abord, puis les films en mosaïque d'affiches ; un dossier d'épisodes devient
  une série (affiche, saisons, épisodes avec image et titre). Informations prises dans cet ordre : balises du fichier
  (titre, année, jaquette intégrée), images posées à côté (`poster.jpg`, `Film.jpg`, conventions Kodi / Jellyfin),
  nom du fichier, puis l'annuaire de Stremio (réglage « Affiches des films », seul le titre nettoyé part).
  Tri (nom, ajout récent, année), « masquer les vus », vu / barre d'avancement. Vidéos du réseau dans « Reprendre »,
  « Épisode suivant » dans le dossier ; appareil éteint : « … ne répond pas ».
- **Lecture sur le téléphone** (ExoPlayer) : plein écran, double appui ±10 s, mini-vidéo déplaçable.
- **Télécommande** : lecture, ±10 s, barre, volume de la télé (boutons du téléphone), notification.
- **Sous-titres** SRT / VTT / ASS (convertis pour la télé), langue audio, décalage.
- **Widgets** d'écran d'accueil : reprendre + chaînes, reprendre + sites, vidéos en cours, chaînes, reprendre,
  photos et vidéos.
- **Sites proposés selon le pays** (télé publique du pays, puis sites libres visibles partout).
- 23 langues ; partager l'appli, signaler un problème, signature du studio dans les réglages.

## Comment ça marche
Le téléphone fait tourner un petit serveur HTTP (`proxy/ProxyServer.kt`) que la télé interroge :
- il relaie la vidéo en ajoutant Referer, cookies et User-Agent exigés par le site ;
- **HLS, mode continu** (par défaut) : assemble les segments en un seul flux MPEG-TS (ou fMP4), en déchiffrant
  l'AES-128 ; c'est ce que presque toutes les télés savent lire. Une avance relance le flux au bon segment ;
- **HLS, mode direct** : réécrit les listes `.m3u8` pour que chaque segment passe par le téléphone ;
- sert les fichiers locaux avec prise en charge des plages (`Range`).

La télé est trouvée par SSDP (et Google Cast, version `play`) puis commandée en SOAP UPnP AVTransport /
RenderingControl (`dlna/Dlna.kt`).

## Où est quoi (`app/src/main/java/com/rollandostudio/universaltvcast/`)
| Dossier | Rôle |
|---|---|
| `cast/` | Le chef d'orchestre `TvCast.kt` (télé ou téléphone, envoi, commandes), lecteur du téléphone (`PhoneEngine`), album et diaporama, vérification de la source (`SourceCheck`), état (`CastState`) |
| `browser/` | Navigateur : logique (`BrowserController`), écran (`BrowserScreen`), repérage des vidéos (`VideoSniffer`), épisodes et titres |
| `proxy/` | Serveur relais pour la télé : HLS, enregistrement du direct, sous-titres, MKV/MP4 |
| `data/` | Historique et favoris (`Library`), chaînes et listes (`Iptv`, `IptvStore`, `Channels`), guide (`Epg`), économie d'énergie |
| `ui/` | Écrans Compose (accueil, lecteur, chaînes, guide, réglages), widgets (`Widgets.kt`), signalement (`Report`) |
| `lan/` | Réseau de la maison : serveurs multimédias (`MediaServers`), dossiers partagés (`Smb`, lus en `content://` par `SmbProvider`), appareils, favoris, vu / commencé (`LanStore`), titres et affiches (`Catalog`) ; écran `ui/NetworkScreen` + mosaïque `ui/LanLibrary` |
| `dlna/`, `net/`, `subs/` | Protocole DLNA, HTTP, recherche de sous-titres |

Les textes des 23 langues viennent de `tools/strings.py` et `tools/i18n/*.json` (`python3 -I tools/strings.py`
régénère les `strings.xml` : ne pas les modifier à la main). `tools/test-tele/` : fausse télé DLNA et site lent
pour les essais.

## Limites connues
- Pas de duplication d'écran : l'appli envoie une vidéo, pas l'écran du téléphone.
- Vidéos protégées par DRM (Netflix, Disney+…) et YouTube : impossibles, voulu par ces sites.
- Sous-titres en DLNA : dépend de la télé (beaucoup ignorent les sous-titres externes ; Google Cast les affiche).

## Construire
Deux versions sortent du même code, choisies au moment de construire :

| Version | Pour | Télés | Bibliothèques |
|---|---|---|---|
| `play` | Google Play | DLNA **et** Google Cast | Google Cast (non libre) |
| `libre` | F-Droid, ou qui veut une appli 100 % libre | DLNA seulement | toutes libres |

```bash
./gradlew testPlayDebugUnitTest assemblePlayRelease     # version Play
./gradlew assembleLibreRelease                          # version libre
```
APK : `app/build/outputs/apk/<version>/release/app-<version>-release.apk` (signées avec la clé de debug pour l'instant).
Le code propre à Google Cast vit dans `app/src/play/` ; `app/src/libre/` le remplace par une version vide.
Textes : `tools/strings.py` génère `res/values*/strings.xml` (ne pas modifier ces fichiers à la main).

## Licence
Universal TV Cast est un logiciel libre, publié sous la licence **GNU GPL version 3** (voir [LICENSE](LICENSE)).
Vous pouvez l'utiliser, l'étudier, le modifier et le redistribuer ; toute version modifiée distribuée doit rester
sous GPL-3.0 et publier son code source. © 2026 Rollando Studio.

**Exception pour Google Cast.** La version `play` utilise la bibliothèque Google Cast (Google Play Services), qui
n'est pas libre. Comme permis par l'article 7 de la GPL, Rollando Studio ajoute l'autorisation suivante :

> *Additional permission under GNU GPL version 3 section 7:* if you modify this Program, or any covered work, by
> linking or combining it with the Google Play Services libraries (or a modified version of those libraries),
> containing parts covered by the terms of their own licence, the licensors of this Program grant you additional
> permission to convey the resulting work.

La version `libre` n'en a pas besoin : elle ne contient aucune bibliothèque non libre.
