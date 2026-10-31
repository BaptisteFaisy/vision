# Mode traversée — bilan du 28/10/2026

Le mode traversée dit à une personne aveugle **quand traverser** : il lit le
feu piéton, surveille les véhicules, et parle (« Vert, traversez. » /
« Rouge, attendez. »). Ce document raconte le travail livré le 28/10/2026
(recherche, paquet `traverse/`, câblage) et ce qui reste à prouver avant un
usage réel. Code : `core/.../traverse/`, `app/.../traverse/`,
`core/.../crossing/`, `app/.../isee/CrossingWiring.kt`.

## 1. Recherche préalable (10 angles)

Dix agents ont passé le mode traversée en revue : détection des feux,
trafic, décision, suivi temporel, UX malvoyants, capteurs, archi temps réel,
données, sécurité, roadmap. Verdict : fail-safe de base sain (non-vert =
attendre), mais 3 trous critiques — feu vert **voiture** confondu avec le
feu piéton, **grille-feu** non détecté (les boîtes `car` n'étaient pas lues),
voix **muette ou préemptée** (flag `urgent` non câblé, ElevenLabs seul).
Quatre chantiers retenus : gains perf, hystérésis, veto trafic, voix sûre.

## 2. Implémentation (paquet `traverse/`, PR #163–167)

Implémenté en 4 streams parallèles (un worktree par stream, review, fix),
intégré, porté sur `vision/master` car le dépôt de travail était archivé :

- **Perf (#163)** : `mask_encoding` bits, WebSocket `/live/ws` et `/release`
  centralisé déjà présents sur `vision` — la PR ne verrouille que le contrat
  (test requête unique, `LifecycleContractTest` conservé).
- **Hystérésis (#164)** : `TraverseState.kt` — confirmation après 2
  détections, 2 frames manquantes tolérées, seuils 0,5 / 0,6 / 0,4, boîte
  gelée. Fini le clignotement du `/live` sans état.
- **Veto trafic (#165)** : `TraverseVeto.kt` — machine
  ATTENDRE / PRÊT / TRAVERSER / ALERTE ; un véhicule (score > 0,5) qui
  approche (boîte croissante) ou proche (< 15 m sténopé) force « attendez »,
  même sur vert ; le guidage obstacles reste actif devant un objet proche.
- **Voix sûre (#166)** : `TraverseVoice.kt` — l'urgent (vert→rouge) coupe la
  lecture en cours et n'est jamais jeté ; préchauffage hors-ligne des 3
  phrases FR via le TTS système, repli cache automatique si ElevenLabs échoue.
- **Intégration + câblage (#167)** : `TraversePipeline` (hystérésis → veto →
  annonceur → voix), `TraverseWiring`, adapteurs `:app`, branchement dans
  `LiveViewModel.publish`.

## 3. Le direct décide (#174)

Le veto recevait `lightGreen = false` en dur : le mode ne disait jamais
« traversez ». La PR #174 branche le classifieur :

- `crossingStateOf()` (extrait de `crossingCueOf`, sonde inchangée) :
  boîte SAM au meilleur score → crop JPEG → `TrainedLightColor` ;
- `TraverseWiring` relit le vert à chaque frame **active** et demande le
  concept feu dans les prompts (5 véhicules + feu, plafond 16) —
  `pedestrian traffic light` depuis le 31/10 (ordre user : piétons seuls,
  rappel 87,5 %, `docs/MESURES-SAM3-FEUX.md` §2) ;
- `LiveViewModel` transmet le JPEG (1 ligne, fichier maintenu à 498 lignes).
- Porte anti-confusion (`PedLightGate`) : le vert classifié ne passe que
  s'il est en bas du cadre, élancé comme un bonhomme, sans rouge franc —
  méthode sans données (priors structurels + 12 tests synthétiques JVM),
  direction sûre (tout rejet vaut « attendez »). R1b (29/10) : dans la
  bande 20-35 % de vert en bas, la forme du nuage tranche (allongé,
  intérieur, masse non entassée en haut) — rappel vert 81/183 → 90/183
  sur lot FR jour, 0 fuite rouge, seuils existants intacts.
- Veto TTC sur boîtes SAM (`VehicleApproach`) : association IoU entre frames
  `/live` (pas d'ids serveur), vitesse lissée (`MotionModel` réutilisé),
  tau = largeur / vitesse < 3 s force le veto — en OU avec le veto
  instantané. Pas de tracking serveur (app `sam3-track` non déployée, 2e
  GPU interdite) : suivi 100 % client, zéro déploiement backend.

## 4. Ce qui tourne où (dépendances extérieures)

- **Dans l'app, hors-ligne** : couleur du feu (`TrainedLightColor`, pur JVM),
  hystérésis, veto, annonceur, voix de repli (clips + TTS système).
- **Processus extérieur** : les **boîtes d'objets** (feu + véhicules) viennent
  de SAM via le backend `/live` (GPU Modal, réseau) — sans lui, pas de boîtes,
  donc rouge, donc « attendez » (défaut sûr, jamais de vert inventé). La voix
  ElevenLabs primaire est aussi réseau, avec repli cache automatique.

## 5. Preuves (28/10, `master`)

- `:core:testDebugUnitTest` + `:app:testDebugUnitTest` verts sur r630
  (2 264 tests, 0 échec à la vérification #167 ; +3 tests #174 :
  `TraverseWiringTest` 9/9, `CrossingWiringTest` 3/3 après refactor).
- UBS : 0 finding nouveau (l'exit 2 sur `CrossingWiring.kt` est un faux
  positif d'outil prouvé pré-existant sur master vierge).
- Limites : fichiers < 500 lignes, fonctions < 50 (`check-kotlin-limits` OK).

## 6. Reste à prouver (bloquant pour un usage réel)

1. **Calibration FR** (lot 1 sans terrain, fait 10/2026) : arbre validé
   sur 235 lampes FR (Panoramax, jour) — couleur 97,9 %, 0 vert sur
   90 rouges ; porte recalibrée (fraction 0,004, élancement 1,5,
   bas 0,35) : rappel bonhomme 28/42, 0/47 disques de face. Reste :
   poses larges, corpus téléphone + seuil bloquant faux verts.
   **Nuit et pluie sortis du périmètre le 29/10** (décision produit, voir
   `docs/CALIBRAGE-FEU.md` §9) : le mode ne traite que le jour.
2. **Terrain** : aucun test téléphone — crop réel, voix sur appareil, veto
   avec vraies distances, à valider sur build du HEAD.
3. **Anti-confusion feu voiture / feu piéton** : porte recalibrée sur
   feux réels (2 disques de profil passent, cas frontal couvert) —
   reste la preuve sur crops SAM réels (cadrage exact inconnu).
4. **Rappel SAM** sur feux piétons FR jamais mesuré (inconnue n° 1).

## 7. Ce que la vidéo montre (29/10)

L'état du feu n'est plus seulement une pastille en bas de carte et une phrase :
il est **posé sur l'image**, au même endroit que les masques SAM. Un cadre de la
couleur de l'état et une étiquette courte (`VERT`, `ROUGE`) désignent
le feu qui vient d'être lu.

- placement : `isee/CrossingOverlayLayout.kt` — `lightMarker` reprend la
  transformation des pastilles de distance (`cropTransform`, `ContentScale.Crop`),
  donc le cadre tombe sur les mêmes pixels que les masques ; `tagRect` met
  l'étiquette au-dessus du cadre, ou en dessous quand le feu touche le haut, et
  la ramène dans l'écran ;
- dessin : `isee/CrossingOverlayLayer.kt` — cadre de 2,5 dp, pas de rectangle
  plein (même règle que les distances : la couleur désigne l'objet, elle ne
  masque pas l'image) ;
- source unique de la couleur et du mot : `crossingAccent` / `crossingTag`,
  réutilisés par la pastille de carte — deux surfaces, un seul vocabulaire ;
- rien de dessiné quand le mode est coupé ou qu'aucun feu n'est dans le champ :
  un cadre vide mentirait sur la position du feu.

Depuis §9 (binaire), plus d'écart de vocabulaire : sans lecture, pastille,
cadre et bouton attendent comme un rouge (« … » et `DOUTE` supprimés le
31/10). Vérifié par `CrossingOverlayLayoutTest`.

**Ce que ça ne prouve pas** : que le cadre tombe juste sur un téléphone réel. Le
placement est testé en JVM sur des cadres simulés, pas sur une frame capturée —
même limite que les distances, dont la recette appareil reste à faire.

### Où c'est affiché

Les **deux** surfaces qui montrent des masques SAM portent le cadre :

- l'accueil iSee (`IseePortraitCamera.StreetFeed`), caméra du téléphone **et** lunettes ;
- l'écran Direct (`LiveScreen.LiveCameraStage`), via `LiveViewModel.withCrossing`
  (`app/LiveCrossingPublish.kt`) alimenté par `TraverseWiring.light`.

### Est-ce temps réel ?

Oui au sens du direct, non au sens du 30 ips — et c'est le même compromis que les
masques et les distances :

| | valeur | source |
|---|---|---|
| rafraîchissement du cadre | **1 fois par frame analysée** (cadence du direct : 150 ms en `Cadence.Fast`, 350/500/900 ms en cadence prudente) | `core/live/LiveSession.kt`, `docs/LATENCE-BUDGET.md` |
| retard du cadre sur l'aperçu | l'A/R complet : **422 ms médian**, plancher 123,7 ms | `docs/LATENCE-BUDGET.md` § étages (`HTTP`) |
| coût ajouté par frame | un décodage JPEG complet + crop : **10-25 ms**, comparable à l'étage `DECODE` | idem |
| thread | `Dispatchers.Default` (`scope.launch(background) { runFrame(...) }`) : **aucun à-coup** sur l'UI | `isee/LiveProbe.kt` |

Conséquence pratique : sur un feu — qui ne bouge pas — le cadre est juste, même avec
400 ms de retard. Dès que le téléphone **panote**, le cadre traîne derrière l'image
vivante, comme les pastilles de distance. Ce n'est pas un défaut de l'overlay, c'est
l'âge de la dernière frame analysée.

### Deux cadences, pas une (29/10)

La couleur et la position n'ont pas la même urgence :

- **la couleur** dit quoi faire, elle se périme vite → relue **localement sur
  chaque frame caméra, au plus tard toutes les 150 ms** (`LightClock`,
  `isee/CrossingLocalLight.kt`). `PedLightGate` est 100 % embarqué : rien ne
  l'oblige à attendre le serveur ;
- **la position** dit où regarder, elle ne bouge pas → elle garde la cadence
  SAM (350-900 ms). Une boîte de 400 ms sur un feu fixe est juste.

Concrètement, l'état affiché cesse d'avoir l'âge de l'aller-retour (422 ms
médians) : il a l'âge de la dernière frame caméra, borné à 150 ms. La voix,
elle, garde son hystérésis (`CrossingAnnouncer`) — deux seaux, deux usages.

**Ce que ça ne règle pas** : la justesse. Rendre l'affichage plus frais ne le
rend pas plus vrai ; la mesure du 29/10 donne **1 vert accepté sur 6** signaux
verts réels (`docs/MESURES-SAM3-FEUX.md` §6). Le prochain gain est là, pas dans
la cadence.

## 8. Proposer le mode quand un passage piéton est dans le champ (29/10)

`crosswalk` fait déjà partie des **douze concepts par défaut** du direct
(`LivePrompts.default`) : SAM le détecte depuis le 29/10 sans que rien ne
l'affiche. Aucun prompt à ajouter, aucun coût serveur de plus.

Quand un passage piéton est vu sur **3 des 5 dernières frames** publiées et
qu'aucun mode n'est actif, la pastille de carte change de libellé
(« Passage piéton — traversée ? », ambre) et **un tap** active le mode Traversée —
le geste qui existait déjà. Rien n'est automatique : entrer en Traversée change
les concepts suivis (feu piéton + `car` — `pedestrian traffic light` depuis le
31/10, ordre user : piétons seuls) et coupe le guidage obstacles, ce
n'est pas une décision que l'app prend seule.

- logique pure : `core/crossing/CrossingOffer.kt` — `isCrosswalkLabel`
  (canonicalisation accents/casse), `seesCrosswalk` (score ≥ 0,40), et
  `CrossingOfferGate` (fenêtre 5, seuil 3) ;
- mémoire : `isee/CrossingWiring.CrossingOfferWatch`, une par processus — la
  garde des 500 lignes ne laissait pas de place à un champ de plus dans
  `LiveProbe` ;
- un **refus se tait une minute** (`declined`, posé quand on coupe le mode) :
  longer un carrefour sur 50 mètres ne doit pas faire clignoter la pastille.

### Le concept est-il détecté ? Mesuré, oui (29/10)

Même protocole que les feux (RTX 3060 Ti, `unsloth/sam3.1` bf16, seuil 0,5), dix
photos publiques :

| jeu | images | détections `crosswalk` |
|---|---|---|
| passages piétons réels (Londres ×4, Brunei ×1) | 5 | **7**, scores **0,54 à 0,89** — au moins une par image |
| scènes **sans** passage (2 salons, 3 vues aériennes d'autoroute) | 5 | **0** |

Le concept discrimine sur cet échantillon : aucune détection sur les négatifs.
Variantes plus bavardes : `pedestrian crossing` 10 détections, `zebra crossing` 8,
`sidewalk` 14 (le trottoir est partout, le passage non). Le prompt par défaut
`crosswalk` est le plus économe.

**Limites** : n = 10, aucune nuit ni pluie, aucun passage **français** ; et un
passage vu de biais, partiellement masqué par une file de voitures, n'a pas été
testé. Ce n'est pas une garantie de non-faux-positif en rue commerçante.

Images : Wikimedia Commons, CC BY-SA 2.0/4.0 et CC0 — Conduit Street (David
Howard), Freeman Street ×2 (Stephen Craven), Quality Street (Richard Sutcliffe),
Brunei (Yu Chu Chin), IKEA Rostov (Vyacheslav Argenberg), Rio (Wilfredor),
Somerset/M5 (Marek Ślusarczyk), Barcelone (Joe Mabel), Beachley (Colin Park).

### Ce que la voix dit maintenant

Le mode Traversée parlait déjà l'état du feu (`CrossingText.phrase` : « Vert,
traversez. » / « Rouge, attendez. ») et confirmait la
bascule (« Mode traversée activé. »). Depuis le 29/10, le passage reconnu
était **proposé** à la voix (« Passage piéton. Dites traverser. ») ; depuis
le 30/10, il n'est plus proposé — le mode **s'arme seul**, et le dit.

### Bascule automatique (30/10)

Ordre user : plus de proposition manuelle — le passage détecté arme le mode,
le passage perdu le coupe, et les personnes restent annoncées en traversée.

- **entrée** : le passage proche 3 frames sur 5 (`CrossingOfferGate`,
  ≤ 2 m mesurés (`OFFER_MAX_METERS`, sinon boîte prouvée sous les pieds —
  sans preuve de proximité, silence) arme le mode et dit
  « Mode traversée activé. » **une fois**
  par apparition (`observeCrossingAuto` : `distinctUntilChanged` + `filter`
  sur l'état d'offre, comme avant — jamais une phrase par frame) ;
- **sortie** : le passage perdu 4 frames publiées de suite (`CrossingLossGate`,
  une réapparition remet le compteur à zéro) coupe le mode et dit « Mode
  traversée coupé. » (`observeCrossingLoss`, compté sur les frames publiées
  seules via `crossingSightPerFrame` — l'état ré-émet entre deux frames pour
  le poste « pixel », sans ce filtre chaque frame compterait double) ;
- **propriétaire** : seule la vision auto-perd (`IseeSteeringOrders.visionArmed`)
  — un mode armé à la main ou par le GPS reste jusqu'à sa propre coupe (un
  passage sans bandes se traverse sans zébrures), et seul un refus manuel
  fait taire la proposition une minute (`setCrossingActive(auto = …)`) ;
- **personnes** : les prompts traversée ajoutent `person` et `crosswalk`
  (12 concepts, plafond 16 tenu) ; le feu parle en premier, et sur son
  silence les personnes sont annoncées au cadran (« Personne à 9 heures, à
  2 mètres », `CrossingPeopleWatch`, débouncé comme toute annonce) ;
- **décalage opposé** (30/10, après-midi, étendu le soir aux obstacles) : des
  gens proches qui s'approchent d'un côté disent d'aller de l'autre
  (« Droite. », mot nouveau seul, clip < 100 ms — le même `LateralNudgeTracker`
  que le guidage normal, qui prime sur les positions comme lui), et depuis le
  soir tout obstacle proche qui s'approche (objet, poteau — même filtre que le
  couloir, sol et panneaux hauts exclus), dans les 2 m de l'axe de marche
  (`AVOID_HALF_WIDTH_M`, portée 4 m comme le couloir) ; un ordre du feu nouveau
  fait taire la voix
  douce 2,5 s (`CUE_QUIET_MS`, les répétitions — même phrase, rouge ou
  vert — ne réarment pas) — sans ce silence, le `collectLatest` côté sonde
  jetterait l'ordre pas encore joué (`CrossingPeopleVoice.kt`, la sonde ne
  change que d'une ligne) ; depuis le 31/10 le vert se répète toutes les
  2 s comme le rouge, un ordre jeté se rattrape à la répétition suivante ;
- **états moteurs** (30/10, soir) : en traversée, les voitures disent si
  elles roulent ou sont arrêtées (« Car moving, 5 meters away. » /
  « Car stopped, 2 meters away. », langue de la voix, anglais par défaut) —
  le même `VehicleStateWatcher` que le mode normal (mouvement confirmé ou
  croissance jusqu'à 15 m — `MOVING_METERS`, info douce au-delà de 8 m,
  arrêt mesuré jusqu'à 3 m, 2 frames, silences 4 s / 10 s), parlé après le
  feu et avant les personnes (`CrossingVehicleWatch`, chaîne
  `crossingPhraseOf`) ; le silence post-ordre est partagé
  (`CrossingCueQuiet`, `:core` pur, les deux voix douces nourries du même
  ordre) et les personnes ne sont consultées que sur le silence des
  véhicules — un mot de décalage ne se perd pas au profit d'une phrase
  jetée. Vélos et trottinettes sans voix traversée (le veto les couvre) ;
  `observeVehicleState` reste coupé en traversée (une seule voix, pas de
  doublon). Sonde +1 ligne (`LiveProbe.kt` à 499).

## 9. Binaire rouge/vert + sorties confirmées (31/10)

Ordre user : fini le doute — rouge ou vert, et une différenciation certaine.

- **Binaire** : `CrossingState` n'a plus que `Red` et `Green`. Tout non-vert
  attend rouge (pas de boîte, crop illisible, couleur indécise, porte refusée)
  : classifieurs, porte, voix, vibration, affichage et voicepack suivent (le
  clip `cross_unsure` et ses MP3 sont supprimés). Le chemin vert est inchangé
  (arbre + porte + 4 frames) : seul le sens sûr change de nom.
- **Sorties confirmées** : audit du 31/10 — la voix exigeait 4 frames mais
  l'haptique et l'overlay suivaient la lecture brute (1 frame verte erronée
  suffisait à vibrer vert et afficher VERT, même sous veto). Depuis : l'état
  publié est le dernier **annoncé** (rouge par défaut), voix, vibration et
  affichage parlent d'une seule voix. La pompe locale ≤ 150 ms contournait la
  confirmation : supprimée (`CrossingLocalLight`, `LightClock` et leurs tests).
- **Fuzz anti-inversion** (`PedLightGateFuzzTest`, ~230 assertions, graines
  fixées) : rouges réels et synthétiques × photométrie, géométrie, bruit,
  balance des blancs — jamais verts. Pré-validé sur un modèle Python fidèle
  (412/413, l'écart expliqué : ligne 1 px = doute tri-state du modèle, rouge
  binaire en Kotlin).
- **Non fait, assumé** : aucune retouche de la porte (colonnes 1-3 px et
  pixels chauds isolés restent acceptés en théorie — comportement documenté
  par les planchers de sensibilité, à régler sur données, voir §6 et la
  spec de collecte) ; la fusion arbre+HSV proposée est formellement subsumée
  par la porte (no-op sur le vert) et n'a pas été implémentée.
- **Conséquence honnête du binaire** : les verts illisibles (rappel 1/6 sur
  crops SAM, §6) disent désormais « Rouge, attendez. » au lieu de douter —
  même action sûre (attendre), couleur annoncée fausse. Seule la collecte
  (crops téléphone labellisés) remontera le rappel.

## 10. Guidage sur le passage proche, même en Traversée (31/10)

Ordre user : à moins de 3 m en face, iSee aligne l'user à la voix pour qu'il
soit bien sur le passage. Avant, le latéral retombait dès que le mode
s'armait — pile quand l'user s'en approchait, plus d'alignement ni de
« Sur le passage. ».

- **seuil** : `CrossingGuidance.GUIDE_METERS = 3,0 m` — au-delà ou sans
  preuve de proximité (mesure ou boîte prouvée sous les pieds,
  `CrossingOffer.seesNearCrosswalk` avec `maxMeters = GUIDE_METERS`,
  fail-closed), silence comme avant ;
- **mots** : les mêmes qu'hors traversée (« Gauche. », « Droite. »,
  « Tout droit. » en clips < 100 ms, « Sur le passage. » après 2 frames),
  doux (`urgent = false`) — le feu, le veto et les véhicules gardent la
  priorité du son, le latéral attend le silence ;
- **câblage** : `feedCrossingLateral` (`isee/CrossingWiring.kt`) — hors
  traversée inchangé, en traversée guidage si proche sinon retombée ;
  `LiveProbe` inchangée (499 lignes, au plafond) ;
- **limite connue** : en traversée dense (piéton sur les zébrures + user
  décentré), l'évitement (`CrossingPeopleWatch`) et l'alignement peuvent
  alterner des mots opposés — les deux sont vrais, l'arbitrage commun est
  un suivi, pas ici.
- **coexistence avec §9** : le guidage ne touche pas au binaire rouge/vert
  ni aux sorties confirmées — il ne publie que des mots latéraux doux, sous
  la priorité du feu, du veto et des véhicules ; l'état annoncé reste le
  dernier confirmé (rouge par défaut).

## 10. Collecte et réentraînement (31/10)

Le rappel vert (1/6 sur crops SAM, §6) ne se répare que par des données :
interrupteur `collectLightCrops` (TECHNICAL SETTINGS, coupé par défaut),
journalisation des crops classifiés (PNG exact, boîte, verdict, 2000/session
max), étiquetage (`review_light_crops.py`), réentraînement
(`train_light.py`, porte et planchers fixes) et barrière GO (20 ancres
parfaites + précision vert ≥ 0,98 sur hold-out + rappel ≥ actuel).
Protocole complet : `docs/COLLECTE-FEUX.md`.

## 11. Recette feux FR (31/10)

Matrice d'acceptation A1-A5, seuils (0 faux vert bloquant, rappel vert
≥ 50 % provisoire) et checklist vierge : `docs/RECETTE-FEUX-FR.md`.
Non rejouée sur appareil.
