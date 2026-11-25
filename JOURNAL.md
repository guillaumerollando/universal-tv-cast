# Journal — Universal TV Cast (anciennement « Universal TV Cast »)

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

## 2026-11-24 — La télé n'était pas trouvée
**Constat** (télé Philips 55OLED760/12, Titan OS, en Ethernet) : son récepteur DLNA
(`:49152/description.xml`, AVTransport + RenderingControl + ConnectionManager) **ne répond à aucune recherche SSDP**,
ni multicast ni unicast ; la télé n'annonce qu'un service maison `urn:schemas-tpv-org:service:PhotoFrame:1`.
Elle n'accepte pas `video/mp2t` (mais `video/mpeg`) ni le HLS. Elle a aussi Google Cast (8008/8009) et AirPlay (7000).

**Fait**
- Recherche en trois volets : SSDP, écoute des annonces NOTIFY, sondage direct du /24 (ports 49152-49155, 9197, 2869,
  8080 et chemins de description habituels). Les télés trouvées sont mémorisées et revérifiées en premier.
- Ajout manuel d'une télé par adresse IP.
- Le format envoyé s'adapte à la liste de la télé (GetProtocolInfo) : `video/mp2t` → `video/mpeg`, mkv → `video/x-mkv`…
  Une télé qui ne lit pas le HLS reçoit toujours le flux continu.
- Vérifié depuis le PC avec le vrai code contre la télé : trouvée en 47 ms, 23 formats lus, `video/mpeg` choisi.

**Pas fait** : lecture réelle sur la télé depuis le téléphone (à faire par le testeur).

## 2026-11-24 — « Fichier non pris en charge » sur la télé
**Constat** (essais depuis le PC vers la télé, lecteur GStreamer de Titan OS) : la télé lit MP4 et MPEG-TS
(`video/mpeg`) s'ils ont une taille annoncée, ou s'ils sont envoyés **par blocs (chunked)**. Un flux sans taille
ni blocs (fermeture de connexion), ce qu'envoyait le flux continu de Universal TV Cast, la bloque. Elle sonde aussi la fin
du fichier (`Range: bytes=<fin>-`) avant de lire.

**Fait**
- Flux continu HLS envoyé en `Transfer-Encoding: chunked` ; réponse immédiate aux requêtes HEAD.
- Relais direct envoyé par blocs quand le site n'annonce pas de taille.
- Vérifié de bout en bout : téléphone → Universal TV Cast → télé, flux HLS chiffré protégé par Referer : lu jusqu'au bout.

**Pas fait** : essai par le testeur sur ses vraies sources ; sous-titres sur cette télé.

## 2026-11-24 — Avance qui échoue, télé qui part sur HDMI 1, télé qui plante
**Constat** (journaux du téléphone, source Stremio) : les commandes « changer de vidéo » restaient sans réponse
(la télé interroge la vidéo avant de répondre, et le relais réinterrogeait Stremio à chaque fois) ; Universal TV Cast
continuait à questionner la télé deux fois par seconde et laissait relancer l'envoi : la télé a fini par planter.
Avancer loin dans un torrent : le morceau met plus de 10 s à venir, le lecteur de la télé abandonne et la télé
repart sur la dernière source (HDMI 1). La télé refuse aussi un déplacement pendant le précédent (710) et un
changement de vidéo pendant la lecture (« Transport locked ») mais l'accepte en pause.

**Fait**
- Relais : taille et type de la vidéo mémorisés, réponses immédiates aux questions de la télé ; attente de la
  source jusqu'à 2 min ; journal `CastorProxy` de tout ce que demande la télé.
- Une seule commande à la fois vers la télé ; interrogation toutes les 2 s, en pause pendant une commande et
  ralentie si la télé ne répond plus ; message « la télé ne répond pas ».
- Appuis ±10 s regroupés ; attente que la télé soit prête avant de la déplacer.
- **Préchargement** : avant de déplacer la télé, Universal TV Cast fait venir le morceau de la source et attend qu'il soit là.
- Changement de vidéo par pause au lieu de Stop.
- **Reprise automatique** (2 fois max.) si la télé quitte la lecture en plein milieu.
- Fausse télé et faux site lent (`tools/test-tele/`) : tout vérifié sur émulateur sans toucher la vraie télé.

