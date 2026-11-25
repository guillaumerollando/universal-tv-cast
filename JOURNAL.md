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
