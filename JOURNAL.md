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

## 2026-11-25 (soir, 4) — Album : écran noir après une vidéo, appuis qui s'empilent
**Retour du testeur** : deux vidéos se sont bien enchaînées, puis écran noir à la place de la photo suivante ;
en changeant de photo, « tout a planté ».
**Journal du téléphone** :
- 14:10:12, photo après la vidéo : la télé a fait `HEAD` sans jamais `GET`. Elle n'a pas lu la photo : son lecteur
  se rouvrait (fin de vidéo) et la commande Play s'est perdue.
- 14:10:28, photo suivante : la télé n'a pas répondu à `SetAVTransportURI`. L'appli a attendu 25 s, délai prévu
  pour les vidéos, et les appuis suivants se sont empilés derrière.
**Fait**
- Photo en DLNA : commandes à délai court (7 s), et vérification que la télé télécharge vraiment la photo
  (`GET` reçu par le relais). Sinon, nouvel envoi au bout de 4 s, une fois.
- Appuis rapprochés (suivante, précédente, vignettes) regroupés : le téléphone affiche tout de suite, la télé ne
  reçoit que le dernier choix.
- Fausse télé : `FAKE_REOPEN=1` perd la première commande Play après une fin de vidéo, comme la Philips.
  Vérifié : photo renvoyée et affichée ; 5 appuis rapides → 2 commandes au lieu de 5.
- Tests 17/17, lint propre.

## 2026-11-25 (soir, 5) — Pourquoi Twitch caste sur la Philips et pas nous
Essai demandé par le testeur, sur son téléphone : un live Twitch casté vers la télé.
- Twitch lance **son propre lecteur Cast**, `B3DCF968` « Twitch Chromecast Player » (journal des services
  Google : `launchApplicationWithOptions: B3DCF968`).
- Question posée à la télé en Cast v2, en lecture seule (`GET_APP_AVAILABILITY` sur le port 8009, rien n'est
  lancé) :
  - `B3DCF968` (Twitch) : `APP_AVAILABLE` ;
  - `CC1AD845` (lecteur standard de Google, celui de l'appli) : `APP_UNAVAILABLE` ;
  - deux autres identifiants testés : `APP_UNAVAILABLE`.
**Conclusion** : le Cast de Titan OS n'accepte qu'une liste d'applis partenaires (YouTube, Twitch…). Ni le
lecteur standard de Google ni un lecteur personnalisé ne seraient acceptés : sur cette télé, le DLNA est la
bonne voie, et l'appli la prend déjà d'office (aucune route Cast proposée). Il ne manque rien côté appli. Sur
les autres télés Cast (Chromecast, Google TV, Sony, TCL…), le lecteur standard est disponible et l'appli
l'utilise.

## 2026-11-25 (soir, 6) — Album : la diffusion s'arrêtait à la fin d'une vidéo
**Retour du testeur** : diaporama lancé sur une vidéo ; à la fin de la vidéo, la diffusion s'est arrêtée au lieu
de passer à la suite (l'appli, elle, est restée ouverte).
**Journal** : vidéo lue à 14:22:01, aucune « fin de lecture » ensuite. La fin n'était reconnue que si la télé
annonçait la durée de la vidéo (et la position atteinte) ; sinon l'appli croyait à un arrêt et coupait tout.
**Fait** : une vidéo est terminée dès que la télé s'arrête après l'avoir vraiment lue : toujours dans un album,
et pour une vidéo seule si sa durée est inconnue ou presque atteinte. Fausse télé : `FAKE_NODUR=1` (pas de
durée annoncée). Vérifié : vidéo → fin reconnue → photo, renvoyée une fois, affichée. Tests 17/17, lint propre.
**Précision du testeur** : c'est la télé qui a quitté la diffusion pour repartir sur HDMI, comme à chaque fin de
vidéo en DLNA sur la Philips.
**En plus** : en DLNA, quand la durée est connue, l'appli passe à la suite 2,5 s avant la fin : la télé
ne ferme pas son lecteur et ne repasse pas par HDMI. Sans durée connue, la télé passe par HDMI puis revient dès
que l'appli envoie la suite. Vérifié sur la fausse télé : passage à la photo à 24 s sur 25, sans « LECTEUR FERMÉ ».

## 2026-11-25 (soir, 7) — Sous-titres des animés ; pourquoi la déconnexion repart sur HDMI
**Retour du testeur** : sur un animé (« Yomi no Tsugai »), aucun sous-titre trouvé ; sur les films, impeccable.
Le journal du téléphone avait déjà effacé la séance : MIUI le remplit en 25 min environ.
**Cause** : les animés sont nommés « [Groupe] Titre - 05 (1080p) », sans SxxEyy. L'appli croyait à un film et
cherchait au mauvais endroit. Les sous-titres existent bien : `tt37532356:1:5` → 13 pistes, dont l'anglais.
**Fait** : reconnaissance des formats d'animés (« - 05 », « 2nd Season - 05 », « S2 - 03 », « Episode 7 », « 12v2 »,
numéros absolus « One Piece - 1120 »), sans confondre « Film - 2019 » ni les tirets d'un titre. Recherche de
secours : l'autre type (film/série), puis l'annuaire des animés Kitsu (titres japonais → IMDb). Journal clair
quand le titre est introuvable. Tests : 6 formats d'animés + 2 cas « pas un épisode ».

**Déconnexion → HDMI 1** : le « Stop » DLNA ferme le lecteur de la Philips, qui repart sur sa dernière source.
Les applis Cast partenaires (YouTube, Twitch) reviennent à l'accueil, parce que c'est la fermeture d'une appli
de la télé. Ce que la télé offre en lecture seule : l'API JointSpace v6.1 (ports 1925/1926) avec la touche
« Home », mais protégée par un appairage (`digest_auth_pairing` : code PIN affiché une fois sur la télé).
Toutes les commandes répondent 403 sans appairage. Rien n'a été envoyé à la télé.
**Décision** (testeur) : pas d'appairage par code PIN, trop lourd pour ce seul gain. Le retour sur HDMI après
« Se déconnecter » reste tel quel (comportement de la Philips en DLNA).

## 2026-11-25 (soir, 8) — Animé « Yomi no Tsugai » : pas d'épisode, pas de sous-titres ; pause trop tôt
**Journal** : fichier transmis par Stremio : `[Judas] Yomi no Tsugai - S01E01v2.mkv`.
- Le « v2 » collé à l'épisode cassait la lecture de « S01E01 ». L'appli l'a pris pour un film : pas de numéro
  d'épisode dans le titre, et la recherche de sous-titres a renvoyé 0 résultat (`tt37532356` en film).
- « 701 Transition not available » : Play envoyé pendant que la télé chargeait encore → erreur affichée.
- Ce MKV contenait ses propres sous-titres, que le testeur a choisis avec la télécommande. Les sous-titres de
  l'appli faisaient donc doublon (et la Philips ignore de toute façon les sous-titres externes en DLNA).
**Fait**
- « S01E01v2 » reconnu (test ajouté). Titre : « Daemons of the Shadow Realm S01E01 », sur la télé comme dans
  l'appli.
- Play : sur une erreur 7xx, on attend que la télé soit prête et on réessaie (3 fois).
- Pause : plus au dépliage des sous-titres, seulement au premier changement (piste, décalage, fichier).
  Replier relance la lecture.
- Télé DLNA + vidéo non MP4 : la ligne indique « Avec la télécommande » et une note explique que la vidéo a
  souvent ses propres sous-titres, à choisir dans le menu de la télé. Le décalage est masqué (sans effet).
- Tests 17/17, lint propre. Vérif émulateur de la pause interrompue : l'émulateur ne joignait plus la fausse
  télé (réseau interne de l'émulateur).
- Pause pour réglage : seulement si la vidéo joue (pas pendant le chargement, où la télé répond 701 — vu par le testeur en dépliant les sous-titres pendant le chargement).

## 2026-11-25 (soir, 9) — Épisode visible, sous-titres « déjà dans la vidéo », vrai lecteur dans les notifications
**Retours du testeur** : le numéro d'épisode apparaît sur la télé mais pas sur le téléphone (le logo de la série
remplace le titre). Le MKV de Stremio avait ses propres sous-titres, bien meilleurs et réglables sur la télé ;
le texte de l'appli était un pavé illisible. Il voudrait un vrai petit lecteur dans les notifications.
**Fait**
- Téléphone : « Saison 1 · Épisode 1 » sous le logo de la série (saison et épisode gardés dans `Media`).
- Repérage des sous-titres d'un MKV/WebM : lecture du premier 1,5 Mo (`MkvProbe` : élément Tracks, en ignorant sa
  mention dans l'index SeekHead). Si la vidéo en a, la ligne affiche « Déjà dans la vidéo (Français, Anglais) »
  avec une phrase : « Choisis-les avec la télécommande de la télé. ». L'appli n'en superpose pas d'autres. Le
  pavé est supprimé. Tests sur deux MKV fabriqués avec ffmpeg (avec et sans sous-titres).
- Notification = lecteur du système (`MediaSession` + `MediaStyle`) : image du film, titre, télé, lecture/pause,
  −10 s / +10 s, barre de progression, arrêt ; aussi sur l'écran verrouillé. Dans un album : photo précédente /
  suivante, et lecture/pause = diaporama. Vérifié sur émulateur : pause et +10 s depuis le lecteur système
  arrivent bien à la télé.
- Tests 20/20, lint propre.
- Vidéo avec ses propres sous-titres : plus de choix de piste ni d'ajout de fichier (les nôtres ne s'afficheraient pas par-dessus) ; seulement « Déjà dans la vidéo » et la phrase sur la télécommande (souhait du testeur).