**Pas fait** : essai par le testeur sur la vraie télé avec Stremio. Le bouton Arrêter et la fin de vidéo
renvoient toujours sur la dernière source (choix de la télé).

## 2026-11-24 — Retour arrière : c'était pire
**Retour du testeur** : avance qui n'avance plus, appli qui mouline, télé relancée de force 3 fois quand il quittait
le lecteur (impression de piratage). La reprise automatique ne distinguait pas une panne d'un départ volontaire,
et le préchargement bloquait toutes les commandes jusqu'à 60 s.

**Vraie cause** (journaux du téléphone) : MIUI met **Stremio** en veille (`SmartPower … background→idle`) 3 s après
qu'on le quitte, et le tue parfois. Son serveur de torrent (127.0.0.1:11470) ne répond alors plus aux demandes
de la télé pour un nouveau morceau : la télé attend, continue l'ancien tampon, puis abandonne → HDMI 1.
Même cause pour l'erreur vue dans Web Video Cast.

**Fait**
- Reprise automatique et préchargement **retirés**.
- Universal TV Cast retient l'appli qui envoie la vidéo ; si la télé attend plus de 15 s une vidéo servie par le téléphone,
  message « … Android l'a sans doute mis en veille » + bouton vers les réglages de l'appli source.
- Vérifié sur émulateur + fausse télé : message affiché ; quand la télé abandonne, Universal TV Cast ne relance rien.

**Pas fait** : vérifier avec Stremio réglé sur « Aucune restriction » (batterie) chez le testeur.

## 2026-11-25 — « Ça fonctionne du feu de Dieu » ; sous-titres et écran de la télé
**Retour du testeur** : avance instantanée (saut à 1h05 direct), mieux que Web Video Cast sur sa Philips.
Restent : pas de sous-titres depuis Stremio ; la télé affiche des infos techniques peu jolies (AAC, 24 Hz, BT 709…).

**Fait**
- Journal `CastorIntake` : tout ce qu'une appli transmet à Universal TV Cast (pour savoir si et comment Stremio passe ses
  sous-titres).
- Sous-titres repérés dans tout extra dont le nom évoque des sous-titres (en plus des conventions MX Player/VLC).
- Affiche 640×360 (logo + titre) envoyée à la télé (`upnp:albumArtURI`), durée annoncée quand elle est connue,
  `dc:creator` Universal TV Cast. Vérifié sur émulateur + fausse télé.

**À vérifier chez le testeur** : ce que Stremio transmet (lire `CastorIntake` après un envoi) ; si la Philips
affiche l'affiche/le titre à la place des infos techniques ; si elle affiche des sous-titres envoyés en DLNA.

## 2026-11-25 — Conseils préventifs « mise en veille »
**Fait**
- `data/Background.kt` : détecte si Android peut endormir une appli (liste blanche batterie) et les marques
  connues pour être agressives (Xiaomi, Samsung, Huawei, Oppo…) ; mots exacts du réglage selon la marque.
- Carte dans l'onglet Lecture, avant que le problème n'arrive : d'abord pour l'appli qui envoie la vidéo
  (Stremio…), sinon pour Universal TV Cast pendant une diffusion ; bouton vers ses réglages et « Ne plus afficher ».
- Rubrique « Dépannage » dans les réglages : télé introuvable, vidéo figée en avançant, format non pris en charge,
  sous-titres, changement de source à la fin, arrêt quand l'écran s'éteint. 7 langues. Vu sur émulateur.

## 2026-11-25 — Aide à la place du dépannage dans les réglages
**Fait** : bouton « ? » en haut, panneau Aide (problèmes courants + « Signaler un problème » qui ouvre l'appli
e-mail vers rollandostudio@gmail.com avec modèle du téléphone et de la télé, version, dernier message), lien
« Aide » dans les messages d'erreur, secouer le téléphone ouvre l'aide (réglage pour le couper). Vérifié sur
émulateur (secousse simulée).

## 2026-11-25 — Design avec un peu de personnalité, carte orange moins insistante
**Retours** : carte orange affichée alors que tout était bien réglé ; « Universal TV Cast » coupé sur deux lignes ; design
trop neutre ; avance parfois un peu « hésitante » mais qui se corrige ; toujours pas de sous-titres depuis Stremio.

