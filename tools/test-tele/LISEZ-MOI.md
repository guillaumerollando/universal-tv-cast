# Tester Universal TV Cast sans la vraie télé

- `faketv.py [durée_s]` : fausse télé Philips Titan OS (port 49152). Reproduit ce qu'on a mesuré sur la vraie :
  HEAD de la vidéo avant de répondre à SetAVTransportURI, « Transport locked » pendant la lecture, 710 si on la
  déplace pendant un déplacement, lecteur qui abandonne si la vidéo n'arrive pas en 10 s (noté « → HDMI »),
  « Stop » noté « → HDMI ». Elle fait elle-même l'`adb forward` vers le relais de Universal TV Cast sur l'émulateur.
- `FAKE_STANDBY=1` : télé en veille réseau (comme la Philips éteinte) : accepte la vidéo, reste « TRANSITIONING », ne lit jamais.
- `FAKE_DROPFIRST=1` : abandonne le tout premier envoi sans lire (la Philips le fait parfois, surtout en quittant l'écran d'attente).
- `slowsite.py <dossier> <délai_s>` : faux site façon torrent (Referer `https://site.example/` exigé), un morceau
  lointain met `délai` secondes la première fois puis devient immédiat.

Sur l'émulateur, Universal TV Cast trouve la fausse télé tout seul (sondage du réseau 10.0.2.x). Vidéo :
`adb shell am start -a android.intent.action.VIEW -d http://10.0.2.2:8765/film.mp4 -t video/mp4 --esa headers "Referer,https://site.example/" -n com.rollandostudio.universaltvcast/.MainActivity`
