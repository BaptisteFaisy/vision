# Distances en temps réel sur le Direct — fiche revérifiée dans le code de cet arbre le 2026-10-31

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
| **Hauteur réelle** | `KnownSizes.TABLE` : table label → mètres (personne 1,70 · personne assise 1,25 · cycliste 1,75 · voiture 1,45 · SUV 1,65 · camionnette 1,95 · camion 2,80 · porte 2,05 · fenêtre 1,30 · bouteille 0,25 · verre 0,12 · borne 0,80 · clôture 1,50 · portail 1,75…). Casse, accents, pluriel et expressions (« sac à dos ») sont neutralisés. Concept inconnu ⇒ **pas de distance**. Les défauts sans hauteur défendable n'ont volontairement pas d'entrée sténopé (`DEPTH_ONLY` : escalier, trottoir, passage piéton, poteau, feu, bordures…) : leur distance vient de la profondeur ou du plan de sol. Chaque entrée porte en plus son incertitude (`KnownSizes.TOLERANCE` : porte ±3 % · adulte ±6 % · enfant/arbre ±15-30 %) qui pondère la fusion. |
| **Focale en pixels** | `LensSpec`, selon trois sources **dans cet ordre de priorité** : le **champ saisi dans Réglages** s'il est positif (`cameraFovDeg > 0`), sinon la **lecture Camera2 à la liaison** (`lensSpecOf`, `app/…/CameraLens.kt`) — d'abord la **calibration intrinsèque** (`LENS_INTRINSIC_CALIBRATION`, focale + centre optique en pixels, `Optics.lensSpecFromIntrinsics`) quand le driver l'expose, sinon **focale mm + taille physique** (`Optics.lensSpec`) — sinon 67° (`LensSpec.DEFAULT_H_FOV_DEG`, grand-angle typique). Ordre vérifié : direct `LiveViewModel.chosenLens`, photo iSee `IseePhotoDistances.chosenLens`. **Attention : la voie REC d'iSee ne passe pas par le capteur** — `LiveProbe.configureFrom` applique `LensSpec.fromSettings` (`ProbeWiring.kt`), qui borne en plus la valeur saisie à 15–130° alors que le direct accepte tout champ positif. Écart connu, non corrigé. |

L'axe vertical du capteur sert quand l'image est en portrait (`sensorLandscape` + `widthFovDeg`)
— le direct redresse chaque frame avant envoi (`uprightScaled`, `LiveScreen.kt`). La mesure est indépendante de la
résolution envoyée : `focalPx` se recalcule pour chaque frame depuis les dimensions **revenant du serveur**
(512 px côté direct `LIVE_EDGE` ; **320 px** côté iSee `ISEE_FRAME_EDGE`).

Le **centre optique** n'est plus supposé au milieu de l'image : `LensSpec` le porte en fractions
(`principalXFrac`/`principalYFrac`, 0,5 = centré), lus de `LENS_INTRINSIC_CALIBRATION` quand le driver
les donne, et le plan de sol (`DistanceMeter.groundMeters`) comme le nuage de points
(`CameraIntrinsics.fromLens`) s'en servent. Le sténopé n'en a pas besoin (hauteurs seules).

**Hauteur et pieds du masque** : quand le masque est fiable (`Detection.maskBox`, posée par
`toDetectionsWithExtents` pour les encodages compacts — `poly` sans décodage, `bits`/`rle`
décodés — non fragmenté), le sténopé mesure la hauteur vraie de l'objet et le sol prend la
ligne des pieds exacte, au lieu de la boîte détecteur. `maskBox` coupée ⇒ pas de mesure,
comme une boîte coupée. La lecture garde la boîte détecteur (overlay, appariement
profondeur). Le PNG n'est pas décodé sur ce chemin (décodeur Android, tests JVM) : tant
que le serveur envoie du PNG, le repli boîte s'applique — passer le direct en masques
`bits`/`poly` activerait les étendues en production (piste ci-dessous).

**Bornes de plausibilité** (`Pinhole`) : rien sous **0,3 m** (`MIN_METERS`),
rien au-delà de **60 m** (`MAX_METERS`), rien pour une boîte de moins de **4 px** de haut (`MIN_BOX_PX`). Hors bornes ⇒ pas d'affichage.

**Objet coupé par le bord haut ou bas ⇒ pas de distance** (`Pinhole.isVerticallyCut`, marge de **2 px**).
Constat de recette sur téléphone (non rejoué ici) : une couette segmentée à tort en « personne », collée
aux deux bords, sortait à **31 m** — la boîte ne mesurait plus la hauteur réelle. Les bords gauche/droite
restent acceptés : seule la hauteur porte la mesure.

