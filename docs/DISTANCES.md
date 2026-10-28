# Distances en temps réel sur le Direct — fiche revérifiée dans le code de cet arbre le 2026-10-26

Chaque objet suivi du Direct porte sa distance à la caméra : une pastille « ≈ 3,2 m » de verre sombre, posée
au-dessus du coin haut-gauche de sa boîte, avec une puce et un liseré de la couleur de son masque ; plus la
distance de l'objet le plus proche dans la ligne d'état et une pastille par concept en bas d'écran
(« personne 2,1 m »).

**Aucun GPU, zéro requête supplémentaire** : tout est calculé sur le téléphone, à partir de ce que `/live/ws`
ramène déjà (label + boîte + masque). Une frame sans objet calibré n'affiche rien du tout — pas de pastille
« ? », pas de chiffre inventé (`DistanceMeter.measure` renvoie une liste vide, `core/…/distance/DistanceEstimator.kt`).
Transport du direct : **WebSocket `/live/ws` uniquement** (`LIVE_WS_PATH`, `core/…/net/WsTransport.kt:35` ; voir `docs/TESTING.md`) — sans effet sur
le calcul de distance, qui ne voit que la frame décodée.

## La mesure : sténopé sur hauteur réelle connue

```
distance (m) = hauteur réelle de l'objet (m) × focale (px) / hauteur de la boîte (px)   (Pinhole.distanceMeters)
focale (px)  = (largeur de l'image / 2) / tan(champ de vision porté par la largeur / 2) (LensSpec.focalPx, :35-39)
```

Deux entrées, deux sources :

| Entrée | D'où elle vient |
|---|---|
| **Hauteur réelle** | `KnownSizes.TABLE` (`KnownSizes.kt:40+`) : table label → mètres (personne 1,70 · voiture 1,45 · camion 2,80 · porte 2,05 · fenêtre 1,30 · bouteille 0,25 · verre 0,12…). Casse, accents, pluriel et expressions (« sac à dos ») sont neutralisés. Concept inconnu ⇒ **pas de distance**. |
| **Focale en pixels** | `LensSpec`, selon trois sources **dans cet ordre de priorité** : le **champ saisi dans Réglages** s'il est positif (`cameraFovDeg > 0`), sinon la **focale mm + taille physique lues par l'interop Camera2 à la liaison** (`lensSpecOf`, `app/…/CameraLens.kt:22-27`), sinon 67° (`LensSpec.DEFAULT_H_FOV_DEG`, grand-angle typique). Ordre vérifié : direct `LiveViewModel.chosenLens` (`:338-341`), photo iSee `IseePhotoDistances.chosenLens` (`:53-56`). **Attention : la voie REC d'iSee ne passe pas par le capteur** — `LiveProbe.configureFrom` applique `LensSpec.fromSettings` (`ProbeWiring.kt:22-31`), qui borne en plus la valeur saisie à 15–130° (`LensSpec.kt:43-46,71-72`) alors que le direct accepte tout champ positif. Écart connu, non corrigé. |

L'axe vertical du capteur sert quand l'image est en portrait (`sensorLandscape` + `widthFovDeg`, `LensSpec.kt:23-29`)
— le direct redresse chaque frame avant envoi (`uprightScaled`, `LiveScreen.kt:291`). La mesure est indépendante de la
résolution envoyée : `focalPx` se recalcule pour chaque frame depuis les dimensions **revenant du serveur**
(512 px côté direct `LIVE_EDGE`, `LiveScreen.kt:45` ; **320 px** côté iSee `ISEE_FRAME_EDGE`, `IseeCameraPane.kt:39` ;
`work_edge` demandé au serveur : 640, `LiveSession.kt:66,173`).

**Bornes de plausibilité** (`Pinhole`, `DistanceEstimator.kt:30,33,36`) : rien sous **0,3 m** (`MIN_METERS`),
rien au-delà de **60 m** (`MAX_METERS`), rien pour une boîte de moins de **4 px** de haut (`MIN_BOX_PX`). Hors bornes ⇒ pas d'affichage.

**Objet coupé par le bord haut ou bas ⇒ pas de distance** (`Pinhole.isVerticallyCut`, marge de **2 px**,
`DistanceEstimator.kt`). Constat de recette sur téléphone (non rejoué ici) : une couette segmentée à tort en « personne », collée
aux deux bords, sortait à **31 m** — la boîte ne mesurait plus la hauteur réelle. Les bords gauche/droite
restent acceptés : seule la hauteur porte la mesure.

## Lissage : lisible à 1-2 images par seconde

