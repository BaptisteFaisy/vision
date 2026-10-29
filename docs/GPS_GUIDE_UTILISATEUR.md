# GPS iSee — guide utilisateur (dire → voir → arriver)

Mode d'emploi du guidage GPS d'iSee : on **dit** la destination, on **voit**
les écrans, on **arrive** guidé. Le vocal MVP (Maps direct) reste spécifié
dans `docs/NAVIGATION-VOCALE.md`, le contrat technique dans
`docs/NAVIGATION.md`, le backend Valhalla dans `docs/VOICE_NAV_PROXY.md` —
ce guide n'y touche pas, il les cite.

Prérequis du guidage in-app : une **URL Valhalla renseignée** (§4). Sans
elle, iSee garde le handoff direct Google Maps, à l'identique (aucun écran,
aucun suivi) — c'est la ligne d'état des Réglages qui le dit.

## 1. Dire (la voix)

1. **Appui long** (~300 ms) sur la surface iSee → « J'écoute. Parle, puis
   relâche pour envoyer. »
2. **Dicter** : « je veux aller à la gare de Lyon », « emmène-moi à X »,
   « aller à X », « aller X » (sans le « à ») — 12 motifs, liste exacte dans
   `docs/NAVIGATION-VOCALE.md` § 3. Transcription Meta, repli ElevenLabs
   Scribe. Une destination non comprise est redemandée à voix haute (« Je
   n'ai pas compris où tu veux aller… »), jamais remplacée par une
   description de la scène.
3. **Secours texte** : si la dictée échoue (bruit, malentendance), l'écran
   propose un champ de saisie clavier — la saisie vaut une dictée : elle
   ouvre la confirmation, sans redicter.
4. **Stop** : « arrête le guidage » (ou « stop », « annule la navigation »)
   à tout moment → « Guidage arrêté » + retour au repos.

## 2. Voir (les écrans)

- **Destination** (toujours, depuis le 29/10) : la dictée n'ouvre que
  cet écran — adresse géocodée + résumé (distance · durée), boutons
  « Démarrer » / « Annuler », lien « Dicter une autre destination ».
  Le départ direct sans bouton (`autoStartOnVoice`) est abandonné : trop de
  guidages partaient sur une question ou une dictée malentendue
  (« comment aller à… ? » devenait un ordre). Sans position GPS ou sans URL
  Valhalla : adresse seule (distance et durée à zéro). Si l'itinéraire
  échoue : adresse + motif en rouge, et « Démarrer » bascule sur Google
  Maps, **à pied**.
- **Guidage** : sur l'accueil iSee, sous la caméra — carte (tracé +
  position), prochaine instruction, distances restantes, bouton
  « Arrêter le guidage », dictée « arrête le guidage ». Le guidage est
  **piéton** : ni voiture, ni transport. La caméra et la sonde
  continuent (évitement d'obstacles préservé) ; la voix iSee parle
  chaque consigne.
- **Écart** : en cas de sortie d'itinéraire, bandeau « Écart — recalcul… »
  + annonce « Vous vous êtes écarté de l'itinéraire. Recalcul en cours. »,
  puis « Nouvel itinéraire calculé. » (recalcul depuis la position
  actuelle, même destination, sans regéocoder).

## 3. Arriver

Annonce d'arrivée + écran « Arrivée à destination » (destination, résumé
du trajet) : « Nouvelle destination » (retour à la dictée) ou « Fermer »
(retour à l'accueil iSee). Le moteur est coupé, la session est au repos.

## 4. Réglages (section Navigation — guidage GPS)

`app/.../NavigationToggle.kt`, câblé sur `Settings.navigationGuidanceEnabled`
(défaut oui) et `Settings.routeBaseUrl`.

- **Interrupteur** « Guider vers la destination confirmée » : à non, seule
  la fiche du lieu s'affiche (« Voici la fiche de X »), sans guidage.
- **URL Valhalla** : défaut embarqué au build = backend Modal CPU public
  (voir `docs/VOICE_NAV_PROXY.md`) ; surcharge possible via
  `route.base.url` (`local.properties`, gitignoré) ou ce champ. **Vide =
  guidage Google Maps uniquement** : la ligne d'état sous le champ l'écrit
  en toutes lettres, pour ne jamais croire guider in-app à tort.
- **Géocodage** (existants, sans clé) : `Geocoder` Android (GMS) d'abord,
  Base Adresse Nationale (`api-adresse.data.gouv.fr`) en repli
  (`GeocodeChain`, `AndroidGeocoderSource`, `BanGeocoder`). Valhalla ne
  géocode pas : il reçoit des lat/lon.

## 5. En fond (service de premier plan)

Le guidage continue l'app en fond : GPS + voix ininterrompus, **notification
permanente** avec bouton **Arrêter** (même effet que « arrête le guidage »).

- Retour arrière (quitter l'écran navigation) = **arrêter** : pas de
  guidage invisible.
- Accueil / verrouillage = **maintien** : le service garde le guidage, la
  voix continue, l'écran de guidage se rouvre au retour dans l'app.

## 6. Recette manuelle (téléphone, ~15 min)

Préalable : build du HEAD de `vision/master` (`git ls-remote` comparé au
SHA du build — règle ISEE-TEST-MASTER-SEUL), APK installé, clés ElevenLabs
+ voix renseignées, interrupteur sur oui, URL Valhalla par défaut,
téléphone pris sous verrou dur + annonce dans `BOITE-USER.md`.

1. Dicter « je veux aller à la gare de Lyon » → écran Destination
   (adresse + résumé), « Démarrer » → retour à l'accueil iSee : carte
   sous la caméra, annonce puis consignes.
2. Lire le résumé sur l'écran de guidage : distance et durée chiffrées
   (ex. « 2,4 km · 30 min »).
3. Marcher : consignes suivies, distances décroissantes, grand texte lisible.
4. S'écarter d'un pâté de maisons → bandeau « Écart — recalcul… » +
   annonce, puis nouvel itinéraire.
5. Verrouiller 1 min → voix continue ; déverrouiller → écran Guidage
   intact, notification permanente visible.
6. Aller au bout (ou finir près du but) → annonce + écran Arrivée →
   « Fermer » → accueil iSee, silence.
7. Vider le champ URL Valhalla → la ligne d'état dit « Maps uniquement » ;
   redicter → bascule Maps directe, sans écran iSee.

Chaque point : SHA du build, heure, observable (texte écran + phrase
entendue + app au premier plan).

## 7. Check-list non-régression (rien d'autre ne change)

- URL vide : handoff Maps direct **à pied** (`mode=w`), relecture
  « Navigation vers X », comportement MVP inchangé
  (`docs/RECETTE-NAVIGATION-VOCALE.md` rejouable).
- Guidage éteint : fiche lieu + « Voici la fiche de X », pas de guidage.
- Stop vocal hors session : ignoré (chemin question normal), pas d'erreur.
- « Où est X », « c'est loin X ? » : questions LLM, jamais un ordre.
- Tap court / swipe : geste seul, jamais de dictée.
- « va à gauche » : ordre de cap prioritaire, jamais une navigation.
- Verrou sans guidage actif : aucun service, aucune notification.
