# Publication sur Google Play

Tout ce qu'il faut pour la fiche Play Store de **Universal TV Cast** (même nom dans toutes les langues).

## Contenu de ce dossier

- `metadata/android/<langue>/` : titre (≤ 30 car.), description courte (≤ 80) et longue (≤ 4000) au format fastlane
  `supply` (`title.txt`, `short_description.txt`, `full_description.txt`), en **24 langues** : les 23 de l'appli, plus
  `es-419` (espagnol d'Amérique latine). Référence : `fr-FR` et `en-US` ; les autres sont traduites depuis `en-US`
  avec les mots de l'appli (« Reprendre », « Ambiance », « Pack soutien »…).
- `screenshots/<langue>/` : captures téléphone 1080 × 2400 (`fr-FR`, `en-US` ; les autres langues reprennent
  `en-US`), plus `08-tele-ambiance.png` en 1920 × 1080 : ce que la télé affiche en mode Ambiance.
  1. accueil (Reprendre, favoris, Caster autre chose) ; 2. vidéothèque du réseau en affiches ; 3. lecture sur la télé ;
  4. Ambiance en cours ; 5. réglages de l'Ambiance ; 6. Personnalisation ; 7. page d'accueil d'un nouveau venu (en) ;
  8. l'écran de la télé en Ambiance.
- `icon-512.png` : icône haute résolution (512 × 512) ; `feature-graphic.png` : bannière (1024 × 500), sans texte.

## Comment les captures ont été faites

Émulateur (barre d'état en mode démo, 20:30), fausse télé DLNA `tools/test-tele/faketv.py` (renommée « TV du salon » /
« Living Room ») et un vrai serveur multimédia DLNA (rygel) qui sert des **films libres de la Blender Foundation**
(Big Buck Bunny, Sintel, Tears of Steel, Elephants Dream, Cosmos Laundromat, Spring, Sprite Fright) avec leurs
affiches posées à côté des fichiers. Aucune marque ni contenu protégé à l'écran ; les sites suggérés de « Pour
commencer » ont été retirés (« Ne plus proposer ») pour ne montrer aucune marque tierce.

## Crédits des images visibles

- Affiches des films : Blender Foundation (durian / mango / gooseberry / spring / sprite fright…), licences
  CC BY 3.0 et CC BY 4.0, via Wikimedia Commons.
- Photos de l'Ambiance (captures 4 et 8) : Dietmar Rabich, Michal Klajban, « Rhododendrites », CC BY-SA 4.0,
  Wikimedia Commons — le crédit est affiché sur l'image par l'appli.

## Réglages de la console Play

- **Catégorie** : Vidéo (Video Players & Editors). Tags : cast, DLNA, TV
- **Gratuite**, sans pub. **Un produit intégré** : `pack_soutien` (achat unique, non consommable, « Pack soutien »),
  à créer dans la Console avant la sortie (prix conseillé : ~2-3 €). Rien d'utile n'en dépend.
- **Sécurité des données** : aucune donnée collectée ni partagée par l'appli (pas de SDK tiers, pas de compte, pas de
  mesure d'audience). Les achats passent par Google Play (déclaré par Google). Requêtes sortantes sans donnée
  personnelle : titres ou identifiants de films vers des annuaires publics (Cinemeta et Kitsu pour les affiches,
  sous-titres OpenSubtitles via Stremio, TheIntroDB / AniSkip pour sauter les génériques), listes de chaînes iptv-org
  et guide des programmes, photos de Wikimedia Commons et radios de Radio Browser (mode Ambiance). Suppression : les données locales partent avec la
  désinstallation.
- **Pages légales** : la politique de confidentialité doit citer Google Play (achat du pack) et les services
  ci-dessus, dont les nouveaux Wikimedia Commons et Radio Browser (Ambiance) — skill `pages-legales`.
- **Classification du contenu** : questionnaire IARC → tout public (l'appli ne fournit aucun contenu ; navigateur
  intégré : répondre « oui » à l'accès à Internet non filtré).
- **Public cible** : 13 ans et plus.
- **Annonces** : non.

## Encore à faire avant la publication

- Registre des fiches : cs, ro, ru, uk vouvoient (registre poli des fiches de ces pays) alors que l'appli tutoie ;
  pl tutoie. À changer si on veut coller à l'appli.
- Créer `pack_soutien` dans la Console, mettre à jour la page de confidentialité, envoyer l'AAB.