**Objet masqué par une meilleure détection ⇒ pas de distance** (`Occlusion.isOccluded`) : une boîte
contenue à **60 %** dans celle d'une détection mieux scorée ne mesure plus son objet (la personne
derrière la voiture sortirait trop loin). Recouvrement partiel, scores égaux ou masqueur moins bien
scoré : les deux sont mesurés — choix conservateur (une pastille de trop plutôt qu'une manquante),
au prix d'une boîte tronquée mesurée trop loin quand le masqueur est mal scoré.

## Lissage : lisible à 1-2 images par seconde

Suivi **Kalman position + vitesse par piste** (`DistanceKalman`, voir « Suivi et fraîcheur ») ;
l'EMA historique (`DistanceSmoother` : 0,35/0,85, bond 50 %, oubli 2 s) reste en repli
(`kalmanEnabled = false`). L'identité stable vient du journal du direct (`LiveTimeline.record`) ;
un objet non identifié est affiché **brut**, sans lisser, et maintenu tel quel par les prédictions.

Sur la page caméra iSee (REC) : **pas de journal de pistes, mesure brute**
(`LiveProbe.readingsOf` n'appelle jamais `record`) — la pastille y bouge donc
un peu plus qu'en Direct.

## Fusion et confiance

Quand le sténopé et le plan de sol parlent tous deux et concordent (rapport ≤ 1,5), la lecture est leur
**moyenne pondérée** (`DistanceFusion.combine`) : le sténopé pèse `1 / (1 + tolérance de sa classe)`
(porte 0,97 · personne 0,94 · arbre 0,77), le sol pèse selon la dépression de ses pieds
(`GroundWeight` : 1 dès 0,2 rad, 0,25 vers l'horizon — de près le sol tire, de loin le sténopé
reprend la main). En cas de divergence, le sténopé garde la main quels que soient les poids —
la pose du sol peut être fausse, le sténopé est la branche validée.

Chaque lecture porte sa **confiance** (`ReadingConfidence`, 0..1) : `1 − 1 px / hauteur de boîte`
(quantification : 0,995 à 200 px, 0,90 à 10 px), multipliée par l'accord des branches quand les deux
parlent (1 si elles coïncident, 0 si leur rapport atteint 2). La voie profondeur propage la
confiance de ses ancres au lieu de la remettre à 1 (`DepthReadings`, calibration affine
`mètres = échelle/proximité + décalage`, moindres carrés robustes dès 3 ancres) et lit sa
médiane sur la fenêtre centrale de la boîte (25 % rognés par côté : les bords portent le
fond, le cœur porte l'objet ; repli boîte entière si dégénérée).

## Suivi et fraîcheur (≤ 150 ms)

Le suivi par piste est un **Kalman position + vitesse** (`DistanceKalman`, défaut ;
`DistanceSmoother`/EMA en repli par `kalmanEnabled = false`) : dt variable, bruit adapté
par la confiance, gating à 3 σ, oubli à 2 s. Entre deux inférences (~1 image/s), le
**ticker à 120 ms** (`DistanceTicker`, direct via `LiveSensors`, sonde via `ProbeMeters`)
republie `DistanceMeter.predictions` : pistes extrapolées par leur vitesse, objets non
suivis maintenus (la sonde ne suit pas), confiance dégradée par l'âge, oubli au timeout.
Pastilles, plus proche et ligne d'état dérivent de `distanceLayer` : une info vieille de
plus de 150 ms n'existe plus à l'écran. La voix garde ses propres grilles (4 s) : 150 ms,
c'est le rythme de l'information, pas du discours.

## Étalonnage guidé

Le biais systématique de focale (capteur ±2-3 %) s'annule en une mesure : un aidant de
taille exacte se tient à une distance mesurée au mètre ruban, le direct le vise
(`CalibrationSnapshots` retient la plus grande personne : score ≥ 0,5, non coupée), et
les Réglages résolvent le champ (`CalibrationSolver` : focale `h × D / H`, champ
`2·atan(W / 2f)`, refus sous 20 px ou hors bornes 15-130°). La valeur mesurée atterrit
dans `cameraFovDeg` (prioritaire, comme un champ saisi) et `CalibrationStore` se souvient
qu'elle est mesurée ; « Clear calibration » revient au capteur. Incertitude affichée :
`±(1 px / h + 0,5 %)` — viser grand (340 px à 2 m : ±0,5°).

## Précision — ce que le chiffre vaut

Le « ±15-20 % sur un objet **debout et entier** » reste **non confronté à un mètre ruban** — estimation
d'ingénierie, pas mesure. Causes d'erreur et leur sort :

- **hauteur réelle supposée** : un enfant mesuré comme « personne » (1,70 m) sort deux fois trop loin ;
  atténué par les entrées de posture (« personne assise » 1,25 m, « cycliste » 1,75 m) quand le concept
  suivi porte la posture, et par la tolérance par classe qui dé-pèse les classes floues dans la fusion ;
- **objet coupé** par un bord haut/bas : écarté (il n'affiche rien) ;
- **objet masqué** par un autre : écarté (`Occlusion`) ;
- **objet penché ou allongé** (chaise vue de dessus, table basse) : la boîte ne mesure plus la hauteur réelle.

Le **banc de précision** (`DistanceBenchmarkTest`, caméra virtuelle exacte) borne l'erreur de formule :
aller-retour sténopé/sol au centimètre de 0,5 à 50 m, et enveloppe de quantification à ±1 px sur la
boîte — **±0,5 % à 2 m, ±1 % à 5 m, ±2 % à 10 m, ±4 % à 20 m** (focale du direct 67°/512 px).
Au-delà, l'erreur est dominée par la hauteur supposée, pas par le calcul.

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
Personne à 1 mètre, chien à 3 mètres.
```

Exact, dans le code : le plus proche d'abord, puis le plus en face ; au-delà de 3 objets cités
(`PositionText.MAX_OBJECTS`), le reste est résumé (« , et 2 autres objets. ») ; le cadran tombe quand la
distance est connue, au mètre entier (`PositionText.phrase`, `core/…/live/PositionText.kt`).

Pas de duplication : c'est l'annonce de positions existante (`PositionAnnouncer` : stabilité **2 frames**,
pas de répétition, **4 s** entre deux phrases — `AnnouncementGate.kt:83,86`) qui porte désormais la distance
dans `PositionText.item`, et **la distance arrondie au mètre entre dans la signature de scène**
(`PositionText.key` + `bucket`) — c'est ce qui fait reparler un objet qui s'approche (3 m → 2 m →
1 m) sans bavarder à chaque frame. `DistanceText.spokenShort` met les mètres en mots courts de la langue
de la voix — français ici (`SpeechLanguage.Fr`, défaut du paramètre), anglais si elle est réglée :
« 50 centimètres », « 1 mètre », « 2 mètres ».
La phrase effectivement dite reste visible dans le panneau iSee sous `dit : …` (`LiveProbeText.kt`).

## Réglages

- **Distances sur le direct** (bascule, activée par défaut, `Settings.distanceOverlayEnabled:35`) : coupe
  l'overlay **et** le calcul (`DistanceMeter.enabled`).
- **Champ de vision caméra (°)** : `0` = lecture du capteur (voie normale). À saisir si le driver est muet ou
  si l'étalonnage dérive — la valeur se vérifie facilement : à 2 m, une personne de 1,70 m doit afficher
  « ≈ 2,0 m » (recette de contrôle ; tolérance chiffrée non mesurée).

## Où c'est testé (comptages `@Test` de cet arbre, 2026-10-31, par `grep -c '@Test'`)

- `core.distance.LensSpecTest` (**12**) : focale paysage/portrait, capteur natif portrait, bornes, déduction du champ vertical, centre optique (défaut, échelle, bornes), focale en pixels (`fromFocalPx`, aller-retour, refus).
- `core.distance.OpticsTest` (**27**) : zone active, recadrages, bornes mm/px, calibration intrinsèque (exacte, décentrée, incohérente).
- `core.distance.KnownSizesTest` (**16**) : chaque défaut du direct est calibré au sténopé **ou**
  explicitement profondeur (`DEPTH_ONLY` — voir le KDoc de `KnownSizes`) ; extérieur couvert
  (`bollard`/`borne`/`plot`/`potelet` 0,80 · `fence`/`clôture`/`grillage` 1,50 · `gate`/`portail` 1,75,
  `barrier` 1,00 déjà là) ; synonymes FR/EN, accents, pluriels, expressions (« clôture grillagée »),
  concept inconnu ; postures assises et sous-types de véhicules ; tolérances par classe et poids de fusion.
- `core.distance.DistanceMeterTest` (**22**) : formule (personne 200 px, focale 386 px ⇒ **3,29 m**), doublement
  de distance quand la boîte est deux fois plus petite, bornes, objet non calibré, coupé haut/bas, colle aux
  deux bords, lissage doux vs bond, oubli après silence, mesure réelle dans les bornes, occlusion (masquée
  écartée, partielle/égaux gardés), confiance croissante avec la boîte, bbox masque (mesure, coupée),
  repli EMA, reset Kalman, prédictions (piste, maintien, oubli, vide).
- `core.distance.DistanceKalmanTest` (**10**) : convergence, vitesse, outlier, saut, timeout, confiance,
  prédiction (extrapolation, label/boîte/confiance, inconnue/périmée/passée).
- `core.mask.MaskDetectionsTest` (**9**) : poly (exact, réduit, hors cadre), bits (exact, épars, dimensions),
  rle réduit, png/none/inconnu/cassé nuls, tracker.
- `app.live.DistanceTickerTest` (**6**) : pas ≤ 150 ms, ticks virtuels, double start, couche (vide, champ).
- `app.isee.ProbeMetersPredictionsTest` (**1**) : ticker sonde insensible au temps virtuel (non-régression `advanceUntilIdle`).
- `core.distance.CalibrationSolverTest` (**3**) : focale retrouvée, tolérance par taille, refus.
- `app.live.CalibrationSnapshotsTest` (**3**) : plus grande personne, coupures/vide, fraîcheur.
- `app.DistanceLayoutTest` (+2) : saisie métrique, résolution (visée/saisie).
- `core.distance.DistanceFusionTest` (**14**) : cohérence, sol seul, coupé, bornes sol, moyenne pondérée,
  sténopé gardé en divergence, poids par tolérance, sténopé dominant de loin, centre optique décentré.
- `core.distance.ReadingQualityTest` (**10**) : occlusion (contenue, scores égaux, masqueur faible gardé, NaN, seuil exact, malformée), confiance (quantification, accord des branches), poids sol (dépression, plancher).
- `core.distance.DistanceBenchmarkTest` (**5**) : banc de précision — aller-retour sténopé 0,5-50 m, aller-retour sol (3 pitchs, centre décentré), enveloppe ±1 px, fusion à branches d'accord.
- `core.distance.GroundPlaneTest` (**17**) : horizon, dépression, ligne à distance, pieds→mètres, latéral, point complet.
- `core.distance.DistanceTextTest` (**8**) : « 85 cm », « 3,3 m », « 12 m », « ≈ », plus proche, par étiquette, mots parlés.
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

## Téléphone cible (mesuré 2026-10-31)

Redmi Note 13 Pro (`2c6852ac`), caméra 0 (celle qu'ouvre le direct) —
`adb shell dumpsys media.camera`, recoupé avec les specs publiées du capteur Samsung HP3 :

| Paramètre | Valeur driver | Recoupement |
|---|---|---|
| Focale | 6,14 mm | 23 mm éq. / 3,78 ≈ 6,1 (web) |
| Tableau | 9,1392 × 6,8544 mm, 4080 × 3060 px | 16320 × 0,56 µm binné 16:1 |
| Zone active | = tableau (pas de recadrage) | `preCorrection` identique |
| Champ | H 73,32° · V 58,34° · `f_px` 2741,07 (512 px : 343,98) | verrouillé par `OpticsTest` |
| Distorsion | 0, 0, 0, 0, 0 (corrigée par le HAL) | rien à redresser en live |
| Centre optique | (2040, 1530) = centre exact | hypothèse `cx, cy` validée |

Conséquences : la chaîne live lit ces valeurs (`CameraLens` → `Optics.lensSpec`) et n'a
pas d'erreur de focale ; le repli `LensSpec.Default` (67°) ne sert que si le driver est
muet (≈ +12 % sur les distances dans ce cas). Pas de stabilisation électronique côté app
(`grep` vide : pas de recadrage FOV), l'OIS est optique. La frame analysée est une
réduction homothétique sans recadrage (`targetSize`, grand côté 512) : `f_px` suit
linéairement et le centre reste centré.

## Pistes (non faites)

Fait depuis : pose IMU temps réel (`PitchMeter`, `docs/POSE-IMU.md`), hauteur/pieds du masque
(`Detection.maskBox`, `bits` demandés par le client — actives si le serveur déployé suit),
profondeur affine (`AffineScale`), Kalman par piste + ticker 120 ms, protocole et script de
recette (`docs/RECETTE-DISTANCES.md`, `scripts/mesure-distances.sh`), étalonnage guidé
(`CalibrationSolver`, section Réglages — voir « Étalonnage »), intrinsèques du téléphone
mesurées et verrouillées (voir « Téléphone cible »).
- **Masques compacts en production** : demander `bits`/`poly` au direct (au lieu du PNG) pour
  activer les étendues de masque partout — ou cache de décodage partagé overlay↔distances
  (pas de double décodage PNG par frame).
- **Précision chiffrée** : passer le protocole `docs/RECETTE-DISTANCES.md` au mètre ruban et
  remplacer « ±15-20 % non mesuré » par un chiffre daté.
- **Profondeur sur masque** : médiane de proximité sur le masque au lieu de la boîte (fonds
  exclus) ; modèle de profondeur plus gros côté serveur.
- **Sol non plan** : ajuster le plan au sol mesuré (RANSAC sur la profondeur) au lieu du plan
  horizontal supposé — trottoirs, pentes, bateaux.
