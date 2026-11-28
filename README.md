# Universal TV Cast

Appli Android gratuite, sans pub et sans pistage, de Rollando Studio : envoie n'importe quelle vidéo sur la
télé — **DLNA / UPnP** (Philips Titan OS, Samsung, LG, Sony, Panasonic, Hisense…) et **Google Cast** — ou la lit
sur le téléphone quand il n'y a pas de télé allumée.

## Ce que fait l'appli
- **Navigateur intégré** qui repère les vidéos de la page (requêtes réseau, balises `<video>`, listes HLS chargées
  en coulisse, service workers) et les envoie toutes seules au lecteur ; blocage des pubs. Vidéo pas repérée :
  « Chercher encore » ou « Signaler ce site » ; vidéo protégée (DRM) : dit franchement. Bloqueur : domaines d'EasyList
  et de la Liste FR (`tools/adblock.py` → `assets/adblock-hosts.txt.gz`, ~54 000 domaines) et règle « anti pop-under » :
  une fenêtre ou une redirection vers un autre site n'est acceptée que si l'on a touché un lien vers lui (sinon bandeau
  « Fenêtre de pub bloquée · Ouvrir quand même »). Barres d'adresse et d'onglets qui suivent le doigt ensemble.
- **Reprendre** : une carte par série, épisode suivant quand on a fini, relancé sans repasser par le site.
- **Chaînes de télé** : listes M3U / Xtream importées, chaînes gratuites, guide des programmes (« En ce moment »,
  grille par chaîne), zapping, pause du direct. Abonnement Xtream : ses **films et séries** (API `player_api.php`) en
  mur d'affiches, catégories du fournisseur, recherche, saisons et épisodes, « Épisode suivant » (`data/Xtream.kt`,
  `ui/XtreamCatalog.kt`) ; l'appli ne fournit aucun contenu.
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
- **Lecture sur le téléphone** (ExoPlayer) : plein écran, double appui ±10 s, mini-vidéo déplaçable ; en quittant
  l'appli, la vidéo continue en petite fenêtre (image dans l'image, ±10 s et pause).
- **Télécommande** : lecture, ±10 s, barre, volume de la télé (boutons du téléphone), notification.
- **Sous-titres** SRT / VTT / ASS (convertis pour la télé), langue audio, décalage.
- **Widgets** d'écran d'accueil : reprendre + chaînes, reprendre + sites, vidéos en cours, chaînes, reprendre,
  photos et vidéos.
- **Sites proposés selon le pays** (télé publique du pays, puis sites libres visibles partout).
- 23 langues ; partager l'appli, signaler un problème, signature du studio dans les réglages.
- **Pack soutien** (achat unique et volontaire sur Google Play, produit `pack_soutien`) : rien d'utile n'en dépend,
  l'appli reste entière, gratuite et sans pub. Il ouvre la **Personnalisation** : 6 couleurs d'accent, noir intégral
  OLED, écran d'attente de la télé (classique, photo du téléphone, horloge), message perso, écran « vivant » (photos
  qui défilent, heure mise à jour chaque minute), 5 icônes d'appli. Version libre : tout est ouvert ; version de
  débogage : ouvert aussi, pour les essais.

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
| `data/` | Historique et favoris (`Library`), chaînes et listes (`Iptv`, `IptvStore`, `Channels`), guide (`Epg`), économie d'énergie, style (`Look`), pack soutien (`Supporter` ; achat dans `SupportStore`, une version par flavor) |
| `ui/` | Écrans Compose (accueil, lecteur, chaînes, guide, réglages), widgets (`Widgets.kt`), signalement (`Report`) |
| `lan/` | Réseau de la maison : serveurs multimédias (`MediaServers`), dossiers partagés (`Smb`, lus en `content://` par `SmbProvider`), appareils, favoris, vu / commencé (`LanStore`), titres et affiches (`Catalog`) ; écran `ui/NetworkScreen` + mosaïque `ui/LanLibrary` |
| `dlna/`, `net/`, `subs/` | Protocole DLNA, HTTP, recherche de sous-titres |

Les textes des 23 langues viennent de `tools/strings.py` et `tools/i18n/*.json` (`python3 -I tools/strings.py`
régénère les `strings.xml` : ne pas les modifier à la main). `tools/test-tele/` : fausse télé DLNA et site lent
pour les essais.

## Formats pris en charge
- **HLS** : TS ou MP4 par morceaux (CMAF), son à part recollé (TS et MP4), AES-128, plages d'octets, directs,
  coupures (#EXT-X-DISCONTINUITY : pubs insérées) avec horloge recalée ; variante H.264 préférée au HEVC pour la télé.
- **DASH** (.mpd, sans DRM) : SegmentTemplate ($Number$, $Time$, SegmentTimeline), SegmentList, SegmentBase / index
  dans le fichier (sidx, à plusieurs niveaux) ; envoyé à la télé en flux continu MP4, image et son recollés ; directs
  DASH lus sur le téléphone seulement.
- **Fichiers** : MP4, MKV, WebM, MOV, AVI, TS (envoyés tels quels : la télé décide de ce qu'elle lit).
- **Sous-titres** : SRT, WebVTT (aussi découpé en morceaux), ASS/SSA, TTML / EBU-TT-D / IMSC, WebVTT et TTML
  dans du MP4 (DASH) ; intégrés aux MKV / MP4 (choix sur le téléphone, gérés par la télé sinon).
- **Chaînes IPTV** : HTTP(S), UDP / RTP multicast (relayés en HTTP par le téléphone), RTSP / RTMP (téléphone seulement).
- **Impossible** : DRM (Widevine, FairPlay, PlayReady), et sources uniquement HEVC / AV1 / VP9 que la télé ne lit pas
  (pas de réencodage sur le téléphone).

## Limites connues
- Pas de duplication d'écran : l'appli envoie une vidéo, pas l'écran du téléphone.
- Vidéos protégées par DRM (Netflix, Disney+…) et YouTube : impossibles, voulu par ces sites.
- Langue (voix) au choix : HLS, DASH, MKV / WebM, MP4 / MOV, TS ; sur le téléphone, aussi tout autre format lu à
  plusieurs voix. Sur une télé DLNA (qui lit toujours la première voix), le relais masque au passage les autres voix
  dans le fichier (quelques octets, rien de recopié).
- Sous-titres en DLNA : dépend de la télé (beaucoup ignorent les sous-titres externes ; Google Cast les affiche).
- Appareils pris en charge : Google Cast (Chromecast, Google TV, Android TV, télés « Chromecast intégré ») et télés
  DLNA / UPnP (Samsung, LG, Sony, Philips, Panasonic…). Pas les Fire TV d'Amazon (ni Cast ni DLNA d'origine), sauf
  avec une appli de réception DLNA installée dessus. Seuls les appareils allumés sont proposés.

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
