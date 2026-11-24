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

## 2026-11-24 — La télé n'était pas trouvée
**Constat** (télé Philips 55OLED760/12, Titan OS, en Ethernet, 192.168.1.20) : son récepteur DLNA
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
ni blocs (fermeture de connexion), ce qu'envoyait le flux continu de Castor, la bloque. Elle sonde aussi la fin
du fichier (`Range: bytes=<fin>-`) avant de lire.

**Fait**
- Flux continu HLS envoyé en `Transfer-Encoding: chunked` ; réponse immédiate aux requêtes HEAD.
- Relais direct envoyé par blocs quand le site n'annonce pas de taille.
- Vérifié de bout en bout : téléphone → Castor → télé, flux HLS chiffré protégé par Referer : lu jusqu'au bout.

**Pas fait** : essai par le testeur sur ses vraies sources ; sous-titres sur cette télé.

## 2026-11-24 — Avance qui échoue, télé qui part sur HDMI 1, télé qui plante
**Constat** (journaux du téléphone, source Stremio) : les commandes « changer de vidéo » restaient sans réponse
(la télé interroge la vidéo avant de répondre, et le relais réinterrogeait Stremio à chaque fois) ; Castor
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
- **Préchargement** : avant de déplacer la télé, Castor fait venir le morceau de la source et attend qu'il soit là.
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
- Castor retient l'appli qui envoie la vidéo ; si la télé attend plus de 15 s une vidéo servie par le téléphone,
  message « … Android l'a sans doute mis en veille » + bouton vers les réglages de l'appli source.
- Vérifié sur émulateur + fausse télé : message affiché ; quand la télé abandonne, Castor ne relance rien.

**Pas fait** : vérifier avec Stremio réglé sur « Aucune restriction » (batterie) chez le testeur.

## 2026-11-25 — « Ça fonctionne du feu de Dieu » ; sous-titres et écran de la télé
**Retour du testeur** : avance instantanée (saut à 1h05 direct), mieux que Web Video Cast sur sa Philips.
Restent : pas de sous-titres depuis Stremio ; la télé affiche des infos techniques peu jolies (AAC, 24 Hz, BT 709…).

**Fait**
- Journal `CastorIntake` : tout ce qu'une appli transmet à Castor (pour savoir si et comment Stremio passe ses
  sous-titres).
- Sous-titres repérés dans tout extra dont le nom évoque des sous-titres (en plus des conventions MX Player/VLC).
- Affiche 640×360 (logo + titre) envoyée à la télé (`upnp:albumArtURI`), durée annoncée quand elle est connue,
  `dc:creator` Castor. Vérifié sur émulateur + fausse télé.

**À vérifier chez le testeur** : ce que Stremio transmet (lire `CastorIntake` après un envoi) ; si la Philips
affiche l'affiche/le titre à la place des infos techniques ; si elle affiche des sous-titres envoyés en DLNA.

## 2026-11-25 — Conseils préventifs « mise en veille »
**Fait**
- `data/Background.kt` : détecte si Android peut endormir une appli (liste blanche batterie) et les marques
  connues pour être agressives (Xiaomi, Samsung, Huawei, Oppo…) ; mots exacts du réglage selon la marque.
- Carte dans l'onglet Lecture, avant que le problème n'arrive : d'abord pour l'appli qui envoie la vidéo
  (Stremio…), sinon pour Castor pendant une diffusion ; bouton vers ses réglages et « Ne plus afficher ».
- Rubrique « Dépannage » dans les réglages : télé introuvable, vidéo figée en avançant, format non pris en charge,
  sous-titres, changement de source à la fin, arrêt quand l'écran s'éteint. 7 langues. Vu sur émulateur.

## 2026-11-25 — Aide à la place du dépannage dans les réglages
**Fait** : bouton « ? » en haut, panneau Aide (problèmes courants + « Signaler un problème » qui ouvre l'appli
e-mail vers rollandostudio@gmail.com avec modèle du téléphone et de la télé, version, dernier message), lien
« Aide » dans les messages d'erreur, secouer le téléphone ouvre l'aide (réglage pour le couper). Vérifié sur
émulateur (secousse simulée).

## 2026-11-25 — Design avec un peu de personnalité, carte orange moins insistante
**Retours** : carte orange affichée alors que tout était bien réglé ; « Castor » coupé sur deux lignes ; design
trop neutre ; avance parfois un peu « hésitante » mais qui se corrige ; toujours pas de sous-titres depuis Stremio.

**Fait**
- Carte de conseil seulement si Android peut vraiment endormir l'appli (vérifié : Stremio et Castor sont sur la
  liste blanche du téléphone du testeur, la carte ne s'y affichera plus).
- Barre du haut maison : « Castor » toujours sur une ligne, le nom de la télé se raccourcit.
- Police Fredoka (celle des jeux du studio) pour titres et boutons ; ondes animées autour de la télé pendant la
  lecture (immobiles si les animations sont coupées) ; arcs de diffusion dans la carte d'accueil.

**En cours** : capture du journal pendant un envoi depuis Stremio pour voir comment il transmet les sous-titres.

## 2026-11-25 — Police marquante et barre d'onglets à nous
**Fait** : Lilita One (police de Jimmy's Blackout) pour le nom Castor et les grands titres ; barre d'onglets en
pilule flottante (onglet actif en capsule ambrée avec son nom, les autres en icône, nom lu par les lecteurs
d'écran, point ambré sur Lecture pendant une diffusion). Vérifié en thème sombre et clair sur émulateur.

## 2026-11-25 — Police : Inter Display / Inter
Lilita One jugée « comics », pas pro. Planche de comparaison (Inter Display, Figtree, Fredoka) : choix de
le testeur = **Inter Display** (titres extra-gras, lettres resserrées) + **Inter** (reste). Lilita et Fredoka
retirées ; 3 graisses seulement (appli à 2,1 Mo). Pilule, ondes et arcs conservés. Vérifié sur émulateur.