## 2026-11-25 (soir, 10) — Finitions visuelles
**Retour du testeur** : lecteur des notifications « aussi bien que YouTube, voire mieux » (rendu HyperOS sur son
Xiaomi). À peaufiner : la liste des langues coupée (« Déjà dans la vidéo (Ann… ») et illisible même dépliée ; la
surbrillance du dépliage qui ne couvre pas toute la carte ; un en-tête plus moderne qu'un aplat noir.
**Fait**
- Ligne repliée : « Déjà dans la vidéo » seulement ; dépliée : les langues en entier (« Français, Anglais »),
  puis la phrase sur la télécommande.
- Le dépliage réagit sur toute la largeur de la carte, surbrillance comprise.
- Ambiance derrière la barre d'état et l'en-tête, façon YouTube : l'image du film très floutée sur l'écran
  Lecture, sinon un dégradé vert sombre ; fondu vers le fond, sans bord net, avec un fondu enchaîné quand la
  vidéo change. Couleurs du texte de l'en-tête corrigées (fond transparent).
- Vérifié sur émulateur : accueil, et un MKV nommé comme l'animé du testeur → « Daemons of the Shadow Realm »,
  « Saison 1 · Épisode 1 », sous-titres « Déjà dans la vidéo » (français, anglais). Tests 20/20, lint propre.

## 2026-11-25 (soir, 11) — Barre d'onglets « verre fumé »
Trois propositions montrées au testeur en sombre et en clair (A verre fumé, B fondu, C teinté). Choix : **A**.
La pilule est translucide (72 %), avec un liseré clair et un reflet. Les écrans défilent dessous et finissent
au-dessus (`LocalBottomInset`), sauf le navigateur. Les variantes B et C sont retirées du code.
Mode clair : le testeur ne le trouve pas beau ; recommandation : passer en sombre uniquement (identité ambre sur
noir, usage le soir devant la télé, un seul thème à soigner). En attente de sa décision.
**Décision** (testeur) : thème sombre uniquement. Le thème clair est supprimé ; les icônes des barres système
restent claires même si le téléphone est en mode clair (vérifié sur émulateur). Opacité de la barre portée à 84 %
pour que les noms des onglets restent lisibles au-dessus d'une affiche.

## 2026-11-25 (soir, 12) — Navigateur : la bonne vidéo, sa durée, l'en-tête qui se cache, les séries
**Retour du testeur** : sur un site de streaming pris au hasard, l'épisode (25 min) n'était pas proposé, seulement
3 vidéos de 12 s. Il voudrait voir la durée, un en-tête qui se cache en descendant, et de l'aide pour les séries
(changer d'épisode, savoir où on en est). Le journal du téléphone n'avait plus la séance.
**Causes probables** : seules les adresses en .mp4/.m3u8… étaient proposées (beaucoup de lecteurs servent sans
extension), et le script de repérage ne tournait que dans la page, pas dans le cadre du lecteur intégré.
**Fait**
- Adresse sans extension demandée « à partir de l'octet 0 » (ou qui ressemble à une vidéo) : on lit 1 Ko pour
  vérifier (#EXTM3U, ftyp MP4, MKV, type vidéo) avant de la proposer (60 vérifications max par site).
- Script de repérage injecté dans tous les cadres dès leur ouverture (`addDocumentStartJavaScript`).
- Durée : HLS (somme des segments, « Direct » sans fin), MP4 (boîte mvhd), lecteur de la page. Liste triée par
  durée. **Moins d'une minute : pas proposée du tout** (souhait du testeur). Sans nom de fichier, la vidéo prend
  le titre de la page.
- En-tête de l'appli et barre d'adresse cachés quand on descend dans la page, réaffichés en remontant.
- Séries : bouton « Épisode 13 → » quand l'adresse contient un numéro d'épisode (episode-12, ep-07-vostfr, ?ep=3,
  …/saison-1/5). Caster depuis un site retient la page : le favori de ce site affiche « · Ép. 12 » et y reprend.
  Les vidéos « Reprendre » venues d'un site rouvrent leur page (les liens de streaming expirent).
- Vérifié sur émulateur avec un faux site (lecteur dans un cadre d'un autre domaine, vidéo /get?id=… sans
  extension, pub de 12 s) : 1 vidéo, « 25 min », titre de la page, pub masquée, bouton Épisode 13, en-tête caché
  au défilement. Tests : 22 (dont les formes d'adresses d'épisodes), lint propre.
**Essai sur le vrai site du testeur (émulateur, nouvelle version)** : la page d'épisode sert la vidéo en HLS
par des adresses opaques « /v/<jeton> » (sans extension ni mot-clé, chargées par le lecteur sans « Range »).
Les 3 vidéos de 12 s vues par le testeur étaient des clips d'aperçu du site (« …/clips/…-1080.mp4 »).
- Ajout : vérification des adresses « jeton » (/x/<24+ caractères>), sauf /api/. Les morceaux d'un flux déjà
  trouvé (listes de variantes, fragments fMP4) et les fichiers de moins de 8 Mo ne sont pas proposés ; après
  4 réponses non vidéo, on cesse de vérifier ce préfixe.
- Résultat : une seule vidéo proposée, « Regarder maintenant : Black Clover Season 2 », HLS, « 23 min ». Le flux
  s'ouvre dans l'appli (23:50, pas de protection). Pas casté (pas de télé sur l'émulateur).

## 2026-11-25 (soir, 13) — otakubinge : « fichier non pris en charge », puis 403
**Journal du téléphone** : 1er essai lu par la télé puis refusé (« fichier non pris en charge ») ; 2e essai, le site
répond 403 sur le flux.
**Analyse (émulateur, PC)** :
- Le site sert l'image et le son **séparément** (deux listes HLS, morceaux MPEG-TS chiffrés AES-128). La liste
  choisie au 1er essai était le **son seul** (AAC, vérifié en déchiffrant un morceau) : la télé ne l'a pas lu.
- L'adresse principale de chaque épisode (`/w/<jeton>`, donnée par `/api/media/content/…`) renvoie 403 hors du
  lecteur du site (Cloudflare, session). Les listes `/v/<jeton>` ne se relisent pas : redemandées par l'appli,
  elles renvoient 403. **Ce site protège ses vidéos : seul son lecteur peut les lire.** Aucune appli de cast ne
  peut les relire.
**Fait**
- `HlsKind` : on lit le début du premier morceau (déchiffré si AES-128) et on regarde ses pistes (PMT). Une liste
  **son seul n'est plus jamais proposée** ; une image sans son est signalée « Image seule, sans le son ».
- `TsMerge` : quand un site sert image et son à part (sans protection), le relais les **fusionne** en un seul
  MPEG-TS (pistes renumérotées, PMT commune, entrelacement par horodatage). La liste du son est associée à l'image
  dans le navigateur ; dans ce cas on passe par le flux continu (DLNA). Test : MPEG-TS image + MPEG-TS son
  fabriqués avec ffmpeg → un flux à 2 pistes lu par ffprobe.
- Site qui refuse la vidéo à la télé (403/401) : message clair « Ce site protège ses vidéos… » au lieu d'un échec
  muet. Retrait des vérifications en double (pour ne pas consommer les jetons du lecteur du site).
- Tests 23/23, lint propre.

## 2026-11-25 (soir, 14) — Navigateur : plus d'en-tête d'appli, barre d'adresse fluide
**Retour du testeur** : la barre qui se rétracte est brutale (la page monte tout en haut puis redescend) ; il
propose de retirer l'en-tête « Universal TV Cast » de l'onglet Navigateur (la barre d'onglets suffit pour en sortir).
**Fait** : pas d'en-tête d'appli dans le Navigateur. La marge de la barre d'état reste fixe, et seule la barre
d'adresse glisse (260 ms, avec fondu) : plus de saut. Le clavier se ferme quand on valide une adresse, et le
fermer libère le champ (sinon la barre restait affichée). Vérifié sur émulateur (otakubinge) : en haut → on
descend, la barre disparaît → on remonte, elle revient. Tests 23/23, lint propre.

## 2026-11-25 (soir, 15) — Pause pendant le chargement, liste qui suivait l'ancienne page
**Retour du testeur** : sur otakubinge, ça marche (mieux que le lecteur du site), mais l'appli s'est « bloquée »
(il a dû la quitter) ; et après avoir changé d'animé, la liste proposait encore Black Clover.
**Journal** : `Dlna.pause` → « 701 Transition not available » à répétition (pause demandée pendant que la télé
chargeait), avec un message d'erreur à chaque appui.
**Fait**
- Pause pendant un chargement : on attend la télé (5 s) ; si elle charge encore, la pause est mise de côté,
  affichée tout de suite, et appliquée dès que la télé lit. Rappuyer l'annule. Play passe aussi par l'attente.
- La liste des vidéos se vide à chaque nouvelle page, y compris quand un site « une seule page » change d'adresse
  sans recharger (on ignore l'ancre et les paramètres de position ?t=…). Avant : seulement en changeant de site.
- Tests 23/23, lint propre.
- Titre des vidéos trouvées sur un site nettoyé (`PageTitle`) : sans le nom du site ni les accroches (« Regarder maintenant : Black Clover Season 2 · Otakubinge » → « Black Clover Season 2 »). Tests 24/24.

## 2026-11-25 (soir, 16) — Favoris = marque-pages de séries, logos, accueil du navigateur
**Demande du testeur** : favoris plus jolis (nom du site, logo) ; surtout, des favoris qui servent de
marque-pages pour suivre plusieurs séries (comme ses light novels : le favori avance là où il en est) ;
accueil et accueil du navigateur qui répondent vite à cet usage.
**Fait**
- Favori = marque-page : clé de série (adresse sans le numéro d'épisode : `Episodes.seriesKey`). Caster un épisode
  d'une série suivie, ou ouvrir un épisode plus loin, fait avancer le favori (adresse, n° d'épisode) et le remonte
  en tête. Plusieurs séries d'un même site sont suivies séparément. Étoile pleine sur tous les épisodes d'une
  série suivie. Titre du favori : la série seule (« Mon Anime – Épisode 12 » → « Mon Anime »).
- Logos des sites : donnés par le navigateur lui-même (`onReceivedIcon`), gardés sur le téléphone (aucun service
  extérieur). Sinon, première lettre sur une pastille.
- Cartes de favoris (logo, site, titre, « Ép. 13 » / « Reprendre » / « Ouvrir », ✕) : en premier sur l'accueil,
  et en tête de l'accueil du navigateur. Bouton maison dans la barre du navigateur pour y revenir.
- Barre d'adresse : le toucher plaçait le curseur au milieu et le texte tapé s'insérait dans l'ancienne adresse ;
  la sélection complète est refaite juste après le toucher. Plus d'« about:blank » affiché.
- Vérifié sur émulateur : favori otakubinge (logo) ; favori « Mon Anime » ép. 12 → bouton Épisode 13 → la carte
  affiche « Ép. 13 ». Tests 26/26 (clé de série, nom de série), lint propre.

## 2026-11-25 (soir, 17) — Favoris : site ≠ série, images, prévenir l'utilisateur
**Demande du testeur** : ne jamais transformer un favori de site en favori de série ; ne rien perdre en allant
sur l'accueil d'un site ; une iconographie par type, avec une image ; prévenir l'utilisateur de ce que l'appli
fait toute seule.
**Fait**
- Deux sortes de favoris : **site** (page d'accueil du site : `key` = domaine) — ne bouge jamais ; **série / film**
  (toute autre page). Seul un favori de série avance, et seulement vers un épisode de la **même** série (même
  clé) : aller sur l'accueil, une autre série ou un film ne touche à rien. Retrait de l'ancienne reprise par site.
- Image de la page (og:image / twitter:image / affiche du lecteur, lue par le script dans la page principale),
  gardée avec le favori et mise à jour quand il avance.
- Cartes : **série/film** = grande image, pastille « Ép. 13 » (signet) ou « Ouvrir », titre, logo + site ;
  **site** = grand logo, nom, étiquette « Site ». Séries d'abord, sites ensuite, sur l'accueil et l'accueil
  du navigateur.
- Messages (4,5 s, lus par TalkBack) : « « Mon Anime » suivie : le marque-page avancera tout seul à chaque épisode
  regardé », « Mon Anime : marque-page avancé à l'épisode 13 », « « X » ajouté aux favoris ».
- Vérifié sur émulateur (faux site sans lecteur : la vidéo intégrée faisait planter l'émulateur côté PC).
  Tests 26/26, lint propre.

## 2026-11-25 (soir, 18) — « Reprendre » par série, accueil des habitués

- « Reprendre » : une seule carte par série (adresse de la page sans le numéro d'épisode ; pour Stremio, nom de la série + saison), avec la pastille de l'épisode (« Ép. 5 », « S2 · Ép. 5 ») et l'image de la page, comme les favoris. Les anciennes listes sont regroupées à la lecture.
- Les séries en favori sont dans « Reprendre » (étoile) et y restent : le favori, c'est « reprendre plus tard ». La rangée de l'accueil ne garde que les sites (« Mes sites »).
- Appui long sur une carte (reprendre, favoris, sites) : menu « Garder ici (favori) » / « Retirer des favoris » / « Retirer de « Reprendre » » ; les croix disparaissent. Actions d'accessibilité équivalentes.
- Accueil : dès qu'il y a de l'historique ou des favoris, plus de grand bandeau ni de carte « Depuis une autre appli » ; « Reprendre » en premier, les quatre façons de caster en une rangée compacte. Le nouveau venu garde l'accueil complet.
- Vérifié sur l'émulateur avec des données d'exemple (3 épisodes → 1 carte, The Office S2E4/E5 → 1 carte, film, retrait, mise en favori).
- L'image de la page (celle des favoris) sert partout : en-tête « film » et fond flouté du lecteur, carte « Reprendre », notification et écran de la télé (Cast). Si elle arrive après le lancement, le lecteur et « Reprendre » la prennent au vol. « Reprendre » garde l'image large (fond) plutôt que l'affiche verticale.

## 2026-11-25 (soir, 19) — Retours sur « Reprendre » et « Mes sites »

- « Mes sites » propose aussi les sites déjà utilisés pour caster (pas seulement les favoris) ; appui long : « Garder ici (favori) » / « Ne plus proposer ». Les sites en favori ont une étoile et passent devant.
- « Reprendre » : seulement les épisodes (page de site avec numéro d'épisode) ou les vidéos des autres applis ; plus les pages d'accueil ou sans épisode, dont le titre ne voulait rien dire.
- Avancement noté toutes les 15 s (et la fin de lecture) : un film fini ou à moins de 5 min de la fin disparaît ; un épisode fini (≈ 8 % de la fin) propose l'épisode suivant (« Ép. 6 », adresse suivante).
- Cartes allégées : titre seul, sans le nom du site.
- « Tout voir → » à côté de « Reprendre » (dès 3 cartes) : grille de tout ce qui est à reprendre, retour par la flèche ou le geste retour.

## 2026-11-25 (soir, 20) — Sites compacts, partout

- Un favori posé sur une page sans numéro d'épisode (accueil d'un site, catalogue…) n'est plus une « série » : il va dans « Mes sites » (cas d'Otakubinge qui restait dans « Reprendre »). « Reprendre » ne montre que les séries à épisode.
- « Mes sites » en petites tuiles (logo + nom court), « Tout voir » au-delà de 4 ; sites où l'on a casté ou suivi une série ajoutés d'office.
- Accueil du navigateur : « Mes sites » en grille, puis les séries suivies.
- Barre d'avancement (fine, couleur d'accent) au bas de l'image des cartes « Reprendre » commencées et pas finies.
- Reprise au bon endroit : la carte (vidéo d'une autre appli) ou un nouveau cast depuis la même page d'épisode repart 5 s avant l'arrêt (s'il a dépassé 30 s). Même mécanisme que la reprise Stremio (DLNA : placement après le début de lecture ; flux continu : ?start=).
- Sites dont l'adresse ne contient pas le numéro d'épisode (un identifiant par vidéo, ex. /watch/a8Kz93) : l'épisode est lu dans le titre de la page (« Frieren – Épisode 8 »), et la série regroupée par site + nom. Une page sans épisode (film) reste dans « Reprendre », sauf l'accueil d'un site et les vidéos de moins de 10 min. Tests PageTitle.episode.
- Favori posé sur un épisode d'un site sans numéro dans l'adresse : reconnu comme série (épisode lu dans le titre), rangé dans « Reprendre » et fusionné avec la carte de la série (même nom sur le même site) au lieu de « Mes sites ». Seuls les sites et leurs pages d'accueil restent dans « Mes sites ». « Retirer de Reprendre » retire aussi ce favori.

## 2026-11-25 (soir, 21) — Navigateur : plus d'écran noir muet

- Retour de Guillaume : un site restait tout noir, sans barre d'adresse (capture). Journaux sans indice sur le site.
- Erreurs de chargement de la page principale (pas d'Internet, site introuvable, trop lent, certificat invalide — jamais de passe-droit SSL) : écran clair avec « Réessayer », « Ouvrir dans le navigateur du téléphone », « Retour à l'accueil du navigateur ».
- Page chargée mais vide (ni texte, ni image, ni lecteur après 4 s, ou en revenant sur l'onglet) : « La page reste vide » + « Réessayer sans bloqueur de pubs » (pour ce site, le temps de la visite) et ouverture dans le navigateur du téléphone ; code HTTP affiché s'il y en a un.
- La barre d'adresse est toujours visible en cas de problème et en revenant sur l'onglet Navigateur.
- Vérifié sur l'émulateur : page noire servie par le PC, domaine inexistant.
- kool.to (bloqué par le fournisseur d'accès : faux certificat « localhost » en HTTPS) reproduit sur l'émulateur. Cause de l'écran noir *complet* : la vue web en échec se dessinait par-dessus la barre d'adresse. Elle est maintenant masquée (et coupée à ses limites) tant qu'un problème est affiché ; la barre montre l'adresse demandée ; l'écran « Connexion non sécurisée » ajoute que c'est souvent le fournisseur d'accès qui bloque le site.

## 2026-11-25 (soir, 22) — Réglages : poignée et signature du studio

- Les panneaux du bas (réglages, télés, aide) s'arrêtent sous la barre d'état : la poignée ne passe plus derrière la caméra quand on les remonte tout en haut.
- Bas des réglages : carte « Fait avec soin par » + vrai logo Rollando Studio (vitrine/logo, version sur fond sombre, PNG 900 px) + « Découvrir nos autres applis » ; toute la carte ouvre rollandostudio.com. « Fait avec soin par Rollando Studio » retiré du texte « À propos » (doublon). « Qualité en guise de promo. »
- Écran d'attente de la télé : signature « by » + logo du studio, petite (logo 180 px sur 1920) et semi-transparente, en bas au centre. Rendu jugé sur une maquette à l'échelle (pas de télé joignable depuis l'émulateur).

## 2026-11-25 (soir, 23) — Regarder sur le téléphone

- « Regarder sur le téléphone » (onglet Lecture) : lecteur plein écran Media3/ExoPlayer 1.6.1 (+0,8 Mo). Même vidéo que pour la télé : en-têtes du site (Referer, User-Agent de la WebView), son séparé fusionné (MergingMediaSource), HLS chiffré AES, sous-titres choisis dans l'appli avec leur décalage (SRT), sinon pistes intégrées dans la langue du téléphone. Paysage/portrait selon la vidéo, plein écran, ±10 s.
- Bascule : la télé se met en pause et le téléphone reprend au même endroit ; « Envoyer sur la télé » renvoie la vidéo à l'endroit atteint (sans télé choisie : recherche + « Choisis une télé »). L'avancement (toutes les 15 s, à la sortie, à la fin) nourrit « Reprendre ».
- Barre d'adresse touchée : boutons Copier le lien / Partager (à la place de l'étoile et du rechargement).
- Correctif ancien : une adresse générique (« index.m3u8 »…) ou un direct déclenchait une recherche de sous-titres qui trouvait un film « Index » (sous-titres anglais appliqués à Arte en direct). Plus de recherche dans ces cas, et titre « Direct » / « Vidéo » au lieu du nom de fichier.
- Vérifié sur l'émulateur avec le direct officiel d'Arte (liste Free-TV/IPTV) : lecture, bascule retour à 29:28.
- IPTV légales pour tester plus tard : github.com/Free-TV/IPTV (chaînes gratuites, flux officiels : Arte, TV5Monde, franceinfo…).
- Retour de Guillaume : passer sur le téléphone pendant que la télé démarre laissait les deux lire en même temps, à des endroits différents (la pause n'était envoyée que si la télé jouait déjà). Maintenant une seule lecture à la fois : la télé s'arrête sur l'écran d'attente quel que soit son état (chargement, lecture, pause) et un cast en attente est annulé. Vérifié avec la fausse télé : écran d'attente envoyé au toucher, puis « Envoyer sur la télé » → vidéo reprise à 32 s.
- Trouvés pendant ce test : « film.mp4 » identifié comme un vrai film (affiche et logo d'un film d'horreur) → noms génériques élargis (film, movie, episode, sample, test, trailer…). Carte « Garde Universal TV Cast actif » : le bouton « Ne plus afficher » écrasé une lettre par ligne sur écran étroit → boutons l'un sous l'autre.

## 2026-11-25 (soir, 24) — Le direct, traité comme du direct

- Retours de Guillaume : sur le téléphone, le direct d'Arte partait n'importe où dans la fenêtre de 30 min (on y reprenait la position de la télé) ; pause / retour au direct pas clairs ; dans « Reprendre », la chaîne apparaissait en « A », sans nom ni distinction d'un programme.
- Direct reconnu (liste HLS sans fin) : `CastState.live`. Onglet Lecture : badge « EN DIRECT » à la place de la barre de temps, ±10 s désactivés ; en pause, « En pause · Lecture reprend au direct » et Lecture renvoie le flux (télé au direct, pas là où l'image s'était figée).
- Téléphone : un direct démarre au direct ; badge rouge « EN DIRECT », qui devient « Revenir au direct » en pause ou quand on a pris du retard (au-delà de la cible du lecteur + 15 s) et y ramène. Pas d'avancement noté pour un direct ; retour vers la télé au direct.
- Chaînes : rangée « Chaînes » sur l'accueil (plus dans « Reprendre ») ; nom reconnu (Arte, France 24, TV5Monde, franceinfo, BFMTV… ~35 chaînes, avec leur couleur), sinon titre, sinon site ; tuile à la couleur de la chaîne + « ● En direct » ; appui long : Renommer (nom gardé) / Retirer la chaîne. Titre du lecteur : « Arte » au lieu de « Direct ».
- Vérifié sur l'émulateur avec la fausse télé et le direct officiel d'Arte : pause → Pause, lecture → flux renvoyé ; téléphone au direct, badge rouge → « Revenir au direct » après pause ; rangée Chaînes, renommer.
- Téléphone, direct : plus de « 29:22 · 30:00 » (fenêtre du flux, sans sens) ; le retard s'affiche dans le badge : « −1:24 · Revenir au direct ». Vérifié sur l'émulateur.

## 2026-11-25 (soir, 25) — Direct sur la télé, version propre

- Retour de Guillaume : depuis la version précédente, Lecture après une pause renvoyait au direct (moins fluide qu'avant), ±10 s désactivés, pas de retard affiché, « index.m3u8 » comme titre.
- Pause → Lecture : simple Play, la télé reprend là où elle s'était figée (comme avant) ; le retard pris s'ajoute (« −0:10 »).
- ±10 s actifs sur un direct (appli et notification) en flux continu : le flux est renvoyé avec le retard voulu (`/ts/…?start=N` = N secondes de retard ; le relais remonte d'autant de morceaux dans ce que garde la liste), dans la limite de la fenêtre du flux moins 30 s. +10 s seulement si on est en retard.
- Badge : rouge « EN DIRECT » au direct, sinon « −m:ss » + bouton « Revenir au direct ». Le téléphone transmet son retard en renvoyant vers la télé.
- En-tête de l'onglet Lecture pour un direct : à la couleur de la chaîne, son nom en grand, l'état, et une ligne lue dans le flux lui-même (liste maître : définition, images/s, codec, langues audio et sous-titres s'il y en a plusieurs) — « 720p · 25 i/s · H.264 » pour Arte. Même ligne sous le titre des autres vidéos HLS.
- Titre d'un direct ouvert par son lien : nom de la chaîne reconnue (sinon « Direct »), aussi depuis « Coller un lien ».
- Vérifié avec la fausse télé et le direct d'Arte : Pause puis Play (pas de renvoi), −0:10 affiché, −10 s → start=20, Revenir au direct → start=0 et badge rouge.
- Retiré à la demande de Guillaume : la ligne d'infos techniques du flux (720p · 25 i/s · H.264…), qu'on ne peut pas changer et qui encombre (« il met son lien, il envoie, ça marche »). Code de lecture de ces infos supprimé aussi.
- Chaînes légales vérifiées (liste + morceaux vidéo téléchargés depuis la France, 25/11) pour ses tests : Arte, TV5Monde Info, TV5Monde FBS, ICI RDI, ICI Montréal, Savoir Média, TV Monaco, MonacoInfo, BX1. Écartées : rediffusions sur IP brutes (France 3, Gulli…), CGTN, RTL, TVM3 (pas de vidéo).

## 2026-11-25 (soir, 26) — Notification et direct arrêté par la télé

- Retour de Guillaume : plus moyen de lancer sur la télé (« tourne dans le vide »). Journaux du téléphone : la télé demande le flux continu, puis ~3 s après l'appli bascule sur l'écran d'attente (fin de vidéo détectée : un direct n'a pas de durée, donc tout arrêt après lecture = « fin ») ; les essais suivants : 716 Invalid CurrentURI. Chaîne en cause non identifiée dans les journaux ; pas reproduit avec la fausse télé (TV5Monde et Arte passent). Essai sur la vraie télé à faire avec son accord.
- Défense : un direct arrêté par la télé est renvoyé une fois (même retard) ; s'il est encore arrêté, message clair « La télé a arrêté ce direct. Réessaie, ou regarde-le sur le téléphone. » au lieu de l'écran d'attente silencieux.
- Notification (capture de Guillaume) : double logo (icône de l'appli en grand + icône de diffusion) et fond bleu au hasard. Sans affiche, image générée à la couleur de la chaîne (connue, sinon tirée du titre) avec son nom → fond orange pour Arte, plus de double logo. Direct : position inconnue → plus de « 00:00 … 00:00 ».
- Essai sur la vraie télé (avec l'accord de Guillaume), Arte : la Philips demande le flux, le lâche au bout de 5 s sans jamais lire (appli : « Arrêté ») ; relancé à la main une minute plus tard → lecture immédiate. Flux du relais capturé depuis le PC : identique octet pour octet aux morceaux d'Arte (H.264 High 720p + AAC, image clé en tête) → pas un défaut du relais. Interrogation de la télé (GetTransportInfo, lecture seule) : PLAYING sur /ts/r1 après la relance.
- Cause : la Philips rate parfois le premier envoi (vu en quittant l'écran d'attente). Correctif : si la télé n'a jamais lu et est arrêtée après le délai de grâce (12 s), la vidéo est renvoyée une fois, à la même position (vidéo ou direct). Fausse télé : option FAKE_DROPFIRST=1 qui reproduit ça ; vérifié (abandon à 21:27:02, renvoi à 21:27:14, lecture).
- Chaînes connues : de ~35 à ~150 (TNT, jeunesse, musique, sport, Belgique, Suisse, Québec, Luxembourg, Monaco, Afrique francophone, info internationale), dans `data/Channels.kt`. Reconnaissance sans accents, « France 2 » / « france-2 » / « france2 » (mots collés deux ou trois à la suite), « Canal+ ». Mots-clés trop courants resserrés (histoiretv, museumtv, tracetv…). Noms et couleurs utilisés seulement pour un direct (notification comprise). ChannelsTest (adresses réelles des flux testés, titres, faux positifs).
- Public international (demande de Guillaume) : chaînes du monde ajoutées (Royaume-Uni, Irlande, États-Unis, Allemagne, Autriche, Espagne, Italie, Portugal, Brésil, Pays-Bas, nordiques, Europe de l'Est, Grèce, Turquie, monde arabe, Asie, Océanie, Amérique latine, Afrique, info/sport internationaux). Mots-clés ambigus resserrés (nos, hr, tn, welt, record, history…) ; noms précis avant les généraux (ABC News avant ABC). Tests élargis (BBC One, Das Erste, Rai News 24, Antena 3, NHK World, CNN en Español… et faux positifs).

## 2026-11-25 (nuit) — Listes de chaînes (IPTV)
- Import d'une liste M3U par adresse (y compris collée dans « Lien ») ou par fichier ; Free-TV proposée pour essayer.
- Écran « Listes de chaînes » : recherche sans accents, filtres par groupe (pays du téléphone d'abord), logos.
- Les liens YouTube/Twitch des listes sont écartés ; User-Agent/Referer de la liste repris.
- Une chaîne regardée apparaît dans « Chaînes » avec son logo. Listes en ligne remises à jour une fois par jour.

## 2026-11-25 (nuit, 2) — Barre d'onglets floutée, barre de lecture du direct
- Barre d'onglets : flou de ce qui défile dessous (Haze 1.6.4), teinte vert fumé ; teinte presque opaque avant Android 12.
- Direct : la même barre que les vidéos, en rouge. Pleine au direct ; on peut la tirer pour remonter dans la fenêtre du flux
  (Castor.seekLive → castAt(retard)). Badge EN DIRECT / −m:ss et « Revenir au direct » dessous.
- Un direct n'est jamais considéré comme fini d'après la durée annoncée par la télé (la fausse télé en annonçait une).

## 2026-11-25 (nuit, 3) — Logos des chaînes, bien intégrés
- Un seul visuel de chaîne (ui/ChannelArt.kt) pour l'accueil, les listes, l'écran de lecture et la notification.
- Logo : celui fourni, sinon celui de la même chaîne (même flux, sinon même nom) dans une liste importée. Aucune autre source.
- Fond : la couleur qui domine sur le bord du logo (logo « en carré » prolongé), marges transparentes retirées ;
  logo détouré : couleur de la chaîne si elle contraste, sinon fond neutre clair ou sombre.

## 2026-11-25 (nuit, 4) — La télé ne se relance plus toute seule
- Rouverte depuis les applis récentes, l'appli recevait de nouveau le lien d'origine (Stremio, partage…) et le relançait
  sur la télé : on ignore désormais un lien marqué FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY. Vérifié sur l'émulateur (fausse télé).
- Envoi en attente d'une télé (aucune trouvée au moment de l'envoi) : valable 2 minutes seulement, au lieu de partir dès que
  la télé réapparaît, même des heures plus tard. Pareil pour les photos.

## 2026-11-25 (nuit, 5) — Source fermée, bouton Annuler
- Journal du téléphone : film Stremio repris à 2773 s alors que Stremio était fermé (127.0.0.1:11470 refusé) → la télé
  tentait, échouait, on renvoyait, sans erreur. Désormais, vidéo servie par une appli du téléphone : on vérifie qu'elle
  répond avant d'envoyer ; sinon « Stremio ne répond plus : l'appli a sans doute été fermée… », rien n'est envoyé.
- Chargement / envoi : les boutons de lecture (inutiles) sont remplacés par « Annuler ». L'écran revient tout de suite à
  l'arrêt ; la commande en cours est annulée, la télé passe ensuite sur l'écran d'attente. Vérifié sur l'émulateur.

## 2026-11-25 (nuit, 6) — Contrôle du direct, chaînes récentes
- Cause des soucis du direct sur la vraie télé : beaucoup de chaînes ne gardent que 30 s à 1 min en ligne. En pause, le
  relais ne téléchargeait plus ; à la reprise, les morceaux suivants avaient disparu → saut près du direct, retard
  recalculé à 0 (barre pleine, pas de « −x », pas de « Revenir au direct » en lecture).
- proxy/LiveBuffer.kt : le téléphone enregistre le direct en continu dans son cache (45 min / 1 Go max, effacé à l'arrêt),
  la télé lit à son rythme. Retard réel et plage disponible remontés à l'appli chaque seconde (journal « direct : … »).
  Test émulateur (Africa 24 English, fenêtre de 3 morceaux) : pause 60 s → reprise à la suite, retard 76 s.
- Barre du direct : déplaçable dès qu'il y a de quoi remonter ; sinon pas de barre. « Revenir au direct » dès 8 s de
  retard, en lecture comme en pause.
- Listes de chaînes : « Regardées récemment » en tête (sans recherche ni filtre). Historique porté à 60 éléments.

## 2026-11-25 (nuit, 7) — Mosaïque de chaînes, masquer
- Listes de chaînes en mosaïque de logos (4 colonnes sur téléphone), nom dessous, sans catégorie.
- Appui long : « Masquer cette chaîne » (lien mort…), avec « Annuler » quelques secondes ; « Réafficher les chaînes
  masquées (N) » à côté du nombre de chaînes. Sur une récente : « Retirer des récentes » (elle revient si on la regarde).

## 2026-11-25 (nuit, 8) — Source qui refuse : on s'arrête
- Journal du téléphone : une chaîne répondait 403 ; la télé échouait, l'appli renvoyait automatiquement (allers-retours).
- Le relais signale désormais tout refus net de la source (401, 403, 404, 410) quand la télé demande la vidéo :
  l'appli arrête (écran d'attente), sans renvoi, et l'explique. Pour une chaîne : « Cette chaîne ne répond pas
  (erreur 403) : lien mort ou réservée à son pays. Appui long dans la liste pour la masquer. » Vérifié sur l'émulateur
  (faux site qui passe en 403).

## 2026-11-25 (nuit, 9) — Vérifier la source avant d'envoyer
- castAt : avant tout envoi à la télé, petite demande à la source (début du fichier, ou un morceau du flux HLS :
  le dernier pour un direct). Refus net (401/403/404/410/451) ou serveur injoignable → rien n'est envoyé, message
  (« La vidéo ne répond pas : … hors ligne », refus avec code, appli source fermée). Source lente (> 8 s) : on envoie
  quand même, comme avant. Vérification gardée 60 s (déplacements dans la vidéo sans délai).
- Émulateur (fausse télé) : injoignable 0 envoi + message ; 403 0 envoi + message ; lente → envoyée ; Arte 1 envoi, lecture.

## 2026-11-25 (nuit, 10) — Zapper depuis l'écran de lecture
- Direct : plus de carte « Sous-titres » ni de « Mode de lecture » (ce dernier retiré de l'écran de lecture partout ;
  il reste dans les Réglages). À la place, « Zapper » : chaînes proches (même catégorie de la même liste, à partir de la
  chaîne en cours, récentes d'abord), « Une chaîne au hasard », « Toutes les chaînes → ».
- Accueil : le lien « Listes de chaînes » du bas n'apparaît plus quand la rangée « Chaînes » (qui l'a déjà) est là.

## 2026-11-25 (nuit, 11) — Chaînes : tout ensemble, écran épuré
- Accueil : tuiles de chaînes sans point rouge ni nom (le logo suffit ; nom lu par le lecteur d'écran) ; lien « Toutes les chaînes ».
- Écran « Chaînes » : toutes les chaînes de toutes les listes en une seule mosaïque (doublons fusionnés par flux ou nom,
  logo préféré), plus celles regardées par leur lien (Arte…). Plus de boutons de listes ni de « iptv-org · France · 230 ».
- ⋮ : « Mes listes de chaînes » (nombre, mettre à jour, supprimer, ajouter) et « Chaînes masquées (N) » (réafficher
  une à une, ou tout). Le zapping puise aussi dans toutes les chaînes.

## 2026-11-26 — Page Chaînes : écran de détail au style de l'appli, tuiles unifiées
- Page « Chaînes » plein écran (pas d'en-tête d'appli ni de barre d'onglets : on y vient de l'accueil comme de la lecture),
  avec le fond de l'appli (lueur verte en haut). Recherche et catégories en verre fumé, qui s'effacent en descendant et
  reviennent en remontant ; catégories en pilules ambrées comme les onglets.
- ChannelTiles (ChannelArt.kt) : un seul format de tuile (96 dp, écart 4 dp, coins 12 dp, marge du logo 9 dp) pour
  l'accueil, la mosaïque, les récentes et le zapping.
- Récentes de la page Chaînes : retrouvées aussi par le nom (chaîne présente dans deux listes).

## 2026-11-26 — Direct : retard compté à l'horloge
- Le retard était déduit de ce que le relais avait envoyé à la télé ; la télé garde une réserve d'avance remplie par
  à-coups → chiffre instable, pause qui ne comptait pas, retour à « direct » après la reprise.
- Désormais (LiveClock) : retard = retard demandé à l'envoi + temps passé sans lecture (pause, chargement). ±10 s et la
  barre visent le moment du programme vu à l'instant de l'appui (le temps de l'attente est rattrapé).
- LiveBuffer : « direct » = 3 morceaux avant le plus récent, pour startFor comme pour la plage disponible (cohérents).
- Émulateur (fausse télé, Africa 24 English) : pause 10 s → −0:11 ; reprise stable −0:15 ; −10 → −0:27 stable, flux demandé start=26.

## 2026-11-26 — Un seul écran de lecture, télé ou téléphone
- PhonePlayerActivity supprimée. cast/PhoneEngine.kt : un ExoPlayer pour l'appli ; CastState.onPhone. castAt() bascule sur
  phonePlay() quand onPhone : reprise, déplacements du direct, chaîne suivante, zapping passent tous par le même chemin.
- Direct sur téléphone : lu depuis l'enregistrement du relais (/ts/…/stream.ts?start=N, ProgressiveMediaSource) →
  pause, ±10 s, barre, « Revenir au direct » identiques à la télé (même LiveClock). Vidéo : lue à sa source (en-têtes,
  son séparé, sous-titres de l'appli).
- Écran de lecture : la vidéo à la place du grand cadre, bouton plein écran ; téléphone tourné → plein écran (commandes
  habituelles en surimpression, un appui les montre/cache). « Regarder sur le téléphone » ↔ « Regarder sur la télé »,
  au même endroit (le passage télé → téléphone garde l'enregistrement du direct). L'appli cachée → pause.
- Émulateur : Africa 24 English sur téléphone : pause 10 s → −0:12 ; −10 → −0:26 (start=26) ; plein écran OK ;
  retour télé → start=26, −0:27.

## 2026-11-26 — Lecteur téléphone : rotation, plein écran standard
- Écran noir / image figée au retour en portrait : la vidéo restait accrochée à l'affichage plein écran. Un seul
  affichage la tient à la fois (le cadre se désactive en paysage et est recréé au retour). Émulateur : portrait →
  paysage → portrait → paysage → portrait, vidéo qui bouge à chaque étape.
- Plein écran : −10 / lecture-pause (ambrée) / +10 au centre, plus petits ; barre en bas avec la sortie du plein écran en
  bas à droite ; titre en haut. Double-tap gauche/droite = −10 / +10 s (plein écran et cadre), rond au milieu de la moitié.

## 2026-11-26 — Plus d'envoi à la télé sans choix
- Castor.playOnTv (session) : null au lancement → ouvrir une vidéo/chaîne ne l'envoie nulle part ; l'écran de lecture
  propose « Lire sur <télé> » ou « Regarder sur le téléphone ». Le choix est retenu jusqu'à la fermeture (zapping au même
  endroit). « Choisir une télé » depuis l'écran de lecture lance dès la télé choisie.
- Exception : une autre appli qui nous envoie une vidéo (« Caster sur la télé ») = télé explicite (open(toTv = true)).
- Émulateur : appli relancée, appui sur une chaîne → 0 envoi ; choix téléphone puis « chaîne au hasard » → téléphone, 0 envoi.

## 2026-11-26 — Programme en cours (guide déclaré par les listes)
- Vérifié : les flux en direct ne portent pas le nom du programme (Arte, TV5, NHK, DW, Africa 24, Rai News : pas d'EIT/SDT,
  ID3 sans titre). Source retenue avec Guillaume : les guides XMLTV que déclare la liste importée (x-tvg-url / url-tvg).
- data/Epg.kt : seulement le guide du pays de la chaîne (tvg-country, ou suffixe du tvg-id, sinon pays du téléphone ;
  fichiers « _FR1.xml.gz »…), jamais « ALL_SOURCES ». Téléchargé au plus toutes les 12 h, lu en flux, résumé gardé en
  cache (programmes de −6 h à +30 h). Correspondance par le nom de la chaîne (les tvg-id des listes ≠ ceux des guides).
- Écran de lecture : « En ce moment » + progression + « Ensuite · 01:32 … » (sous l'en-tête télé ; à côté du logo sur
  téléphone ; titre seul à côté du logo en plein écran). Émulateur : guide FR (4 Mo) lu en 2,7 s ; Arte : 50 programmes.

## 2026-11-26 — Guides : serveurs Xtream et adresse à la main
- Iptv.xtreamGuide : liste « …/get.php?username=…&password=… » → guide « …/xmltv.php?… » (déduit, rien à saisir).
- « Mes listes » ⋮ › Guide des programmes : adresse facultative par liste (Playlist.guide).
- IptvStore.Guide(url, byCountry) : une liste qui déclare beaucoup de guides (Free-TV) = collection par pays → seulement
  celui du pays ; guide unique, Xtream ou saisi → toujours lu. Correspondance par identifiant de guide (tvg-id) puis par nom ;
  toutes les fiches de la chaîne (plusieurs listes) essayées. Guide injoignable : nouvel essai au bout d'1 h.
- Émulateur, faux fournisseur Xtream (get.php sans guide en tête, xmltv.php « Émission de test ») : programme affiché
  via l'identifiant (le guide nommait la chaîne autrement). Test unitaire IptvTest.findsXtreamGuideAndHeaderGuides.

## 2026-11-26 — Télé éteinte (veille réseau)
- Constaté (lecture seule) : la Philips éteinte répond sur le réseau (description 200, port ouvert) et, après un envoi,
  reste « TRANSITIONING », RelTime 0:00:00. Impossible de savoir d'avance qu'elle est éteinte.
- Pastille de la télé : verte seulement quand une vidéo y passe (choisie ≠ allumée).
- 20 s sans démarrer (DLNA) : « La télé ne démarre pas la lecture. Est-elle allumée ? … » + bouton Annuler (sans rien couper).
- LiveClock : le retard ne grandit qu'une fois la lecture vraiment partie (avant : retard demandé) — fini le « −15 » sans image.
- Fausse télé : FAKE_STANDBY=1 (accepte, reste en transition). Émulateur : message à 20 s, Annuler, retard stable.

## 2026-11-26 — Chaîne qui échoue, vidéo fixe, vignette
- Une chaîne (media.channel) garde l'écran de chaîne, le programme et la section Zapper même si son flux ne répond pas
  (avant : plus « en direct » → écran de vidéo, plus de zapping).
- Téléphone : la vidéo reste en haut de l'écran de lecture, le reste défile dessous.
- MiniPlayer : lecture sur le téléphone, ailleurs que sur l'écran de lecture (accueil, chaînes…) → vignette 192×108 en bas
  à droite (au-dessus de la barre d'onglets) ; un appui ramène à l'écran de lecture. Un seul affichage tient la vidéo.

## 2026-11-26 — Retard du direct à la seconde
- Seuil 1 s (au lieu de 8) : dès 1 s de retard, ou en pause, « −0:01 » et « Revenir au direct ».
- LiveClock : le direct se mesure depuis le vrai départ de la lecture (le temps de chargement ne compte plus ; sinon
  « Revenir au direct » afficherait −0:05). Avant le départ : retard demandé, figé.
- Émulateur (téléphone) : direct → pause 3 s « −0:03 » → reprise stable → Revenir au direct « EN DIRECT ».

## 2026-11-26 — Guide absent chez Guillaume, lueur autour de la vidéo
- Diagnostic (version de diagnostic installée un instant, données lues puis version normale réinstallée) : son téléphone
  n'a que la liste « iptv-org · France », qui ne déclare aucun guide → rien à afficher. « Essayer une liste gratuite »
  (Free-TV) est maintenant aussi dans « Ajouter une liste » tant qu'elle n'est pas importée.
- Journal : « direct : morceau… » toutes les 30 morceaux (le journal du Xiaomi ne garde qu'1–2 min).
- Lueur (mode ambiant) autour de la vidéo du téléphone : PixelCopy d'une vignette 32×18 toutes les 1,5 s pendant la
  lecture, couleur moyenne ravivée (S ×1,6, V 0,45–0,85), halo radial animé (1,4 s) derrière la vidéo.

## 2026-11-26 — Navigateur qui clignotait
- Cause : le flou de la barre d'onglets (Haze, hazeSource sur le contenu) capturait la WebView en continu → la page
  disparaissait une image sur deux. hazeSource retiré sur l'onglet Navigateur (la page ne passe pas sous la barre).
- Émulateur (Wikipédia) : avant, luminosité des captures 205–223 qui alterne ; après, 205,6 stable au repos et au défilement.

## 2026-11-26 — Lueur jusque dans l'en-tête, récentes en mosaïque
- Lueur de la vidéo (téléphone) : couleur partagée (PhoneEngine.glow) et dessinée dans l'éclairage de fond de l'appli
  (CastorApp, sous l'Ambient), centrée sur la vidéo → elle éclaire aussi l'en-tête. Plus dessinée dans PlayerScreen.
- Page Chaînes : « Regardées récemment » en mosaïque (mêmes cases que « Toutes les chaînes »), plus en rangée.
- Non vérifié sur émulateur (arrêté après la surcharge mémoire) ; installé sur le téléphone de Guillaume.

## 2026-11-26 — « Pour commencer » (contenus gratuits et légaux)
- Accueil des nouveaux venus : section « Pour commencer » (ui/Starter.kt) : carte « Chaînes TV gratuites » (un appui importe
  Free-TV et ouvre les chaînes) + 3 sites gratuits et légaux à essayer. Accueil du navigateur : les 3 sites tant qu'aucun site.
- Sites retenus après essai dans le navigateur de l'appli (vidéo détectée) : Internet Archive (films du domaine public,
  monde entier), Arte.tv (Europe, plusieurs langues), NASA+ (monde entier). Icônes : celles publiées par les sites.
- Mosaïque : chaînes du pays du téléphone d'abord (sans recherche ni catégorie). Logo supprimé chez Imgur (redirection
  « removed ») = absent → nom de la chaîne sur sa couleur.
- Émulateur : planté côté GPU, relancé en -gpu swiftshader_indirect.

## 2026-11-26 — Lueur plus réactive
- Lecture de la couleur toutes les 0,4 s (au lieu de 1,5 s), transition 0,45 s (au lieu de 1,4 s) : jusqu'à ~3 s de
  retard avant, < 1 s maintenant. Pixels lus en une fois (getPixels). Copie 32×18 par la carte graphique : coût négligeable.
- Non revérifié à l'écran (navigation de l'émulateur ratée) ; installé sur le téléphone.

## 2026-11-26 — Chaînes gratuites : apprendre à coller le lien
- Carte « Chaînes TV gratuites » : le lien Free-TV affiché (sélectionnable), « Copier le lien » (→ « Lien copié ») et
  « Coller un lien » (ouvre la case habituelle) ; explique que ça marche avec toute liste M3U. Plus d'import en un appui.
- Tuile « Coller un lien » : « Vidéo, direct ou liste de chaînes ». Case : titre « Colle un lien », bouton « Ouvrir ».
- Émulateur (appli remise à zéro) : copier → Coller un lien → suggestion du clavier → valider → page Chaînes ouverte.

## 2026-11-26 — Problème de son après le navigateur
- Rapporté : plus de son correct après une lecture lancée depuis le navigateur ; réglé en relançant l'appli. Journal du
  téléphone trop court pour voir la cause.
- Suspect : le lecteur du site continuait dans la WebView (rien ne le mettait en pause) → deux lecteurs pour le son.
  BrowserController.pauseMedia() (JS : pause de toutes les vidéos/sons, iframes de même origine comprises), appelé par
  Castor.castAt avant chaque envoi (télé ou téléphone).
- PhoneEngine journalise : interruption par le système (focus audio perdu, casque débranché), volume, session audio.
- Non vérifié : l'émulateur plante (GPU) en lançant la vidéo du site.

## 2026-11-26 — Épisode suivant repéré sur la page, fondus
- Cas Guillaume (historique lu via version de diagnostic) : otakubinge « …/anime/<série>/watch », titre sans numéro →
  aucun moyen de déduire la suite par l'adresse.
- Script de page : repère un lien « épisode suivant » (rel=next, ou texte/aria-label « Épisode suivant », « Next episode »,
  « Suivant ›»…, même site). Media.nextUrl / Recent.nextUrl (« next » dans l'historique) ; « Reprendre » s'en sert pour
  un épisode fini ; bouton « Épisode suivant » sur l'écran de lecture (ouvre la page dans le navigateur). Limite : un
  bouton sans lien (site qui change d'épisode en JavaScript) n'est pas vu.
- Test local (page sans numéro, lien « Épisode suivant › ») : bouton affiché, watch2.html ouverte.
- Fondu court (18 dp) en haut des zones qui défilent : accueil, écran de lecture (sous l'en-tête ou la vidéo).
- Fondus retirés à la demande de Guillaume (« c'était plus propre avant ») : ui/Fade.kt supprimé, accueil et écran de
  lecture comme avant. En-tête inchangé (il ne se cache pas au défilement).
- Navigateur : la vignette de la vidéo en cours se tient au-dessus du bouton « N vidéos » (+72 dp), qu'elle cachait
  (bouton qui lance l'épisode suivant). Non revérifié à l'écran.
- Vignette remontée seulement quand le bouton « N vidéos » est affiché (Nav.browserVideoButton).

## 2026-11-26 — Parcours de départ, fausse « page vide »
- Internet Archive signalé « page vide » à tort : page faite de composants web (shadow DOM), invisibles pour innerText et
  querySelectorAll. Le contrôle compte maintenant tout élément à shadowRoot comme du contenu. Vérifié sur l'émulateur.
- Carte « Chaînes TV gratuites » repliée par défaut (une ligne), dépliée d'un appui.
- Son « Coller un lien » : remonte jusqu'à la tuile « Coller un lien » et l'entoure d'un contour ambré qui pulse ~3 s
  (on apprend où elle est). Vérifié sur l'émulateur (appli remise à zéro).

## 2026-11-26 — Arte : plantage et « 20 milliards de vidéos »
- Plantage (journal du téléphone, 02:51) : IllegalArgumentException « Key … » = même adresse deux fois dans la liste
  « Vidéos de la page » (clé de liste dupliquée). Ajout à la liste synchronisé et sans doublon, au plus 15 ; clé de liste unique.
- Fausses vidéos : « .m3u8 » cherché dans toute l'adresse (paramètres des mesures d'audience d'Arte inclus) → seulement
  un chemin qui finit par .m3u8.
- Liste maîtresse : ses pistes #EXT-X-MEDIA (aud_VF, aud_VO…), les éléments du même dossier, et les fichiers à son nom
  (« 127960-000-A_v1080… ») sont masqués ; la vidéo « Auto · N qualités » passe en tête.
- Émulateur, Arte « Flow » : avant 7 entrées (son seul, morceaux) ; après « 1 vidéo » = 127960-000-A.m3u8, 1 h 21, 6 qualités.
  Accueil Arte : plus de fausses vidéos. Aucun plantage.

## 2026-11-26 — En-tête qui se cache, lueur de « Coller un lien »
- En-tête de l'appli (accueil, lecture) : se cache en défilant vers le bas (sa hauteur suit le doigt, il s'efface) et
  revient dès qu'on remonte ; réaffiché en changeant d'onglet. NestedScrollConnection sur la zone de contenu.
- Surbrillance de la tuile « Coller un lien » : halo radial ambré qui déborde et respire (650 ms), ombre lumineuse ambrée,
  fond légèrement ambré, fin liseré. Vérifié sur l'émulateur (deux phases de la pulsation).

## 2026-11-26 — Chaînes gratuites : iptv-org par pays ; ondes ; +10 s
- Free-TV testée depuis le réseau de Guillaume : France 10/33 chaînes répondent (403, serveurs morts, pages YouTube/sites).
  iptv-org France : ~170/231. « Chaînes TV gratuites » et « Essayer une liste gratuite » proposent maintenant
  iptv-org/countries/<pays du téléphone>.m3u (gb → uk ; sans pays : languages/<langue>.m3u). Pas de guide des programmes
  dans ces listes (Free-TV en déclarait).
- Ondes de diffusion (CastWaves) remises sur l'en-tête de chaîne pendant la lecture sur la télé (perdues avec les logos).
- +10 s au direct : actif seulement à partir d'1 s de retard (avant : > 0, d'où le bouton actif et clignotant au direct).

## 2026-11-26 (nuit) — Programmes pour toutes les chaînes ; découverte pas à pas
- Sources gratuites vérifiées : les guides d'iptv-org/epg (GUIDES.md) sont tous hors service ; epgshare01 (déclaré par
  Free-TV, liste open source) couvre 83/231 chaînes iptv-org France, epg.pw 76/231. Repli retenu : epgshare01 du pays de
  la chaîne (Epg.fallbackGuide), utilisé seulement si aucun guide déclaré ne la connaît (12 h de cache, 1 h si absent).
- Émulateur, appli neuve + iptv-org France seule : France 3 → « En ce moment » + « Ensuite · 03:15 … » (guide FR en 2,2 s).
- Accueil habitué : « Pour commencer » garde ce qui n'a pas été essayé (liste de chaînes si aucune, sites si aucun site).

## 2026-11-26 (nuit) — Économie d'énergie, 23 langues
- data/Power.kt : économie d'énergie = réglage (Réglages) OU économie d'énergie du téléphone. Coupe : lueur de la vidéo
  (plus de copie d'image), flou Haze (barre d'onglets, en-tête Chaînes → fond plein), animations décoratives
  (animationsOff), suivi du direct 2 s au lieu d'1 s. Vidéo inchangée.
- Traductions : tools/i18n/<qualificatif>.json (source.json = référence en/fr), lus par tools/strings.py ; 16 langues
  ajoutées par 4 traducteurs en parallèle (ja, ko, zh-rCN, zh-rTW, ru, uk, pl, cs, ar, tr, hi, in, sv, ro, vi, th), toutes
  vérifiées (clés, placeholders, pluriels CLDR). localeFilters étendu (sinon écartées !), generateLocaleConfig +
  res/resources.properties : langue choisie par appli dans Android 13+. Vérifié : japonais, arabe (miroir RTL).
- Nouveaux textes à traduire désormais aussi dans tools/i18n (sinon repli sur l'anglais).
- Arte (émulateur, journal « vidéos proposées ») : épisode de série, 3 films, film en allemand, concert → 1 seule vidéo
  (liste maîtresse) à chaque fois, aucun plantage. NASA+ et Internet Archive : 1 vidéo (tests précédents).

## 2026-11-26 (nuit) — Rangement du code
- Code mort retiré : IptvStore.locate, LiveBuffer.nextSeq, PlayerScreen.Volume/ModeCard, CastorApp.Hidden,
  Castor.clockText ; 22 textes inutilisés retirés (source + 16 traductions).
- Compilateur : 0 avertissement (Locale.forLanguageTag/Builder, resumeWith, presse-papiers Android, @param:StringRes).
- Lint : 0 erreur (LocalActivity, StateFlow lu via collectAsState, BOM littéral → ﻿), pluriels « many » fr/es/it/pt,
  états primitifs (mutableIntStateOf…), offset lambda (pas de recomposition à chaque image de l'indicateur d'onglet).
- Test général émulateur : accueil, Chaînes, lecture téléphone + programme, plein écran, navigateur, 0 plantage.

## 2026-11-26 (nuit) — Télé éteinte : pas « connectée », lecture sur le téléphone
- Mesuré (lecture seule) : Philips éteinte = répond en HTTP (description 200), mais PAS à la recherche SSDP (la box répond).
- Renderer.awake : vrai seulement si l'appareil a répondu à la recherche (M-SEARCH) ou s'annonce (NOTIFY alive).
  Choix automatique de la télé seulement parmi les allumées ; la télé en cours est lâchée si elle ne répond plus (sauf
  lecture en cours ou choisie à la main). Liste : « Ne répond pas : éteinte ? ». Nouvelle recherche au retour dans l'appli.
- Vidéo/chaîne ouverte sans télé allumée → lecture directe sur le téléphone à la fin de la recherche (pendingAuto).
- Émulateur (fausse télé = comme en veille) : pastille « Choisir une télé », chaîne lancée sur le téléphone, 0 envoi.
- À vérifier télé allumée : la Philips doit répondre à la recherche (sinon elle serait vue « éteinte ? »).
- Épisodes : « prisonniere-951-1-4 » lu comme épisode 951 (le « e » final de « prisonniere » pris pour « e951 ») →
  le mot doit commencer là ; « -1-4/ » et « (1/4) » = partie 1 sur 4 (dernière partie : pas de suite). Le lien
  « épisode suivant » repéré sur la page passe avant celui déduit de l'adresse. Tests ajoutés.
- Arte « 5 vidéos » reproduit (accueil → « Reprendre la lecture ») : les pistes (aud_VF, aud_VO…) arrivaient avant la liste
  maîtresse → 1, 2, … 5 puis retour à 1. Désormais une piste au nom d'une liste HLS déjà vue n'est jamais ajoutée, et la
  liste maîtresse qui arrive après retire ses pistes aussitôt. Journal : « vidéos proposées : 1 » du début à la fin.
- Noms : titre de la page (sans « – Regarder le film complet ») au lieu d'un nom de fichier technique.
- Sites gratuits suggérés (accueil et accueil du navigateur) tant qu'il y a moins de 6 sites, sans ceux déjà dans « Mes sites ».

## 2026-11-26 (nuit) — Langues et sous-titres de la vidéo
- Hls.tracks : pistes son (#EXT-X-MEDIA AUDIO : nom, langue, défaut) et sous-titres en un seul WebVTT (Arte), hors « forcés ».
- Carte « Langue » sur l'écran de lecture (≥ 2 pistes). Téléphone : TrackSelectionOverride (nom puis langue), réappliqué
  quand les pistes arrivent. Télé : le relais ne garde que la piste choisie dans la liste maîtresse servie (audioChoice) ;
  changement → renvoi au même endroit. Non vérifié sur la vraie télé (la fausse reçoit le flux continu, sans ce choix).
- Sous-titres de la vidéo ajoutés à la carte « Sous-titres » (français, allemand, sourds et malentendants…).
- Bugs trouvés en route : setPageImage écrasait la vidéo par une copie ancienne (sous-titres et titre perdus) → mise à
  jour sur l'état courant ; identify() ne cherche plus de film pour un nom technique (« 130430-001-A ») ; titre affiché
  sans « – Regarder la série » (PageTitle.display).
- Émulateur, Arte « Prisonnière 951 (1/4) » sur téléphone : 6 langues, VO appliquée, sous-titres français affichés.
- Internet Archive (Chaplin) : 1 vidéo (1 h 17), lecture sur téléphone OK ; titre sans « : Free Download, Borrow… ».
  Nom de fichier à tirets bas = technique → titre de la page. NASA+ : 1 vidéo (test précédent) ; non relu cette nuit,
  l'émulateur plante (GPU) dès qu'une vidéo tourne longtemps.

## 2026-11-26 (matin) — Castor.kt découpé (sans changement de comportement)
- Castor.kt (1 757 lignes) → 1 319 lignes + fichiers par sujet, dans `cast/` :
  `CastState.kt` (état, phases, erreurs), `Album.kt` (photos, diaporama, vidéos de l'album),
  `LiveClock.kt` (retard sur le direct), `MediaProbe.kt` (HLS ? sous-titres MKV ?), `TvSteps.kt` (gestes DLNA prudents).
- Castor garde la façade publique (openPhotos, photoAt…) ; Album utilise les membres `internal` de Castor.
- Vérifié : compilation sans avertissement, tests unitaires, lint 0 erreur ; émulateur + fausse télé : vidéo envoyée et lue,
  album 2 photos (flux diaporama), photo suivante, diaporama, arrêt.

## 2026-11-26 (soir) — Logos gardés, programmes recalés, téléphone direct sans télé
- Logos et images : gardés sur le téléphone (cache/images, WebP, 30 Mo au plus, ménage au démarrage), réduits
  à la taille utile (logos 256 px) ; en mémoire, cache limité (20 Mo) au lieu d'une liste sans fin de bitmaps 400 px.
  Logo déjà prêt : affiché tout de suite (plus de nom puis logo à chaque retour sur l'écran).
- Programmes faux : le guide gratuit France (epgshare01) mélange plusieurs sources ; France Télévisions y est
  décalée d'1 h (« Journal 20h00 » annoncé à 19 h). Epg mesure le décalage de chaque source d'après les heures
  écrites dans les titres (« Le 6h info »…), à l'heure du pays de la chaîne, et la recale (majorité nette, ≥ 8 titres).
  Piège : un fuseau ICU inconnu de java.util donnait GMT (−2 h mesuré) → fuseau du téléphone s'il est du pays.
  « En ce moment » = programme commencé le plus récemment ; « Ensuite » = premier qui commence après maintenant
  (sources qui se chevauchent). Affichage remis à zéro à chaque changement de chaîne. Cache du guide : .v4.tsv.
- Écran de lecture : sans télé allumée, plus de « Choisir une télé » : la vidéo part sur le téléphone (vidéo
  ouverte « pour la télé » comprise) ; « Regarder sur la télé » caché sans télé. Lecture auto sur le téléphone
  faute de télé : ne compte pas comme un choix (la question revient quand une télé est là).
- Vérifié émulateur (sans télé) : 6ter lue direct sur le téléphone, « The Rookie » = l'image, « Ensuite 23:50 » ;
  journal « source www.francetv.fr décalée de -1 h : corrigée ». Tests EpgShiftTest/EpgPickTest, lint 0 erreur.

## 2026-11-26 (nuit) — Suggestions gratuites qu'on peut écarter
- « Pour commencer » (accueil et navigateur) : appui long sur un site suggéré ou sur la carte des chaînes gratuites →
  « Ne plus proposer » (aussi action d'accessibilité). Gardé dans Library (`starters_dismissed`), même liste pour
  l'accueil et le navigateur.
- Texte « Appui long sur une carte pour la retirer ou la garder en favori » supprimé (mal placé, sans effet sur
  les suggestions) ; clé remplacée par `start_dismiss` dans les 23 langues.
- Vérifié émulateur : NASA+ écarté → disparu de l'accueil et du navigateur, et le reste après redémarrage.

## 2026-11-27 (nuit) — Sites libres et ouverts : tests et « universel »
Vision de Guillaume : ne suggérer que du gratuit ET libre/ouvert (domaine public, PeerTube, Blender, Wikimedia, télé
publique) ; pas de services gratuits avec pub (Pluto, Tubi, Plex). Quelques suggestions, jamais un catalogue.
- Suggestions « Pour commencer » (rangée qui défile) : Internet Archive, Arte, PeerTube (Sepia Search), Blender Studio
  (video.blender.org, films libres), Wikimedia Commons (vidéos de qualité), NASA+. Descriptions dans les 23 langues.
- Testé sur émulateur (détection, nom, lecture téléphone ; envoi fausse télé pour HLS PeerTube et WebM Commons) :
  Blender ✓, Sepia → PeerTube ✓, Commons ✓ (1080p, sous-titres), Internet Archive ✓, NASA+ ✓, Arte ✓ (Flow),
  Vimeo ✓, Dailymotion ✓ (après « Continuer sans accepter »). Library of Congress : vérification anti-robot
  (Cloudflare), non testée. Europeana : vidéo intégrée Vimeo derrière un consentement, non chargée.
- Corrections générales (tous sites) :
  - liens « nouvel onglet » (target=_blank) : s'ouvrent ici après un vrai toucher (avant : rien) ;
  - qualités d'une même vidéo regroupées (stemOf / fileScore : ≤ 1080p, MP4 d'abord), et montée auto en 1080p/720p
    quand le nom de fichier porte sa qualité (« .480p. ») ;
  - ignorés : manifestes DASH (.mpd), morceaux DASH/MSE (init-…, chunk-…), « -fragmented.mp4 » de PeerTube,
    la page elle-même, le « #… » final des adresses ; même liste HLS sur un autre serveur du site = une seule ;
  - og:video / twitter:player:stream lus : vidéo proposée avant même la lecture (Internet Archive) ;
  - og:site_name retiré des titres (« – Maternelle Éducatif ») ; titre suivi s'il arrive après la vidéo ;
    nom de fichier = identifiant de la page (« xjfs17.m3u8 ») → titre de la page ; « File:… .webm » nettoyé ;
  - sous-titres <track> de la page proposés avec la vidéo (langue du téléphone d'abord) : Commons ✓ ;
  - relais : HLS en plages d'octets (EXT-X-BYTERANGE, EXT-X-MAP BYTERANGE) → PeerTube part sur la télé
    (avant : rien n'arrivait) ;
  - « Mes sites » : nom court sans sous-domaine générique (video.blender.org → Blender).
- Émulateur : -gpu host plante avec la vidéo ; swiftshader_indirect -memory 4096 tient (plante parfois en lecture longue).
- Suite : Blender pointe sur la chaîne triée par vues (les films d'abord) ; Wikimedia sur sa recherche vidéo (grille)
  au lieu des pages de texte « Featured » ; Ogg Theora (.ogv) et versions archivées (« 20120806…! ») ignorés.
- Navigation interne d'un site (PeerTube, sans rechargement) : la vidéo d'avant est oubliée, la nouvelle proposée seule ✓.
- Faux problème écarté : clavier qui revenait après le chargement = artefact des touches simulées par adb (mode non
  tactile) ; avec le clavier à l'écran, rien.

## 2026-11-27 (nuit, suite) — Télé en veille, double appui, lecteur automatique, épisode suivant en arrière-plan
- Télé éteinte proposée : la Philips en veille continue d'annoncer « urn:schemas-tpv-org:service:PhotoFrame:1 »
  (NOTIFY ssdp:alive) sans répondre à aucune recherche (MediaRenderer, AVTransport, ssdp:all). Désormais seuls les
  messages SSDP qui parlent de lecture (MediaRenderer, AVTransport, RenderingControl) la marquent allumée
  (Dlna.isRendererMessage, AwakeTest). À vérifier télé allumée : elle doit répondre à la recherche.
- Double appui ±10 s : vague en arc du côté touché, onde depuis le doigt, trois flèches qui s'allument, « 10 secondes »
  (23 langues) ; les appuis suivants s'ajoutent (« 20 secondes »…).
- Navigateur : une vidéo lancée par l'utilisateur dans la page (vrai geste, son non coupé, > 1 min) → l'appli passe
  seule à son lecteur, une seule fois par page (revenu sur la page, on peut y regarder sans être renvoyé : pas de
  boucle). Attend 1 s que la meilleure version arrive ; « -480.mp4 » (PeerTube) monte en 720p.
- « Épisode suivant » dans le lecteur : la page est chargée en arrière-plan (lecture lancée son coupé si besoin),
  sa vidéo part toute seule ; le bouton affiche « Recherche de l'épisode suivant… » ; 25 s sans vidéo → la page
  s'affiche. Arte : le n° de partie est aussi dans l'identifiant (130430-001-A → 130430-002-A).
- Plantage corrigé : « handler » dans le bloc de la WebView = View.getHandler(), nul quand la page n'est pas à
  l'écran → champ renommé mainHandler.
- Vérifié émulateur : Blender (lecture dans la page → lecteur, pas de boucle), Arte 1/4 → 2/4 → 3/4 sans quitter
  la lecture, animation ±10 s / cumul 20 s.

## 2026-11-27 — Version libre (F-Droid), « En ce moment », grille d'une chaîne
- Licence : exception GPL article 7 pour Google Play Services (README « Licence »). Deux versions du même dépôt
  (product flavors) : `play` (inchangée, DLNA + Google Cast) et `libre` (aucune bibliothèque non libre, DLNA seul ;
  `app/src/libre/…/CastControl.kt` vide). Commandes : `assemblePlayRelease`, `assembleLibreRelease`.
- Chaînes : pilule « En ce moment » en tête des catégories (se combine avec elles et la recherche) : logo, programme
  en cours, progression, la suite ; appui = regarder ; icône à droite (ou appui long) = grille de la chaîne.
  Au plus 3 pays de guides chargés.
- Lecteur : un appui sur le programme en cours ouvre la grille de la chaîne (d'hier soir à demain, jours titrés,
  passé estompé, en cours mis en avant). Les blocs « soirée » qui recouvrent leurs émissions sont retirés (Epg.withoutBlocks).
- Corrigé (retour de Guillaume) : sans télé, chaque changement de chaîne relançait une recherche des télés (~5 s)
  avant de repartir sur le téléphone. Désormais : déjà sur le téléphone → on zappe sur place ; aucune télé
  choisie → téléphone tout de suite (la recherche tourne en fond, une télé trouvée se propose par le bouton).
  Vérifié émulateur : 13e Rue → AB1 → Arte en ~3 s chacune (Arte : 403 de la chaîne, sans rapport).
- Lecteur : coins arrondis retirés autour du programme en cours (ils rognaient le texte).
- Erreur d'une chaîne : bouton « Masquer cette chaîne » dans le message (puis « Chaîne masquée · Annuler ») ;
  la phrase « Appui long dans la liste pour la masquer » retirée du message (23 langues). Vérifié émulateur (Arte, 403).
- Bug LCI (téléphone de Guillaume) : « LCI HD » (151.80.18.177:86) ne répond plus ; pendant les 15 s d'attente,
  l'ancienne chaîne continuait sous le nom de la nouvelle avec une barre « 0:25 / 0:25 » (une « vidéo de 9 s »),
  puis « Problème de réseau ». Corrigé : sur le téléphone l'ancienne lecture s'arrête dès le changement (et ses
  derniers états sont ignorés pendant le chargement) ; liste HLS : 6 s pour se connecter au lieu de 15 ; serveur
  muet avec Wi-Fi présent → « La vidéo ne répond pas… » + « Masquer cette chaîne ». Vérifié émulateur.
- Bug CNews (lien Dailymotion, son à part de l'image, fMP4) : chargement sans fin et sans erreur sur le téléphone.
  L'enregistrement du direct (pause) ne prend que l'image ; le lecteur attendait le son. Désormais, son séparé →
  le lecteur d'Android lit la liste lui-même (pas de remontée longue pour ces chaînes-là). Vérifié émulateur :
  image + son AAC en ~2 s. À faire un jour : enregistrer aussi le son séparé pour garder la pause longue.
- Boutons ±10 s animés (page de lecture et plein écran) : le bouton s'enfonce et rebondit, la flèche pivote, une onde
  part du bouton, « +10 » / « −10 » monte en s'effaçant ; appuis rapides cumulés (« −20 »). Pas coupés par
  l'économie d'énergie (moins d'une seconde, sur appui).
- Position après ±10 s : plus d'allers-retours (50:33 → 50:43 → 50:33). Le moment visé reste affiché tant que le
  lecteur (téléphone, télé, Cast) ne l'a pas rejoint, 6 s au plus (Castor.shownPosition).
- Mini-vidéo (lecture sur le téléphone, hors écran de lecture) : se déplace du doigt et se range dans le coin le plus
  proche (avec l'élan), coin gardé d'un écran à l'autre ; jetée hors de l'écran par un côté (plus de la moitié
  dehors, ou lancée vite) → s'efface et la lecture s'arrête. Action d'accessibilité « Arrêter ». Vérifié émulateur.

## 2026-11-27 — Lancement automatique, double appui, sous-titres en un appui
**Fait**
- Internet Archive (Chaplin) : la vidéo lancée dans la page ne passait pas au lecteur. Son lecteur est rangé dans
  un « shadow DOM », d'où l'événement de lecture ne remonte pas : le script écoute maintenant chaque racine cachée
  (`attachShadow` enveloppé). Vérifié sur émulateur, aussi après une chaîne (CNews).
- Page déjà envoyée une fois : elle repart seule au lecteur si autre chose a joué depuis (une chaîne…). On ne
  s'abstient que si sa vidéo est encore dans le lecteur (pas de boucle).
- Lien de page web reçu d'une autre appli (Partager, Ouvrir avec) : ouvert dans le navigateur de l'appli (type
  `text/html` vérifié) au lieu d'être lu comme une vidéo.
- Double appui en plein écran : même animation que les boutons ±10 s (`SkipBurst`, mouvements mis en commun avec
  `AnimatedSkip`), à la place des trois flèches.
- Sous-titres : un appui ouvre la liste directement ; appui long : « Ajouter un fichier » (aussi dans la liste
  quand il n'y a rien à choisir). Sous-titre choisi : petit bouton « Décalage » qui déplie le réglage.

**Pas fait**
- Le premier cas signalé (vidéo d'un site de streaming non lancée seule après une chaîne) n'a pas été reproduit
  tel quel : site inconnu. Les deux causes probables sont corrigées ; à revoir si ça recommence (nom du site).
- Choix d'un sous-titre réel (Arte) non refait sur émulateur après la refonte.

## 2026-11-27 — Partager l'appli
**Fait**
- Réglages : ligne « Partager l'appli » (feuille de partage d'Android, phrase + lien), en 23 langues. Lien par
  variante : fiche Play Store (`play`), dépôt GitHub (`libre`) via `BuildConfig.SHARE_URL`.
- Standard commun à toutes les applis : `~/Dev/Gestion de projet/standard-incontournables.md` (partager, signaler, signature), cité dans `~/Dev/CLAUDE.md`.

**Pas fait**
- Le lien Play Store ne mène nulle part tant que l'appli n'est pas publiée.
- Les autres applis et jeux n'ont pas encore le bouton (à faire dans chacune, par sa session).

## 2026-11-27 — Reprendre en un appui, widget
**Fait**
- « Reprendre » sur l'accueil : la page de l'épisode est rouverte en arrière-plan (même mécanisme que l'épisode
  suivant), sa vidéo repérée et lancée là où on s'était arrêté, sans afficher le site. Un rond de chargement tourne
  sur la carte ; si la vidéo reste introuvable (25 s), le navigateur montre la page. Vérifié sur émulateur (~2 s).
- Widget d'écran d'accueil (`ui/HomeWidget.kt`, RemoteViews, sans dépendance) : la vidéo à reprendre (affiche,
  titre, épisode, avancement, bouton lecture) et les 4 dernières chaînes avec leur logo. Redessiné quand
  l'historique change (au plus toutes les 2 s). Appuis vérifiés sur émulateur (reprise Chaplin, CNews).
- Signalement : sujet `[Bug] Universal TV Cast <version>` fixe ; sans appli de mail, l'adresse est copiée.
- Standard des 3 incontournables (`~/Dev/Gestion de projet/standard-incontournables.md`) appliqué à 7 autres
  projets (commits locaux, non poussés).

**Pas fait**
- Aperçu du widget dans la liste des widgets : simple (logo blanc), pas une vraie capture.

## 2026-11-27 — Famille de widgets
**Fait** (`ui/Widgets.kt`, un fichier pour tous ; `WidgetKit` dessine, `WidgetListService` remplit les listes)
- « Reprendre + chaînes » (4×2, le premier), « Reprendre + sites » (4×2, sites favoris avec leur icône),
  « Mes vidéos en cours » (3×3, cartes à faire glisser : StackView), « Mes chaînes » (4×1, grille qui grandit avec
  le widget), « Reprendre » (2×2, affiche en grand).
- Aperçus soignés dans la liste des widgets (affiche imaginaire en dégradé, chaînes en couleurs, textes traduits).
- Vérifié sur émulateur : chaque widget affiché avec les vraies données sauf le petit « Reprendre » (même carte que
  « Mes vidéos en cours », vérifiée) ; glisser une carte puis l'ouvrir relance la vidéo ; sites et chaînes s'ouvrent.

**Pas fait**
- Le petit « Reprendre » n'a pas été posé sur l'écran de l'émulateur (seulement son aperçu).

## 2026-11-27 — Widget Photos et vidéos ; « Mes vidéos en cours » en grille
**Fait**
- Widget « Photos et vidéos » (4×1) : « Album » ouvre tout de suite le sélecteur de photos d'Android (diaporama),
  « Vidéos » celui des vidéos du téléphone (`Nav.pick`). Vérifié sur émulateur.
- « Mes vidéos en cours » : les cartes à faire glisser (StackView) ne glissaient pas sur le téléphone de Guillaume ;
  remplacées par une grille de petites affiches (titre, avancement), qui défile. Vérifié sur émulateur : défilement,
  appui → l'épisode Arte repart où on en était.

**Pas fait**
- Le widget à cartes déjà posé sur un téléphone se transforme seul en grille (même widget) : à vérifier sur le sien.

## 2026-11-27 — Sites proposés selon le pays
**Fait**
- `ui/Starter.kt` : chaque site proposé peut avoir une liste de pays (`countries`). Le pays vient, sans autorisation,
  de la SIM, du réseau mobile et des réglages de langue (un des trois suffit). La télé du pays passe en tête.
- Ajoutés : ARD Mediathek (DE, AT, CH, LU, LI), ZDF (DE, AT, CH), RTVE Play (ES, AD), DW en direct (partout).
  Arte réservée à l'Europe (35 pays).
- Essais dans l'appli sur émulateur (en France) : ARD ✅ (vidéo trouvée et lue en 2 s ; certaines avant-premières
  demandent un compte) ; ZDF ✅ détection (bande-annonce), mais la plupart des émissions sont bloquées hors
  DE/AT/CH, d'où la restriction ; RTVE ✅ direct 24h, replays bloqués hors d'Espagne ; DW ✅ direct, partout.

**Pas fait**
- Non retenus faute d'essai concluant : RTP Play (PT, bandeau cookies), ONF/NFB (CA, bandeau sans refus possible),
  ORF ON (AT, l'émulateur s'est arrêté pendant l'essai), SRF Play (CH), TV5MONDEplus (refuse les robots).
  À essayer : NHK World (JP), ABC iview (AU, bloqué hors Australie), CBC Gem (CA), Rai Play (IT), NPO (NL),
  VRT MAX / Auvio (BE), Globoplay (BR, payant en grande partie), PBS (US).
- Pas revu à l'écran après ce changement (émulateur arrêté).

## 2026-11-27 — « Reprendre » : une carte par série
**Fait**
- Cause des 3 cartes Arte : la clé de série gardait l'identifiant du programme (« 130430-003-A »), qui change à
  chaque partie. `Episodes.seriesKey` le neutralise désormais (test `arteMemeSerie`).
- Filet de sécurité : `Recent.sameSeriesAs` (même site, même nom de série, épisode connu des deux côtés), utilisé à
  l'ajout dans l'historique et à l'affichage de « Reprendre » (une carte par série, l'épisode le plus récent). Les
  doublons déjà enregistrés se regroupent à l'affichage.
- Épisode fini : « Reprendre » propose le suivant, depuis le début (inchangé). Suite introuvable : l'épisode reste
  au lieu de disparaître ; appui long → « Série terminée » (23 langues) pour le retirer.

## 2026-11-27 — Reconnaître plus de vidéos
**Fait**
- Listes HLS chargées en coulisse sans « .m3u8 » dans l'adresse (« /manifest », « /playlist?id=… ») : le script
  enveloppe `fetch` et `XMLHttpRequest` et signale toute réponse qui commence par `#EXTM3U` ou dont le type est
  « mpegurl » (`CastorBridge.hls`). Vérifié sur émulateur avec une page de test locale (fetch de /manifest) :
  repérée (10 min, 5 qualités, 1080p) et lue.
- Requêtes des « service workers » des sites : inspectées (vidéos) et filtrées (pubs) comme les autres
  (`ServiceWorkerControllerCompat`). Pas de site d'essai sous la main : non vérifié en vrai.

**Pas fait / limites**
- DASH (.mpd) seul, sans HLS : ni les télés DLNA ni le relais ne le gèrent ; le téléphone le pourrait (module DASH
  d'ExoPlayer) si un site utile n'a que ça.
- Vidéos protégées (DRM : Netflix, Disney+, une partie de Canal+…) et lecteurs à morceaux sans liste (YouTube) :
  impossible, voulu par les sites.

## 2026-11-27 — Jamais bloqué : vidéo pas repérée
**Fait**
- Vidéo lancée dans la page et rien de repéré au bout de 15 s (8 s avant ; ZDF met parfois 16 s) : message
  « Vidéo pas encore repérée » avec « Chercher encore » (l'appli démarre elle-même le lecteur du site, son coupé)
  et « Signaler ce site » (mail `[Site] <site>` avec l'adresse de la page, pour le rendre compatible).
  Vérifié sur émulateur avec une page piège (vidéo en blob) : le message apparaît, « Signaler » ouvre le mail.
- Vidéo protégée (DRM) : repérée par `requestMediaKeySystemAccess` ; le message dit franchement qu'elle ne se
  regarde que dans le site ou son appli (pas de « Chercher encore » inutile).
- Appui long dans le navigateur : sur un lien → « Lancer la vidéo de ce lien » (ouverte en coulisse, comme
  l'épisode suivant) ; ailleurs (lecteur, image) → « Trouver la vidéo de cette page ». Texte et champs gardent leur
  appui long (sélection, coller).
- Les 3 incontournables : 7 projets poussés ; règle ajoutée à l'Atelier (AGENTS.md, étape 3 de la publication),
  enregistrée mais pas poussée (dépôt Atelier hors de l'accord donné).

**Pas fait**
- Appui long et « Chercher encore » pas vus à l'écran : l'émulateur a été fermé de l'extérieur trois fois
  (une autre session ?). « DRM » non vérifié sur un vrai site.

## 2026-11-27 — Appui long moins envahissant
**Fait**
- Retour de Guillaume : l'appui long empêchait de sélectionner du texte. Il n'agit plus que sur un lien, et seulement
  si la page n'a encore aucune vidéo repérée (« Lancer la vidéo de ce lien »). Ailleurs, appui long d'origine.
  « Trouver la vidéo » reste dans le message « Vidéo pas encore repérée ».

## 2026-11-27 — Appui long sur une vidéo ; essais du logo de chaîne
**Fait**
- Appui long sur une vidéo de la page (élément <video>, ou ce qui la recouvre) : « Lancer cette vidéo » (celle déjà
  repérée part tout de suite, sinon recherche). Signalé par la page elle-même (événement `contextmenu`), donc le
  texte garde sa sélection. Vérifié sur émulateur (page de test : menu ; ARD avant lecture : sélection du texte).
- Widget : tuile claire derrière les logos en partie noirs (`Logos.darkShare` > 18 %), pour France 5 et pareils.
  Pas revu à l'écran.
- Logo de chaîne dans le lecteur : 4 présentations à l'essai (`LogoStyle` : tuile, détouré, liseré blanc, verre
  dépoli), réglables depuis l'ordinateur (`--es castor_logo_style STICKER`). Planche : `docs/design/logos-variantes.png`.
  Par défaut : la tuile, en attendant le choix de Guillaume.

## 2026-11-27 — Logo de chaîne : liseré adaptatif, halo doux
**Fait**
- Liseré (option C) adaptatif : blanc autour d'un logo sombre, gris très foncé autour d'un logo surtout blanc
  (`Logos.edgeColor`, part de pixels très clairs > 50 %). Nouvelle option E « halo doux » (`LogoStyle.GLOW`) : masque
  du logo flouté, même couleur adaptative. B (détouré seul) et D (verre dépoli) écartés par Guillaume.
- Planche `docs/design/logos-variantes-2.png` : A, C, E sur France 5, Antenne Réunion, Novo 19, Paris Première, 6ter,
  BFM TV, BFM Alsace (portrait et plein écran). Les logos qui ont leur propre fond (BFM TV, Antenne Réunion,
  Paris Première en partie) restent des blocs dans toutes les options.
- Par défaut : la tuile (A), en attendant le choix.

## 2026-11-27 — Logo de chaîne : on garde la tuile
**Décision de Guillaume** : la tuile (bloc coloré) reste partout ; plus professionnelle, elle met le logo en valeur.
Liseré et halo « font bidouille ». Code d'essai retiré (`LogoStyle`, liseré, halo, réglage caché) ; les planches
restent dans `docs/design/` pour mémoire. Gardé : tuile claire du widget pour les logos sombres (`Logos.darkShare`).

## 2026-11-27 — Nettoyage du code
**Fait** (aucun changement de comportement)
- Navigateur séparé en deux : logique `browser/BrowserController.kt` (≈ 500 lignes) et écran `browser/BrowserScreen.kt`
  (≈ 530 lignes), au lieu d'un fichier de 1 000 lignes.
- `cast/Castor.kt` : vérification de la source sortie dans `cast/SourceCheck.kt` ; repères de sections ajoutés
  (sous-titres, déplacement et direct, langue) ; commentaire « Arrêter » remis au-dessus de `stop()`. Le cœur
  (télé ou téléphone, envoi) reste groupé : trop imbriqué pour être coupé sans risque.
- Recherche de code mort : aucune fonction inutilisée (hors tests et rappels d'Android). Analyse Android :
  0 erreur (traductions d'ARD, ZDF, RTVE ajoutées dans 15 langues ; aperçu de widget aplati ; fuites de contexte
  documentées : ce sont des contextes d'application).
- README réécrit (fonctions actuelles, 23 langues, Google Cast, carte du code « Où est quoi »).
- Planches de design sorties du dépôt : `~/Dev/Gestion de projet/Universal TV Cast/design/` (les mentions
  `docs/design/` plus haut désignent désormais ce dossier).
- Essai sur émulateur après coup : vidéo Archive repérée et reprise, chaîne France 5 lue.

**Pas fait**
- Suggestions de style de l'analyse (UseKtx…) et mises à jour de bibliothèques : sans effet, ou à faire à part.

## 2026-11-27 — Fin du nom « Castor » dans le code
**Fait**
- Paquet du code `studio.rollando.castor` → `studio.rollando.tvcast` (dossiers main, play, libre, test) ;
  `Castor` → `TvCast` (objet central, `TvCastApp`, `TvCastApplication`, `TvCastTheme`, `Theme.TvCast`, pont
  JavaScript `TvCastBridge`) ; étiquettes du journal Android `TvCast…` ; verrous et fils `tvcast-…`.
- Gardé exprès : l'**identifiant de l'appli** `studio.rollando.castor` (applicationId). Le changer en ferait une
  autre appli pour Android et le Play Store : désinstallation, historique perdu, nouvelle fiche. Invisible pour
  les utilisateurs. Les fichiers de données (library, iptv, epg…) n'avaient pas le nom : rien à migrer.
- Vérifié sur émulateur (installation par-dessus l'ancienne : historique gardé, vidéo repérée et reprise).
- Note : les journaux plus haut citent encore `Castor.kt`, `CastorApp.kt`, `castor/…` : ce sont désormais
  `TvCast.kt`, `TvCastApp.kt`, `tvcast/…`.

## 2026-11-27 — Identifiant de l'appli : com.rollandostudio.universaltvcast
**Fait**
- applicationId et namespace `studio.rollando.castor` → `com.rollandostudio.universaltvcast`, selon la convention du
  studio (`~/Administratif/Rollando Studio/play-console/convention-identifiants.md`, ligne ajoutée). Code rangé sous
  le même nom : `app/src/*/java/com/rollandostudio/universaltvcast/` (au lieu de `studio/rollando/tvcast`).
  Lien de partage (Play Store) mis à jour. Possible sans dégât car rien n'est publié.
- Vérifié sur émulateur : installation neuve, vidéo repérée et lue.
- Pour Android c'est une nouvelle appli : sur le téléphone de Guillaume, l'ancienne (`studio.rollando.castor`) reste
  installée à côté, avec son historique ; à désinstaller par lui quand il veut.

## 2026-11-27 — Repère de la tuile « Lien » plus doux
- Fait : la tuile « Coller un lien » (montrée depuis les chaînes gratuites) n'a plus de contour orange ; la lueur
  s'allume en fondu (0,45 s) et s'éteint en fondu (1,1 s) au lieu de disparaître d'un coup. Installé sur le téléphone.
- Pas fait : rien.

## 2026-11-27 — Réseau (NAS, ordinateurs) et album intelligent
- Fait :
  - Accueil : Sites web, Album, **Réseau**, Coller un lien (la tuile Vidéos disparaît, l'Album fait les deux).
  - Album : une vidéo seule se lit comme une vidéo ; plusieurs éléments lancent le diaporama.
  - Réseau : serveurs multimédias DLNA trouvés tout seuls (recherche SSDP), dossiers partagés SMB (sondage du port
    445 + nom NetBIOS), ajout à la main (« 192.168.1.40 », « \\PC\Films », « nas:8200 »), connexion avec
    identifiant retenu (mot de passe chiffré par le Keystore), oublier un appareil (appui long), aide Windows / Mac / NAS.
  - Fichiers SMB lus par `SmbProvider` (adresse `content://`, lecture à la demande, rien n'est copié) : relais vers la
    télé, lecteur du téléphone et sous-titres marchent sans changement.
  - jcifs-ng 2.1.10 (SMB 1 à 3.0.2 ; la 3.1.1 échouait à vérifier la signature). Appli : 4,9 → 6,6 Mo (Play).
  - Ménage : les enregistrements de direct laissés par une appli fermée de force sont effacés au démarrage.
  - Textes en 23 langues ; tests `LanTest`.
  - Vérifié sur émulateur : faux NAS DLNA (Browse, ordre naturel, lecture), faux partage SMB protégé (connexion,
    dossiers, lecture sur le téléphone, envoi à la fausse télé, mot de passe retenu après redémarrage).
- Pas fait / pas vérifié :
  - Vrai Windows, vrai Mac, vrai NAS, vraie recherche sur le Wi-Fi (l'émulateur ne voit pas la diffusion du réseau).
  - Liste des partages sur un port autre que 445 (jcifs-ng passe toujours par 445 pour la demander) : donner le
    partage à la main dans ce cas.
  - Photos d'un serveur DLNA (masquées) ; vidéos du réseau absentes de « Reprendre » (comme les vidéos du téléphone).

## 2026-11-27 — Notifications demandées au bon moment
- Fait : plus de demande d'autorisation des notifications à l'ouverture de l'appli. Pendant une lecture, une carte
  « Ta télécommande dans les notifications » explique à quoi elles servent (pause, ±10 s, arrêt dans les
  notifications et sur l'écran verrouillé ; jamais de pub ni de message), avec « Afficher la télécommande »
  (demande d'Android) ou « Non merci ». Un refus est respecté : la carte ne revient plus. Une carte à la fois
  (la carte batterie vient après). Textes en 23 langues. Vérifié sur émulateur (installation neuve).
- Pas fait : rien.

## 2026-11-27 — Aide Réseau dépliable, Linux ajouté
- Fait : dans « Regarder les vidéos de ton ordinateur », Windows, Mac, Linux et NAS sont des blocs repliés qu'on
  déplie au besoin. Nouveau bloc Linux (partage multimédia GNOME, ou partage réseau local + smbpasswd), 23 langues.
  Vérifié sur émulateur.
- Pas fait : rien.

## 2026-11-27 — Dossiers du réseau en favoris
- Fait : ★ dans un dossier ouvert du Réseau (NAS, ordinateur) ; les dossiers étoilés ressortent sur l'accueil dans
  « Mes favoris » (avant les sites ; la rangée garde « Mes sites » tant qu'aucun dossier n'est en favori), ouverts
  d'un appui directement dans le dossier, retirés par appui long. 30 au plus, gardés dans `LanStore`.
  Titre « Mes favoris » en 23 langues. Vérifié sur émulateur avec le faux NAS du PC.
- Pas fait : rien.

## 2026-11-27 — Favoris réseau : appareil entier, retour, fil d'Ariane
- Fait : ★ aussi sur l'appareil entier (NAS, ordinateur ; tuile avec son icône). Arrivé par un favori, « retour »
  au premier niveau ramène à l'accueil. Fil d'Ariane sous le titre (« NAS › Séries › Saison 1 »), chaque étape
  touchable pour y remonter, y compris jusqu'à l'appareil quand on est entré par un dossier favori. Vérifié sur émulateur.
- Pas fait : rien.

## 2026-11-27 — Vidéothèque du réseau
- Fait :
  - Mosaïque d'affiches : dossiers d'abord (affiche de la série ou du film quand le nom est reconnu), puis les films.
    Dossier d'épisodes → série : affiche et titre, saisons, épisodes avec image et vrai titre (« 1. Pilot »).
  - Informations dans l'ordre demandé par Guillaume : balises du fichier (titre, année, jaquette intégrée, lues par
    Android sans tout télécharger), images posées à côté (poster.jpg, Film.jpg…), nom du fichier, puis l'annuaire de
    Stremio en complément (réglage « Affiches des films », éteint = rien ne part et le cache est vidé).
  - Tri (nom, ajoutés récemment, année) et « masquer les vus », retenus ; coche « vu » et barre d'avancement.
  - Vidéos du réseau dans « Reprendre » (épisode suivant proposé quand le précédent est fini), « Épisode suivant »
    dans l'ordre du dossier, reprise à la bonne position. Avant de relancer : vraie demande à l'appareil ; éteint →
    « NAS … ne répond pas. Vérifie qu'il est allumé et sur le même Wi-Fi. » (23 langues).
  - Mémoire bornée : réponses de l'annuaire 600, balises 600, vidéos retenues 300, « vu » 800, jaquettes 25 Mo.
  - Vérifié sur émulateur avec de vrais noms (Dune, Matrix, Amélie, Oppenheimer, Interstellar, Breaking Bad, The Office).
- Pas fait / pas vérifié : jaquettes intégrées et balises sur de vrais fichiers (les fichiers de test n'en ont pas) ;
  vrai NAS Synology / Plex / Jellyfin ; photos d'un serveur DLNA toujours masquées.

## 2026-11-27 — Essai avec les vrais films du disque T5 et un fichier à balises
- Fait : les 4 films du disque Samsung T5 (noms bruités « …MULTi.VFQ.2160p…Wawacity… ») sont reconnus avec affiche
  et année. Un fichier au nom illisible (VID_…mp4) avec titre, année et jaquette intégrés s'affiche par ses balises.
  Corrigé : le titre écrit dans le fichier passe avant celui de l'annuaire ; « Épisode suivant » seulement entre
  épisodes (plus entre deux films d'un même dossier).
- Pas fait : rien.

## 2026-11-27 — Vidéothèque : sens du tri, genres, note, « Au hasard »
- Fait : tri comme l'explorateur Windows (flèche ↓ croissant / ↑ décroissant à côté du tri choisi ; retoucher le
  tri inverse ; nouveau tri dans son sens naturel : nom A→Z, ajout / année / note les plus récents ou mieux notés
  d'abord ; sans valeur toujours à la fin). Nouveau tri « Note ». Note ★ IMDb sous chaque affiche. Puces de genres
  (traduits, 21 genres en 23 langues, tirés de la fiche du film, gardée) pour filtrer, et « Au hasard » (un film
  pas encore vu parmi ceux affichés). Vérifié sur émulateur.
- Pas fait : rien.

## 2026-11-27 — Vue série dans le dossier de la série
- Fait : un dossier de série (« Breaking Bad » avec « Saison 1 », « Saison 2 ») s'affiche en vue série : affiche et
  titre, puis chaque saison en bloc dépliable (nombre d'épisodes, combien sont vus) avec ses épisodes. La première
  saison qui reste à voir est ouverte d'office. « Épisode suivant » passe d'une saison à l'autre (vérifié S01E03 →
  S02E01). Pluriel « épisodes » en 23 langues. Vérifié sur émulateur.
- Pas fait : rien.

## 2026-11-27 — Télé allumée vue « éteinte ? », sous-titres intégrés sur le téléphone
- Fait :
  - La Philips 55OLED760 (192.168.1.66) ne répond jamais à la recherche SSDP, même allumée : elle restait « Ne répond
    pas : éteinte ? ». Désormais : une télé trouvée qui lit ou est en pause (GetTransportInfo PLAYING /
    PAUSED_PLAYBACK) est marquée allumée ; la télé où la vidéo passe n'est jamais marquée « éteinte ? » ; ouvrir la
    liste des télés relance la recherche (l'état n'est plus celui d'avant l'allumage).
  - Sous-titres intégrés à la vidéo (MKV, MP4) lus sur le téléphone : la carte Sous-titres liste les pistes (langue,
    nom) et « Aucun », au lieu de « choisissez-les sur la télé ». Vérifié sur émulateur avec un MKV fr + en.
- Pas fait / pas vérifié : la correction « allumée » sur la vraie télé (Guillaume) ; une télé allumée mais à l'arrêt
  (rien en lecture) reste vue par la recherche seule.

## 2026-11-27 — Guidage « Coller un lien » aussi pour un habitué
- Fait : sur l'accueil d'un habitué (Reprendre, favoris…), « Coller un lien » de la carte Chaînes TV gratuites ne
  saute plus directement à la fenêtre : l'accueil amène la tuile « Lien » à l'écran et la fait briller (même lueur en
  fondu que la grande tuile du nouveau venu). Vérifié sur émulateur.
- Pas fait : rien.

## 2026-11-27 — Flow (Arte) figé sur la télé après quelques secondes
- Cause (journaux du téléphone + flux relayé analysé avec ffprobe) : Arte sert désormais ses vidéos en MP4 par
  morceaux (CMAF) avec le son à part, et propose du HEVC. La Philips ne lit pas le HLS, donc flux continu : le relais
  choisissait la variante HEVC (1080p) et ne recollait pas le son (fusion faite seulement pour le TS). La télé
  affichait quelques secondes puis se figeait (compteur qui avance, 1,3 Mo en attente sur la connexion).
- Fait : choix de variante H.264 d'abord (HEVC seulement s'il n'y a rien d'autre) ; nouveau `Fmp4Merge` qui recolle
  le son au MP4 fragmenté (en-tête à deux pistes, fragments renumérotés, son calé dans le temps comme pour le TS, ce
  qui absorbe l'écart 5,991 / 6,000 s des morceaux d'Arte). Vérifié sur les vrais morceaux de Flow à 2682 s :
  H.264 + AAC, mêmes horodatages, décodage complet sans erreur (test `Fmp4MergeTest`, avec FMP4_SAMPLES).
- Pas vérifié : la lecture sur la vraie télé (attendre l'essai de Guillaume).

## 2026-11-27 — Audit des formats : DASH, coupures pub, sous-titres des diffuseurs, IPTV, chaînes en favoris
- Fait :
  - Coupures HLS (#EXT-X-DISCONTINUITY : pubs insérées, pré-roll) : `TsClock` (PTS/DTS/PCR) et `Fmp4Clock` (tfdt)
    recalent l'horloge du flux continu et de l'enregistrement des directs. Testé : morceau à 5000 s recollé à la suite.
  - DASH sans DRM (`Dash`) traduit en liste interne : SegmentTemplate (Number, Time, SegmentTimeline), SegmentList,
    SegmentBase, index dans le fichier (sidx simple et à plusieurs niveaux). Télé : flux continu MP4 image + son
    (`Fmp4Merge`) ; téléphone : module DASH de Media3 ; pistes son du MPD ; directs DASH sur le téléphone seulement.
    Testé sur 7 manifestes publics (Akamai, DASH-IF Netflix/Sony, Shaka, Unified Streaming) et sur émulateur
    (lecture téléphone, 5 langues, sous-titres, envoi à la fausse télé).
  - Sous-titres : TTML / EBU-TT-D / IMSC → SRT ; WebVTT HLS découpé recollé (X-TIMESTAMP-MAP) ; sous-titres DASH en
    fichier ou en MP4 (wvtt, stpp, `Mp4Text`), répliques coupées refusionnées. Sur le téléphone, seuls les sous-titres
    choisis dans la carte s'affichent (le lecteur n'en impose plus d'autres).
  - IPTV : listes acceptant UDP / RTP multicast (relayés en HTTP par le téléphone, à la télé et à son lecteur) et
    RTSP / RTMP (téléphone seulement, message « Ce flux se lit sur le téléphone »). Modules RTSP et RTMP de Media3.
  - Chaînes en favoris (★, appui long sur une chaîne de l'accueil ou de la mosaïque) dans « Mes favoris ».
  - Nom technique « dash.mpd » plus pris pour un titre de film. Appli Play : 6,7 → 7,2 Mo.
- Pas vérifié : multicast UDP/RTP (l'émulateur ne reçoit pas le multicast du PC), RTSP / RTMP (pas de serveur de
  test), coupures pub sur une vraie chaîne, étoile sur les chaînes à l'écran (compilé, pas essayé).

## 2026-11-27 — « Passer l'intro / le récap / le générique »
- Fait : `Skips` cherche les passages dans cet ordre : chapitres du fichier MKV (« Opening », « OP », « Ending »,
  « Générique »…, `MkvProbe.chapters`), TheIntroDB (séries, par IMDb + saison + épisode, gratuit sans clé), AniSkip
  (animés, MyAnimeList trouvé par Kitsu ; écarté si la durée de l'épisode ne concorde pas). Bouton ambré au-dessus
  de la barre (et en plein écran) pendant le passage : « Passer l'intro », « Passer le récap », « Passer le
  générique », ou « Épisode suivant » pendant le générique de fin quand le suivant est connu. Même bouton que l'on
  regarde sur le téléphone ou sur la télé (déplacement de la lecture). Textes en 23 langues.
  Vérifié en vrai : Breaking Bad S01E02 (intro 5:14–5:31), Frieren ép. 1 et Jujutsu Kaisen S2E3 (OP/ED), MKV à
  chapitres sur émulateur (bouton affiché, saut 3 s → 9 s).
- Corrigé en route : l'annuaire renvoyait n'importe quel titre (« Mon Anime » → « I Love You, Man ») : un mot
  important en commun est maintenant exigé (cache des titres renouvelé) ; un titre sans extension (DLNA,
  « Mon.Anime.S01E01 ») perdait son « S01E01 » ; lecture des chapitres tolérante à un serveur qui coupe tôt.
- Pas vérifié : le bouton « Épisode suivant » pendant le générique de fin à l'écran (même code) ; sur la vraie télé.

## 2026-11-27 — Idée notée : pub discrète + premium
- Noté dans `~/Dev/Gestion de projet/Universal TV Cast/idees-plus-tard.md` : si un jour Guillaume veut un revenu,
  une seule bannière non bloquante (accueil), achat unique pour l'enlever, version libre sans pub. Pas d'actualité.

## 2026-11-27 — Noter l'appli (nouvel incontournable du studio)
- Fait : ligne « Noter l'appli » dans les réglages, juste après « Partager » (ouvre la fiche Play), et fenêtre
  officielle d'avis de Google (`review-ktx`, version Play seulement) proposée au calme : en revenant à l'accueil,
  rien en lecture, après 3 vidéos regardées 10 min ou plus sur 2 jours, appli installée depuis 3 jours ; 3 fois au
  plus, espacées de 4 mois (`data/ReviewPrompt.kt`). Aucune question avant, aucune récompense (règles Google).
  Version libre : ni ligne ni fenêtre. Textes en 23 langues.
  Standard du studio passé à 4 incontournables (`standard-incontournables.md` § 4, tableau, `~/Dev/CLAUDE.md`).
- Pas vérifié : la fenêtre de Google elle-même (elle n'apparaît que pour une appli installée depuis le Play Store).

## 2026-11-27 — Image dans l'image (gratuit) et pack soutien (cosmétique)
- Image dans l'image (commit précédent) : vidéo du téléphone en petite fenêtre en quittant l'appli, ±10 s et pause.
  Gratuit pour tout le monde (décision de Guillaume).
- Pack soutien : achat unique sur Google Play (`billing-ktx` 9.1.0, produit `pack_soutien`, confirmé auprès de
  Google, revérifié à chaque ouverture, retiré si remboursé). Version libre : tout ouvert, sans bibliothèque de
  paiement. Version de débogage : ouvert. Seule source de revenu : Google Play (pas de Ko-fi, etc.).
- Il ouvre « Personnalisation » (en tête des réglages, visible par tous, grisé sans le pack avec le bouton
  « Soutenir · prix ») : 6 couleurs d'accent (thème de toute l'appli), noir intégral OLED, écran d'attente de la télé
  au choix (classique coloré, photo du téléphone en plein écran avec heure, grande horloge avec date), message perso,
  « écran vivant » (l'image est renvoyée à la télé chaque minute : heure à jour, photos qui défilent), 5 icônes
  d'appli (alias du lanceur, posées en quittant l'appli : Android ferme la tâche lancée par l'ancienne icône),
  bouton « Voir sur la télé ». Photos copiées réduites (1920 px, 12 au plus) dans l'appli. Textes en 23 langues.
- Vérifié sur émulateur (débogage) : couleurs et OLED appliqués à toute l'appli, aperçu horloge + message, photo
  choisie dans le sélecteur d'Android et aperçu, changement d'icône sans fermer l'appli puis relance par la
  nouvelle icône.
- Pas vérifié : l'achat réel (le produit `pack_soutien` est à créer par Guillaume dans la Play Console, avec un prix ;
  les testeurs de licence l'achètent gratuitement), l'écran d'attente vivant sur la vraie télé.
- À faire avant publication : la politique de confidentialité doit mentionner l'achat via Google Play.

## 2026-11-27 — Films Dolby Vision refusés par la télé (Babylon)
- Constat : Babylon (MKV 4K HEVC, Dolby Vision profil 8.1 + HDR10+, son E-AC-3 / Atmos) : la Philips lit le début
  du fichier puis s'arrête (durée 0:00:00) ; l'appli renvoyait la vidéo en boucle. Sur le téléphone, beaucoup
  d'images sautées et le son jeté par le décodeur.
- Essais depuis le PC vers la télé (extraits de 90 s) : MKV d'origine, MKV une seule piste audio, MKV son AAC
  → refusés ; MKV sans Dolby Vision, TS (avec ou sans Dolby Vision), MP4 → lus. Puis : même MKV d'origine dont
  seule la déclaration Dolby Vision de l'en-tête (BlockAdditionMapping dvvC, 66 octets) est remplacée par un
  élément vide de même taille → lu, deux fois sur deux. C'est donc cette déclaration qui fait refuser le fichier.
- Fait : le relais masque cette déclaration au passage pour les télés DLNA (`MkvProbe.dolbyVisionMask`,
  `ProxyServer.patched`), même longueur, donc plages et avance rapide intactes ; image en HDR10 (couche de base).
  Pas pour le profil 5 (sans couche de base lisible), ni pour Google Cast. Tests `DolbyVisionMaskTest`, dont un
  sur le vrai film : flux corrigé identique au fichier accepté par la télé.
- Pas vérifié : la lecture du film entier depuis le téléphone (à relancer par Guillaume) ; l'image et le son à
  l'écran (seul l'état « lecture » de la télé a été relevé). Pas fait : message clair quand une télé refuse un
  fichier, au lieu de le renvoyer.

## 2026-11-27 — Télé allumée mais pas proposée
- Constat : la Philips ne répond jamais à la recherche réseau ; au repos (allumée, rien en lecture) l'appli la
  croyait éteinte. Le bouton « Regarder sur la télé » n'apparaissait qu'avec une télé « allumée » ; et même la
  télé demandée à la main, la recherche finie sans télé « allumée », la vidéo repartait seule sur le téléphone.
- Fait : toute télé déjà vue sur le Wi-Fi est proposée (bouton « Regarder sur la télé » sur l'écran de lecture,
  même pendant la lecture sur le téléphone, qui ouvre le choix de la télé) ; le téléphone reste le choix par défaut
  à l'ouverture d'une vidéo sans télé repérée allumée, mais plus jamais après que l'utilisateur a demandé la télé.
- Relevé pour plus tard (télé allumée, au repos) : Cast `:8008/setup/eureka_info` répond, JointSpace
  `:1925/6/system` répond (`/6/powerstate` exige l'appairage), AirPlay `:7000/info` statusFlags=580. À comparer
  avec la télé en veille pour savoir la reconnaître allumée sans qu'elle lise. Le téléphone ne voit pas non plus
  la télé en Google Cast (« appareils Cast : aucun ») alors qu'elle l'a : à creuser.

## 2026-11-28 — Reconnaître la Philips allumée ou en veille
- Relevé (télé allumée au repos, puis en veille à la télécommande) : en veille, le récepteur DLNA, AirPlay,
  l'interface Philips (1925) et le ping répondent toujours, et le DLNA accepte même une vidéo sans rien montrer.
  Seul le service Google Cast (`:8008/setup/eureka_info`) se ferme (connexion refusée, 4 fois sur 4).
- Fait : une télé au repos dont le service Cast est ouvert est reconnue allumée (choisie d'office comme les
  autres) ; l'appli retient les télés où elle l'a déjà vu ouvert (`Library.castHosts`) et, s'il est fermé au moment
  d'envoyer, n'envoie rien et dit « … est en veille. Allume-la avec sa télécommande, puis réessaie. » (23 langues).
- Essai : un paquet Wake-on-LAN envoyé du PC (adresse matérielle lue dans la table ARP du PC) a été suivi du
  retour du service Cast 9 s plus tard (à confirmer : télé rallumée par le paquet ou par Guillaume ?). Une appli
  Android ne peut pas lire l'adresse matérielle (table ARP interdite) et la télé ne la donne nulle part (DLNA,
  Cast, AirPlay, JointSpace, DIAL) : pas d'« Allumer la télé » pour l'instant.
- Pas vérifié sur le téléphone : télé allumée choisie d'office, message de veille.
