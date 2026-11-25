# Ce que disent les utilisateurs des applis de cast (novembre 2026)

Recherche sur les avis de Web Video Cast (InstantBits) et de ses concurrents : BubbleUPnP, LocalCast, Cast to TV
(InShot), XCast, AllCast, Video & TV Cast (2kit), iWebTV, Castify. Preuves souvent minces : le Play Store et Reddit
se lisent mal de façon automatique. Les avis viennent d'agrégateurs (JustUseApp), de l'App Store, d'AlternativeTo,
des fiches des éditeurs et de forums techniques.

## Les plaintes, par ordre de fréquence
1. **Pubs** (n°1 partout) : pop-ups avant de caster, relancées à chaque échec, appli qui rame à cause d'elles.
2. **Paywalls** : 3 vidéos locales max, vidéos limitées à 5 min, favoris et barre de lecture payants,
   abonnements à la semaine, « payé, marché une fois ».
3. **Connexion peu fiable** : télé pas trouvée, saisie d'IP déroutante, cast qui décroche quand on quitte l'appli
   (optimisation batterie d'Android), VPN qui casse la découverte.
4. **Détection de vidéo** : la pub détectée au lieu du film, plusieurs liens sans savoir lequel choisir.
5. **Formats** : MKV, HEVC, AC3 non lus par certaines télés (pas de transcodage).
6. **Sous-titres** : ignorés par beaucoup de télés en DLNA, mauvais épisode trouvé sur OpenSubtitles.
7. **Avance / lecture** : gel pendant l'avance rapide, liens qui expirent au bout de 30-60 min (URL signées).

## Ce qui est apprécié
- Bloqueur de pubs et de pop-ups du navigateur (l'éloge le plus fréquent de Web Video Cast).
- « Ça marche là où les autres échouent », vrai cast (téléphone libre), pas un miroir.
- Reprise à la minute près, historique, favoris, onglets.
- File d'attente et listes de lecture (BubbleUPnP), minuterie de veille, lecture aléatoire ou en boucle.
- Sous-titres : recherche OpenSubtitles, fichier perso, décalage ; choix de la piste audio.
- Achat unique plutôt qu'abonnement.

## Ce qui est payant chez Web Video Cast (gratuit chez nous = argument)
Sans pub, favoris, page d'accueil perso, historique, **file d'attente**, raccourci sur l'écran d'accueil,
sites les plus visités.

## Pièges techniques connus du DLNA
- Sous-titres : pas de standard ; seule méthode fiable = graver dans l'image (transcodage).
- Avance : beaucoup de télés n'avancent que si la taille du fichier est connue.
- HLS mal lu par les télés : il faut le recoller en un flux (ce que fait Universal TV Cast).
- Choix de la piste audio rarement possible sans remux.
- DRM (Netflix, Disney+) : impossible.
- Stremio a abandonné le DLNA pour ces raisons et renvoie vers les lecteurs externes : c'est notre créneau.

## Sources
- https://justuseapp.com/en/app/1400866497/web-video-cast-browser-to-tv/reviews
- https://appireview.pages.dev/com.instantbits.cast.webvideo
- https://apps.apple.com/us/app/-/id1400866497
- https://cafebazaar.ir/app/com.instantbits.cast.webvideo?l=en
- https://alternativeto.net/software/web-video-caster/about
- https://blog.opensubtitles.com/opensubtitles/web-video-caster-is-the-worlds-best-mobile-browserfor-casting-and-streaming
- https://justuseapp.com/en/app/973895042/video-tv-cast-samsung-tv/reviews
- https://apps.apple.com/us/app/localcast-cast-to-tv/id969804264
- https://bubblesoftapps.com/bubbleupnp
- https://alternativeto.net/software/allcast/about
- https://gist.github.com/Ivshti/12bebc0ae07c38804229fe7c291c7344
- https://discourse.coreelec.org/t/m3u8-hls-dlna-upnp-issues/57919
- https://eu.community.samsung.com/t5/tv/external-subtitles-not-working-ue32m5502/td-p/322945/page/3
