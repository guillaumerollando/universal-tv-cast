# Castor : veille technique et propositions (25 novembre 2026)

Deux recherches : (1) les briques open source et SDK pour caster vers toutes les télés, (2) les bonnes pratiques
des « télécommandes » DLNA éprouvées (Universal Media Server et ses 221 profils de télés, go2tv, Gerbera,
BubbleUPnP). À lire avec [avis-concurrents.md](avis-concurrents.md).

## 1. Ce qui existe

| Brique | Ce qu'elle couvre | État | Verdict |
|---|---|---|---|
| **Google Cast SDK** (officiel) | Chromecast, Google TV, Android TV, et beaucoup de télés avec Cast intégré (Sony, Philips, TCL, Hisense…) | Maintenu par Google | **À adopter.** Le plus gros gain de couverture. Pas d'en-têtes ni de CORS côté télé : notre relais les fournit. |
| **Connect SDK Core** (LG, Apache-2.0) | DLNA, Roku, LG webOS, vieux LG, DIAL, AirPlay | Cœur encore mis à jour (2025-2026), paquet Maven vieux de 11 ans | Possible en sous-module. Lourd, mal maintenu côté appli. |
| **jUPnP** (suite maintenue de Cling) | DLNA/UPnP seulement | Maintenu (3.0.x) | Possible, mais notre code DLNA marche déjà ; les vrais problèmes étaient ailleurs. |
| **Roku ECP** | Roku | Quelques requêtes HTTP, non officiel | Petit ajout à faire nous-mêmes, avec le relais. |
| **Fire TV** | Amazon | Fling arrêté en mars 2026 ; remplaçant (Matter) inadapté | Plus tard, via FCast (protocole ouvert, récepteur gratuit). |
| **AirPlay** | Apple TV, télés AirPlay | Pas de bibliothèque Java fiable, échecs fréquents sur les télés non Apple | Non. |
| **Samsung Smart View / LG webOS** | Accès natif | SDK vieillissants ou non documentés | Non : leur DLNA suffit. |

Ce que personne ne fournit et qui reste **notre force** : le relais sur le téléphone (en-têtes du site, HLS
recollé, fichiers locaux), le navigateur qui repère les vidéos, l'entrée « lecteur externe ».

Aucune preuve publique de ce qu'utilisent Web Video Cast, LocalCast ou BubbleUPnP en interne.

## 2. Ce que font les applis éprouvées et que Castor ne fait pas encore

1. **Ne jamais faire attendre la télé.** Le lecteur des télés (GStreamer sur la Philips) abandonne après
   **15 s sans recevoir d'octets**. Les serveurs sérieux envoient les en-têtes tout de suite et lisent
   la source en avance pour garder des données sous la main. Notre relais attend la réponse de la source avant
   de répondre à la télé : c'est là que la télé abandonne quand Stremio est lent ou endormi.
2. **« Arrêter » = Pause par défaut.** Aucune appli ne documente le problème HDMI, mais le même cas existe sur
   des amplis (Denon) : Stop fait changer de source, Pause non. Le changement de source vient du logiciel de la
   télé : on ne peut que l'éviter. Stop seulement sur demande explicite.
3. **Vidéo suivante** : Pause → nouvelle vidéo → Play (ce que fait déjà Castor), Stop en secours.
4. **Avance sur un flux sans taille** : c'est l'appli qui relance le flux au bon endroit (go2tv) ou le serveur
   qui accepte une demande « à partir de telle minute » (`TimeSeekRange.dlna.org`, Universal Media Server).
5. **Profil par défaut le plus sûr** : MPEG-TS H.264 + AAC, `video/mpeg`, envoi par blocs (jamais « sans taille
   et fermeture »), `CaptionInfo.sec` pour les sous-titres, pas de code `DLNA.ORG_PN` si on n'est pas sûr.
6. **Découverte** : sockets liés au réseau Wi-Fi (sinon un VPN casse tout), recherche aussi envoyée directement
   à l'adresse connue de la télé.
7. Ne jamais déplacer la télé pendant qu'elle charge, une commande à la fois (déjà fait).

## 3. Propositions

### Lot A : que ça marche parfaitement (quick wins)
- **Arrêter sans HDMI** : le bouton Arrêter met en pause et se déconnecte ; « arrêter la télé » devient un choix
  explicite.
- **Relais qui ne fait plus attendre la télé** : en-têtes envoyés tout de suite, lecture anticipée de la source
  (ce qui garde aussi Stremio réveillé tant que la télé lit).
- **Premier lancement** : un écran court qui explique d'autoriser Castor (et Stremio) à tourner en arrière-plan,
  avec les boutons vers les bons réglages ; c'est la cause n°3 des plaintes chez les concurrents.
- **Commandes sur l'écran verrouillé et dans la notification** (lecture/pause, ±10 s).
- **Sockets liés au Wi-Fi** (marche avec un VPN actif).

### Lot B : caster sur toutes les télés
- **Google Cast** (Chromecast, Google TV, télés avec Cast intégré, dont la Philips du testeur qui a un récepteur
  Cast) : on garde notre relais, on ajoute le CORS.
- **Roku**.
- Un seul bouton « Choisir une télé » qui liste tout, avec le type d'appareil.

### Lot C : le confort que les autres font payer
- **Reprise à la minute près** pour chaque vidéo.
- **File d'attente** et épisode suivant.
- **Minuterie de veille**.
- **Navigateur** : écarter les pubs détectées comme vidéos, afficher la qualité (1080p, 720p…).

### Plus tard / non
- Plus tard : sous-titres OpenSubtitles (compte développeur à créer), listes IPTV, Fire TV via FCast.
- Non : AirPlay émetteur, miroir d'écran, transcodage sur le téléphone.

## Sources principales
- https://developers.google.com/cast/docs/media — https://developers.google.com/cast/docs/caf_receiver
- https://github.com/ConnectSDK/Connect-SDK-Android-Core — https://github.com/jupnp/jupnp/releases
- https://developer.roku.com/dev/docs/external-control-api — https://github.com/futo-org/fcast
- https://developer.amazon.com/docs/fling/frequently-asked-questions.html
- https://github.com/UniversalMediaServer/UniversalMediaServer/tree/main/src/main/external-resources/renderers
- https://github.com/alexballas/go2tv — https://docs.gerbera.io/en/stable/supported-devices.html
- https://groups.google.com/g/bubbleupnp/c/TLrQlArbtvQ (Pause plutôt que Stop)
- https://manpagez.com/html/gst-plugins-good-plugins-1.0/gst-plugins-good-plugins-1.0-1.14.0/gst-plugins-good-plugins-souphttpsrc.php
- https://forum.webostv.developer.lge.com/t/seeking-in-transcoded-videos-on-the-media-player-app/5784