`DistanceSmoother` : moyenne mobile exponentielle **par piste** (l'identité stable du journal du direct —
`LiveTimeline.record` renvoie l'id de chaque détection ; un objet non identifié est affiché **brut**, sans
lisser, `DistanceMeter.readOne`). Deux vitesses — **0,35** pour accompagner un objet qui s'approche, **0,85**
quand la mesure fait un bond de plus de **50 %** (l'objet a réellement bougé, ou la piste a été reprise). Une
piste muette plus de **2 s** est oubliée : l'objet qui revient est mesuré à neuf. Valeurs aux défauts du
constructeur (`DistanceSmoother.kt:17-22`), testées dans `DistanceMeterTest`.

Sur la page caméra iSee (REC) : **pas de journal de pistes, mesure brute**
(`LiveProbe.readingsOf` n'appelle jamais `record`) — la pastille y bouge donc
un peu plus qu'en Direct.

## Précision — ce que le chiffre vaut

Le doc annonçait « ±15-20 % sur un objet **debout et entier** » : **jamais confronté à un mètre ruban,
précision non mesurée** — à tenir pour une estimation d'ingénierie, pas une mesure. Trois causes d'erreur connues :

- **hauteur réelle supposée** : un enfant mesuré comme « personne » (1,70 m) sort deux fois trop loin ;
- **objet coupé** par un bord haut/bas : écarté depuis (il n'affiche rien) ;
- **objet penché ou allongé** (chaise vue de dessus, table basse) : la boîte ne mesure plus la hauteur réelle.

L'étiquette porte le « ≈ » pour cette raison (`DistanceText.approximate`), et le format dégressif — cm sous
1 m, décimètre jusqu'à 10 m, mètre au-delà (`DistanceText.format`) — ne promet pas plus de précision que ça.

## Aspect et cadrage des pastilles

Depuis le 26/10, une pastille n'est plus un rectangle plein de la couleur du masque : c'est du **verre sombre**
(texte blanc, lisible sur n'importe quelle image) avec une **puce** et un **liseré** de la couleur du masque,
posée au-dessus du **coin haut-gauche** de la boîte et reliée à elle par un **fil de rappel** qui se termine
par un point sur ce coin (`drawPill`, `DistanceOverlay.kt:93`). La couleur ne sert plus qu'à **désigner** l'objet,
plus à remplir la vignette ; le fil lève l'ambiguïté entre deux boîtes voisines (constat du 26/10 : la pastille
centrée ne disait pas de quel objet elle parlait, et une pastille poussée vers le bas pouvait atterrir sur un
autre objet).

Sur demande du user le 27/10/2026, ce verre devient **partiellement transparent**, en forme de capsule :
la caméra reste visible à travers un fond sombre en dégradé, légèrement teinté par le masque. Le contour
reprend `maskColorAt(colorIndex)`, comme la puce et le fil, avec un reflet intérieur discret. Le texte blanc
garde une ombre courte pour rester lisible sur une scène claire, et les chiffres tabulaires évitent les
variations de largeur d'un chiffre à l'autre. Le fond est isolé dans `DistanceBadgeSurface.kt`, partagé par
les hôtes de `LiveDistanceLayer` (caméra iSee, photo et Direct). Tous les fils sont dessinés avant toutes
les pastilles pour ne jamais passer par-dessus leur texte.

Le placement est une fonction pure (`placePills`, `DistanceLayout.kt`) : alignement sur le coin de la boîte,
**bornage dans le cadre**, **jamais sous le bandeau du haut** (inset **230 dp** — `TOP_SAFE_INSET` : boutons
« Arrêter / Réglages », ligne d'état, concepts), et **empilement vers le bas** par pas de
`hauteur + PILL_STACK_GAP` quand deux pastilles se recouvrent. Le texte est mesuré (`TextMeasurer`) avant le
placement, donc la pastille a exactement la largeur de son contenu ; tout est dessiné dans un seul `Canvas`,
fils dessous, pastilles dessus. `PillPlacement.leadY()` choisit le bord que le fil touche — le plus proche de
la boîte, donc le bas quand la pastille est au-dessus, le haut quand l'empilement l'a poussée dessous.
Testé dans `DistanceLayoutTest` ; l'aperçu avant/après qui a servi à choisir cette forme était une maquette canvas hors dépôt.

## À voix haute

La voix (ElevenLabs, bascule « Annoncer les objets et leur distance » — coupée **ou** clé absente ⇒ silence
sans erreur visible, cf. piège (b) de `docs/TESTING.md`) dit la scène **avec les distances** :

```
Personne à 9 heures, à 1,4 mètre ; chien à midi, à 3 mètres.
```

Exact, dans le code : au-delà de 3 objets cités (`PositionText.MAX_OBJECTS`), le reste est résumé (« , et 2
autres objets. ») ; le séparateur devient le point-virgule dès qu'une distance est présente (`PositionText.phrase`,
`core/…/live/PositionText.kt:101-121`).

Pas de duplication : c'est l'annonce de positions existante (`PositionAnnouncer` : stabilité **2 frames**,
pas de répétition, **4 s** entre deux phrases — `AnnouncementGate.kt:83,86`) qui porte désormais la distance
dans `PositionText.item`, et **la distance arrondie au mètre entre dans la signature de scène**
(`PositionText.key` `:140-145` + `bucket` `:190-191`) — c'est ce qui fait reparler un objet qui s'approche (3 m → 2 m →
1 m) sans bavarder à chaque frame. `DistanceText.spoken` met les mètres en mots de la langue de la voix — français ici
(`SpeechLanguage.Fr`, défaut du paramètre `DistanceText.kt:36`), anglais si elle est réglée : « 85 centimètres »,
« 1,4 mètre », « 12 mètres » ; le singulier va jusqu'à 2 sur la valeur **arrondie** (« 1,5 mètre »).
La phrase effectivement dite reste visible dans le panneau iSee sous `dit : …` (`LiveProbeText.kt:57`).

## Réglages

- **Distances sur le direct** (bascule, activée par défaut, `Settings.distanceOverlayEnabled:35`) : coupe
  l'overlay **et** le calcul (`DistanceMeter.enabled`).
- **Champ de vision caméra (°)** : `0` = lecture du capteur (voie normale). À saisir si le driver est muet ou
  si l'étalonnage dérive — la valeur se vérifie facilement : à 2 m, une personne de 1,70 m doit afficher
  « ≈ 2,0 m » (recette de contrôle ; tolérance chiffrée non mesurée).

## Où c'est testé (comptages `@Test` de cet arbre, 2026-10-26, par `grep -c '@Test'`)

- `core.distance.LensSpecTest` (**7**) : focale paysage/portrait, capteur natif portrait, bornes, déduction du champ vertical.
- `core.distance.KnownSizesTest` (**6**) : **tous** les concepts par défaut du direct sont calibrés — ils sont
  **six** (`person`, `door`, `bicycle`, `car`, `truck`, `electric scooter` — `LivePrompts.kt:27`, `MAX = 6` `:34`),
  et la table couvre bien plus large (animaux, véhicules, mobilier, rue — `KnownSizes.TABLE`) ; synonymes FR/EN,
  accents, concept inconnu.
- `core.distance.DistanceMeterTest` (**12**) : formule (personne 200 px, focale 386 px ⇒ **3,29 m**), doublement
  de distance quand la boîte est deux fois plus petite, bornes, objet non calibré, coupé haut/bas, colle aux
  deux bords, lissage doux vs bond, oubli après silence, mesure réelle dans les bornes.
- `core.distance.DistanceTextTest` (**7**) : « 85 cm », « 3,3 m », « 12 m », « ≈ », plus proche, par étiquette, mots parlés.
- `core.live.LiveTimelineTest` (**10**, dont le test « record rend l'identité **dans l'ordre des détections** »,
  `null` pour une détection inexploitable).
- `core.live.PositionTextTest` (**10**) + `PositionAnnouncerTest` (**8**) : secteurs, ordre de lecture, distance
  dans la phrase et dans la signature, stabilité/non-répétition/4 s.
- `app.DistanceLayoutTest` (**15**) : transformation `Crop` identique à l'aperçu, étiquette ancrée au coin
  haut-gauche de sa boîte et ramenée dans le cadre, bandeau du haut, `placePills` (pastille posée au-dessus de
  son coin, empilement vers le bas quand deux pastilles se recouvrent, deux pastilles éloignées qui ne bougent
  pas, pastille plus large que le cadre), bord touché par le fil (`leadY`), saisie du champ (± bornes),
  pastilles du bas, préfixe du concept.
- `app.isee.IseeDistancesTest` (**5**) : pastilles d'une photo segmentée — personne haute mesurée (≈ 3,9 m),
  une pastille par objet, concept inconnu ignoré, photo vide ou de taille nulle, boîte coupée par le bord bas
  non mesurée.
- `app.isee.LiveProbeTextTest` : ligne d'état REC avec `plus proche ≈ 1,1 m` et détail `distances :` trié.
- Recette sur téléphone réel (1220×2712, relevé non rejoué ici) : ligne d'état
  « Direct · 1,5 ips · 635 ms · 4 objets · plus proche 2,0 m · NVIDIA A100 », pastilles
  « ≈ 2,0 m / ≈ 2,1 m / ≈ 2,3 m » superposées aux masques, section Réglages « Distances » rendue.
  Mesure datée, format relevé à l'époque ; la ligne du direct s'écrit aujourd'hui via `LiveState.line`.

## Pistes (non faites)

- **Plan de sol** : distance pour n'importe quel objet qui touche le sol, à partir de la hauteur et de
  l'inclinaison de la caméra — plus juste pour « l'obstacle devant moi », mais il faut lire le pitch du capteur.
- **Profondeur métrique réelle** (Depth Anything sur la GPU Modal) : plus juste, mais réveil du GPU payant et
  latence par frame — décision explicite du user.
- **Étalonnage guidé** : « tiens-toi à 2 m » → ajustement du champ de vision.
- **Précision chiffrée** : une fois pour toutes, comparer 10 objets à un mètre ruban et remplacer « ±15-20 %
  non mesuré » par un chiffre daté.