**Fait**
- Carte de conseil seulement si Android peut vraiment endormir l'appli (vérifié : Stremio et Universal TV Cast sont sur la
  liste blanche du téléphone du testeur, la carte ne s'y affichera plus).
- Barre du haut maison : « Universal TV Cast » toujours sur une ligne, le nom de la télé se raccourcit.
- Police Fredoka (celle des jeux du studio) pour titres et boutons ; ondes animées autour de la télé pendant la
  lecture (immobiles si les animations sont coupées) ; arcs de diffusion dans la carte d'accueil.

**En cours** : capture du journal pendant un envoi depuis Stremio pour voir comment il transmet les sous-titres.

## 2026-11-25 — Police marquante et barre d'onglets à nous
**Fait** : Lilita One (police de Jimmy's Blackout) pour le nom Universal TV Cast et les grands titres ; barre d'onglets en
pilule flottante (onglet actif en capsule ambrée avec son nom, les autres en icône, nom lu par les lecteurs
d'écran, point ambré sur Lecture pendant une diffusion). Vérifié en thème sombre et clair sur émulateur.

## 2026-11-25 — Police : Inter Display / Inter
Lilita One jugée « comics », pas pro. Planche de comparaison (Inter Display, Figtree, Fredoka) : choix retenu = **Inter Display** (titres extra-gras, lettres resserrées) + **Inter** (reste). Lilita et Fredoka
retirées ; 3 graisses seulement (appli à 2,1 Mo). Pilule, ondes et arcs conservés. Vérifié sur émulateur.

## 2026-11-25 — Flashs en avançant, sous-titres Stremio, reprise à la bonne position
**Constats** (capture du journal pendant un essai du testeur) :
- Stremio transmet seulement l'URL (avec le nom du fichier), `position`/`startfrom` (ms) et `return_result` :
  **aucun sous-titre**. Universal TV Cast ignorait la position : il démarrait au début.
- Flashs en avançant depuis 2 versions : la seule nouveauté côté télé était l'affiche (`albumArtURI`) et la durée
  dans le DIDL ; la Philips affiche très probablement l'affiche pendant chaque chargement.

**Fait**
- DIDL revenu à la version qui marchait (sans affiche ni durée).
- Démarrage à la position envoyée par l'appli source.
- Sous-titres automatiques (`subs/SubtitleFinder.kt`) : nom du fichier → titre/année/épisode → Cinemeta (identifiant
  IMDb) → OpenSubtitles v3 de Stremio (sans compte). Langue du téléphone puis anglais choisies d'office ; toutes les
  langues au choix. Recherche avant le premier envoi (5 s max). Vérifié : Spider-Man (81), Breaking Bad S03E07 (64).
- Le relais sert aussi `video.srt` (et .ass, .smi…) à côté de la vidéo, pour les télés qui cherchent un fichier de
  sous-titres du même nom ; journalisé.
- Titre propre (nom Cinemeta) au lieu du nom de fichier. Ondes depuis le bord de la télé. Carte du volume retirée.

**À vérifier sur la vraie télé** : plus de flashs ; la Philips affiche-t-elle les sous-titres (CaptionInfo.sec,
DIDL, ou fichier .srt voisin) ?

## 2026-11-25 — Barre d'onglets : taille fixe, indicateur qui glisse
La pilule qui changeait de largeur faisait un effet « gluant ». Remplacée par une barre flottante de largeur fixe,
trois emplacements égaux (icône + nom toujours visibles) et un indicateur ambré qui glisse en 0,22 s sans rebond.

## 2026-11-25 — Images du film
**Fait** : l'identification du film (Cinemeta, déjà faite pour les sous-titres) fournit aussi affiche, image de fond
et logo du titre (metahub ; image de l'épisode pour une série). Onglet Lecture : grande image du film avec logo
(ou titre) et état de la lecture en surimpression, ambiance floutée en fond (Android 12+) ; petites affiches dans
« Lu récemment » ; affiche dans la notification. Chargeur d'images maison (cache 24 Mo, réduction), aucune
bibliothèque. Rien n'est envoyé à la télé. Vérifié sur émulateur (Spider-Man, Breaking Bad S03E07).

## 2026-11-25 — Animation « en cours de lecture » sur l'en-tête du film
Badge télé avec ondes (en haut à droite) et barres d'égaliseur à côté de l'état ; immobiles en pause ou sans animations.

## 2026-11-25 — Google Cast (pour les sous-titres et toutes les télés Cast)
**Constat** : la Philips ne demande jamais les sous-titres en DLNA (ni CaptionInfo.sec, ni fichier .srt voisin) ;
son lecteur DLNA ne lit que les pistes intégrées au fichier. Web Video Cast ouvrait un autre lecteur sur cette télé :
très probablement Google Cast (la télé l'intègre, ports 8008/8009).

**Fait**
- SDK Google Cast officiel (`play-services-cast-framework`) + MediaRouter ; lecteur standard de Google.
- Les appareils Cast apparaissent dans « Choisir une télé » à côté du DLNA (« Google Cast · sous-titres » / « DLNA »).
- Lecture Cast : vidéo par le relais (MP4/fichiers directs, HLS réécrit), titre et affiche, **sous-titres en piste
  WebVTT** ; commandes pause, avance, arrêt, volume ; reprise à la position de Stremio ; erreur de format signalée.
- Relais : CORS complet + réponse aux requêtes OPTIONS ; sous-titres servis en WebVTT (`subtitles.vtt`).
- DLNA inchangé. Vérifié sur émulateur (démarrage, DLNA, WebVTT, CORS) ; l'émulateur ne voit pas les appareils Cast.

**À vérifier sur la vraie télé** : l'entrée Cast de la Philips apparaît ; la vidéo Stremio et ses sous-titres
s'affichent dans le lecteur Cast ; avance ; arrêt.

## 2026-11-25 (nuit) — Cast automatique, album photo, accueil, reprise après sous-titres
**Constats** : l'essai Cast n'a rien changé car Universal TV Cast a réutilisé l'entrée DLNA mémorisée (aucune connexion Cast
dans le journal). Changer de sous-titres repartait à 0 (déplacement demandé trop tôt, refusé en silence). Vidéos
du téléphone : fonctionnent sur émulateur ; sur Xiaomi, sélecteur MIUI ou vidéos HEVC probablement en cause.

**Fait**
- Une seule entrée par télé : Cast + DLNA d'une même adresse fusionnés, **Cast choisi d'office**, DLNA en secours
  automatique (échec de connexion ou format refusé, au même endroit). Journal `CastorCast`.
- Reprise fiable après changement de sous-titres (attendre la lecture, puis se placer, 3 essais). Vérifié : 1:55.
- Fichiers du téléphone : sélecteur photos/vidéos d'Android ; ouverture en 3 méthodes de secours.
- **Album photo** : sélection multiple, photos préparées (1920 px, orientation, HEIC→JPEG), envoi Cast/DLNA,
  précédente/suivante, diaporama 6 s, vignettes. Vérifié sur émulateur + fausse télé.
- Accueil : grille de 4 tuiles (Sites web, Vidéos, Photos, Lien), carrousel « Reprendre » avec affiches,
  favoris toujours visibles (explication si vide), lien « Parcourir les fichiers ».

## 2026-11-25 (nuit) — Sous-titres intégrés au MP4 pour les télés DLNA
**Fait** : `proxy/Mp4Subtitler.kt` ajoute une piste de texte tx3g (mov_text) à un MP4 **sans le recopier ni le
réencoder** : l'index moov est lu une fois, réécrit (piste en plus, stco→co64 décalés), et la télé reçoit un fichier
virtuel (morceaux de l'original + index et sous-titres en mémoire), plages d'octets comprises. MP4 classiques
(moov au début ou à la fin) ; fragmentés refusés → envoi habituel. Utilisé pour les télés DLNA quand des sous-titres
sont choisis et que la vidéo est un MP4 (réglage « Sous-titres intégrés », activé par défaut).
**Vérifié** : test unitaire avec ffmpeg (moov début et fin : 3 pistes, sous-titres relus à l'identique, décodage sans
erreur) ; émulateur + fausse télé avec Breaking Bad et sous-titres français réels : fichier servi valide, lecture par
plages identique au fichier complet ; **GStreamer (le moteur de la Philips) voit la piste « subtitles : Timed Text, fr »**.
**À vérifier** : affichage sur la vraie Philips.

## 2026-11-25 (nuit) — Navigateur
**Fait**
- Bloqueur renforcé : régies de pub vidéo (IMA SDK de Google, FreeWheel, SpotX, Teads…) et motifs d'adresses
  (/pagead/, /pubads, /vast…). Les pubs ne sont plus proposées comme « vidéos de la page ».
- HLS : une liste principale masque ses variantes ; affichage « Auto · 5 qualités · 1080p ».
- Barre d'adresse : la toucher sélectionne l'adresse, taper la remplace (avant, le texte s'ajoutait à l'ancienne
  adresse — défaut révélé par les tests).
- Testé : Internet Archive (MP4 repéré et lu par la fausse télé via le relais), démo hls.js (HLS repéré et
  regroupé). YouTube : pubs servies par ses propres serveurs, non filtrables sans casser la vidéo (et ses
  vidéos ne sont pas « castables » depuis une page web : flux protégés).
- Aide mise à jour (sous-titres automatiques / Cast / intégrés ; vidéos HEVC du téléphone).

**À tester par le testeur (télé allumée)**
1. Universal TV Cast choisit Cast d'office pour la Philips : le lecteur de la télé doit changer ; sous-titres affichés ?
2. Si Cast refuse la vidéo, bascule automatique en DLNA, avec **sous-titres intégrés** au MP4 : affichés ?
3. Changer de sous-titres en cours de lecture : reprise au même endroit.
4. Album photo et diaporama.
5. Vidéos du téléphone (nouveau sélecteur) ; si échec, regarder `CastorProxy` (méthode d'ouverture, format).
6. Navigateur : sélection de l'adresse au toucher, regroupement des qualités, moins de pubs.

**Idée en attente** : parcourir les serveurs du réseau (NAS, Plex, Jellyfin en DLNA) — fonction de LocalCast et
BubbleUPnP, demandée par une minorité ; faisable car Universal TV Cast parle déjà UPnP.

## 2026-11-25 — Nouveau nom « Universal TV Cast », licence GPL-3.0
**Fait**
- Nom public **Universal TV Cast** (référencement) : nom de l'appli, tous les textes des 7 langues, aide,
  notification, e-mail de signalement, `dc:creator` et en-têtes réseau, README, docs, journal. Inchangés car
  invisibles : `applicationId` `studio.rollando.castor`, packages Kotlin, dossier, étiquettes du journal
  (`CastorProxy`…).
- En-tête : nom en deux étages (« UNIVERSAL » en petites capitales ambrées au-dessus de « TV Cast »), lu
  « Universal TV Cast » par TalkBack ; le bouton « Choisir une télé » garde sa place. Vérifié sur émulateur.
- Dépôt `guillaumerollando/universal-tv-cast`. Licence **GPL-3.0** : fichier `LICENSE` (texte officiel) et
  section Licence du README.
- Données personnelles retirées des fichiers suivis : IP du réseau local, chemins personnels (fausse télé :
  `ADB` lu dans l'environnement ; test MP4 : dossier `EMB_DIR`), nom de la pièce, prénom du testeur.
  **Attention** : l'historique git contient encore ces éléments dans les anciens commits (IP locale de la télé
  et du téléphone, chemin `/home/…`). Rien de secret, mais à savoir avant de rendre le dépôt public.
- Lint : 16 erreurs corrigées (vérification d'API pour les photos, type MIME en minuscules pour les listes
  HLS, BOM écrits en `﻿`, `app_name` non traduisible, faux positif `dispatchKeyEvent`). Lint passe.
- Tests : 15/15. APK release 3,1 Mo.

**Reste à tester sur la télé** : Cast choisi d'office avec sous-titres, sous-titres intégrés en DLNA, reprise
après changement de sous-titres, photos, vidéos du téléphone.

## 2026-11-25 (après-midi) — Retours du premier vrai essai : finitions
**Retour du testeur** : « bluffant », marche du premier coup avec Stremio, sous-titres FR/EN, reprise à la bonne
minute. À reprendre : sous-titres décalés, photos lentes (1-2 s), retour sur HDMI 1 à la fin d'une vidéo ou du
diaporama, aide trop « texte ».

**Fait**
- **Décalage des sous-titres** : − / + par pas de 0,5 s (± 60 s), remise à 0. Les appuis rapprochés sont
  regroupés (1,3 s) : la télé ne recharge qu'une fois, à la seconde près (position exacte lue sur le lecteur Cast).
  Marche en Cast (WebVTT) et en DLNA (externe ou intégré au MP4). Test unitaire du décalage.
- **Écran d'attente sur la télé** (image 1920 × 1080 aux couleurs de l'appli, dessinée sur le téléphone, texte
  traduit) au lieu de laisser la télé repartir sur HDMI : à la fin d'une vidéo (Cast : fin signalée tout de suite
  par le lecteur ; DLNA : arrêt près de la fin), et sur le bouton Arrêter (vidéo ou album). « Se déconnecter »
  reste le moyen de libérer la télé. Vérifié sur la fausse télé : fin → écran d'attente, Arrêter → écran d'attente,
  plus aucun « Stop ».
- **Photos plus rapides** : les photos voisines (suivante, précédente, celle d'après) sont préparées pendant
  l'affichage ; le délai restant est celui de la télé. (Idée d'un flux vidéo continu écartée : une télé met 2 à 5 s
  à démarrer un flux en direct, plus lent qu'une photo, et ça coûte en batterie.)
- **Album photos + vidéos** : la tuile « Album » prend photos et vidéos ; une vidéo est lue jusqu'au bout puis
  l'album passe à la suite (diaporama ou non), barre d'avancement et lecture/pause, pastille ▶ sur les vignettes,
  état du diaporama écrit sous les boutons. Vérifié : photo → photo (6 s) → vidéo 25 s → photo → boucle.
- **Sous-titres repliés** : une ligne « Sous-titres · Français » qui se déplie au toucher.
- **Aide** : plus aucun nom d'appli tierce. Le panneau s'ouvre en grand (déplier une réponse ne le fait plus
  redescendre). Chaque problème montre l'état réel et les bons boutons : Wi-Fi et adresse, télés trouvées,
  relancer la recherche, ajouter par IP ; mise en veille de notre appli et de l'appli vidéo avec bouton vers leurs
  réglages ; vidéo en cours (format, Cast ou DLNA), renvoyer, essayer en DLNA, flux continu ; type de télé et
  sous-titres disponibles, réglage « sous-titres intégrés » ; se déconnecter.
- Fausse télé : simule maintenant la fin d'une vidéo.
- Tests : 16/16, lint propre.

**À vérifier sur la vraie télé (Cast)** : écran d'attente à la fin d'une vidéo et sur Arrêter (le lecteur Cast de
Titan OS reste-t-il ouvert ?) ; vitesse des photos ; décalage des sous-titres.
**Plus tard** : listes IPTV (M3U) — un lien de chaîne passe déjà par « Coller un lien ».

## 2026-11-25 (soir) — Diaporama en flux vidéo, pause pendant le réglage des sous-titres
**Retour du testeur** : à chaque photo, la télé met environ 2 s d'écran noir, comme si elle changeait de fichier.
Il propose d'envoyer le diaporama comme un flux vidéo continu. Le sens du décalage des sous-titres n'est pas
clair (il s'est trompé). Il veut aussi que la vidéo se mette en pause pendant le réglage des sous-titres, car
régler pendant la lecture puis mettre pause fait beaucoup d'actions d'un coup.

**Fait**
- **Diaporama fluide** (réglage, activé par défaut) : le téléphone dessine la photo 25 fois par seconde dans
  l'encodeur H.264 matériel (1920 × 1080, 6 Mb/s, une image clé par seconde). Le passage d'une photo à l'autre se
  fait par un fondu de 0,45 s, puis le flux est mis en MPEG-TS par un multiplexeur maison (`TsMuxer`, PAT/PMT,
  PTS/PCR). La télé ouvre son lecteur une seule fois par album :
  - Cast : HLS en direct, segments d'une seconde, 4 en ligne ;
  - DLNA : MPEG-TS continu envoyé par blocs (`video/mpeg`).
  Changer de photo ne demande plus rien à la télé. Si la télé refuse le flux (erreur au chargement, ou erreur
  Cast dans les 7 s), l'appli revient toute seule aux photos une par une. Les vidéos de l'album se lisent
  toujours en direct ; le flux reprend sur la photo suivante. L'écran d'attente et l'arrêt coupent le flux.
  Les photos sont gardées en 16 bits pour ménager la mémoire.
  Vérifié :
  - fausse télé DLNA : le flux est lu ;
  - ffprobe : H.264 1920 × 1080 ;
  - ffmpeg : flux continu et HLS décodés, segments de 25 images sans erreur, bande d'images photo 1 → photo 2 ;
  - test unitaire : aller-retour du multiplexeur pour des tailles d'image de 1 à 70 000 octets.
  Une erreur de décodage isolée est apparue au moment d'un fondu, avec l'encodeur logiciel de l'émulateur ;
  à surveiller avec l'encodeur matériel du téléphone.
- **Sous-titres** : phrase claire (« Les sous-titres arrivent avant la voix ? Appuie sur +. Après la voix ?
  Appuie sur −. ») sur toute la largeur.
- **Pause pendant le réglage** : déplier les sous-titres met d'abord la vidéo en pause. Les changements (piste,
  décalage) rechargent au même endroit en restant en pause (Cast : chargement sans lecture automatique ;
  DLNA : pause après la reprise). Replier relance la lecture, si c'est l'appli qui l'avait mise en pause.
  Vérifié sur la fausse télé : Pause → (+0,5 s) rechargement, déplacement à 7 s, Pause → repli → Play.
- Tests : 17/17, lint propre.

**À vérifier sur la vraie télé** :
- Cast : le lecteur accepte-t-il le HLS en direct sans piste son ?
- Délai entre l'appui et la photo affichée (le flux en direct a un peu de retard, mais plus d'écran noir).
- Batterie pendant un long diaporama.

## 2026-11-25 (soir, 2) — Diaporama en flux : moins bien sur la Philips, retour au mode photo par photo
**Retour du testeur** : avec le flux, l'album est plus lent qu'avant et pas fluide.
**Constats** (journal du téléphone et réseau, en lecture seule) :
- Le flux est passé par le **DLNA**. Le téléphone ne trouve **aucun appareil Google Cast**, alors que la télé
  s'annonce bien en Cast sur le réseau (réponse mDNS `_googlecast._tcp`, ports 8008/8009, `eureka_info` lisible).
  Les services Google publient une route, mais pas pour le lecteur standard de Google (`CC1AD845`) que
  l'appli demande. Le Cast de cette Philips ne semble donc pas proposer ce lecteur.
  Conséquence : chez le testeur, tout passe en DLNA, y compris les sous-titres (intégrés au MP4).
- Le lecteur DLNA de la Philips met beaucoup de vidéo en réserve avant d'afficher un flux en direct, d'où le
  retard. Sa liste de commandes (`avt.xml`) n'a pas `SetNextAVTransportURI` : impossible de lui faire préparer
  la photo suivante à l'avance.

**Fait**
- « Diaporama fluide » est désactivé par défaut et marqué expérimental. On revient au mode photo par photo, et
  on change de photo par une seule commande quand la télé l'accepte (avant : pause puis attente).
- Tests 17/17, lint propre.
**Piste** : le noir entre deux photos vient de la télé en DLNA. On ne pourra l'enlever qu'avec un lecteur à
nous sur la télé : un récepteur Cast personnalisé (si le Cast de la Philips en accepte un) ou une appli Titan OS.
**Décision** (testeur, 25/11) : pas de partie côté télé. Pas d'accès aux stores Philips, et ce serait hors du
principe « Universal » (faire au mieux avec ce que chaque télé offre déjà). Le mode photo par photo suffit.

## 2026-11-25 (soir, 3) — Se déconnecter libère vraiment la télé
**Retour du testeur** : après « Se déconnecter », la télé garde l'écran d'attente. Il veut qu'elle revienne comme
avant. Il trouve le mode photo par photo d'avant satisfaisant (gardé par défaut).
**Fait** : « Se déconnecter » envoie maintenant « Stop » à la télé en DLNA (en Cast, la session est fermée) : la
télé quitte notre écran et revient à sa dernière source. L'écran d'attente affiche « by Rollando Studio ».
Tests 17/17, lint propre.
