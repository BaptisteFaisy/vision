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
   bas 0,35) : rappel bonhomme 28/42, 0/47 disques de face. **31/10,
   ordre user (détection au plus large) : fraction 0,002, élancement
   1,0 — chiffres ci-dessus caducs, disques admis, A2 levé.**
   Reste : corpus téléphone + remesure sur les 235.
   **Nuit et pluie sortis du périmètre le 29/10** (décision produit, voir
   `docs/CALIBRAGE-FEU.md` §9) : le mode ne traite que le jour.
2. **Terrain** : aucun test téléphone — crop réel, voix sur appareil, veto
   avec vraies distances, à valider sur build du HEAD.
3. **Anti-confusion feu voiture / feu piéton** : porte recalibrée sur
   feux réels (2 disques de profil passent, cas frontal couvert) —
   reste la preuve sur crops SAM réels (cadrage exact inconnu).
4. **Rappel SAM** sur feux piétons FR jamais mesuré (inconnue n° 1).

**31/10 — cadrage tête** (branche `feat/isee-feux-pietons-boitier,
ordre user). SAM suit désormais la petite boîte en plus du caisson
(`pedestrian signal head`, 17e concept) et c'est sa boîte qui part au
classifieur entraîné (`CrossingPrompts.selectLightBox` la préfère,
repli caisson sans tête). Reste à prouver sur le terrain : rappel du
prompt tête et couleur lue sur boîtes tête téléphone, non mesurés.

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
| rafraîchissement du cadre | **1 fois par frame analysée** (cadence du direct : 150 ms en `Cadence.Fast`, moitié de la latence bornée 33-900 ms en `Cadence.Direct` régulée) | `core/live/LiveSession.kt`, `docs/LATENCE-BUDGET.md` |
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
Depuis le 31/10, l'ordre vocal (« traverser », « crossing mode on/off »)
vaut aussi sur le direct (il partait au LLM), et l'activation annonce le
passage en vue avec sa distance (« Passage piéton devant, à 5 mètres. »)
quand la sonde le voit — silence sinon, jamais de chiffre deviné.

### Bascule automatique (30/10)

Ordre user : plus de proposition manuelle — le passage détecté arme le mode,
le passage perdu le coupe ; les personnes ne sont plus annoncées (31/10) —
la voix ne se tait jamais : évitement, sinon tout droit toutes les 300 ms.

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
  (12 concepts, plafond 16 tenu) ; les mots d'évitement passent devant le
  feu (§ 25 : ordre d'évitement d'abord, feu ensuite), répétés toutes les
  300 ms, et « Tout droit. » parle en queue de chaîne hors scan
  (`CrossingPeopleWatch.straight`, battement 300 ms — la voix ne se tait
  jamais) ; les annonces au cadran (« Personne à 9 heures ») sont coupées
  depuis le 31/10 (ordre user : esquiver au maximum, sans décrire) ;
- **décalage opposé** (30/10, après-midi, étendu le soir aux obstacles) : des
  gens proches qui s'approchent d'un côté disent d'aller de l'autre
  (« Droite. », répété toutes les 300 ms, clip < 100 ms — le même `LateralNudgeTracker`
  que le guidage normal, sans positions en traversée — supprimées le 31/10) ;
  depuis le soir, tout obstacle proche qui s'approche (objet, poteau — même
  filtre que le couloir, sol et panneaux hauts exclus), dans les 2 m de l'axe de marche
  (`AVOID_HALF_WIDTH_M`, portée 4 m comme le couloir) ; les mots ne sont
  plus tus par le feu (§ 25, 31/10 : ils passent devant — le silence doux
  2,5 s `CUE_QUIET_MS` ne couvre plus que les véhicules, ordre du feu
  nouveau, répétitions non réarmantes) ; depuis le 31/10 le vert se répète
  toutes les 2 s comme le rouge, un ordre jeté se rattrape à la répétition
  suivante ;
- **états moteurs** (30/10, soir ; stop déplacé le 31/10) : en traversée,
  les voitures disent si elles roulent ou sont arrêtées (« Stop, car
  moving. » — « Stop, 2 vehicles moving. » au pluriel — si le mouvement
  est proche (sous 8 m), « Car moving. » / « Car stopped,
  2 meters away. » sinon, langue de la voix, anglais par défaut — depuis
  le 01/11 le mouvement ne dit plus son emplacement, § 32 de
  `VOIX-DANGER.md`) — le
  même `VehicleStateWatcher` que le mode normal (mouvement confirmé ou
  croissance jusqu'à 15 m — `MOVING_METERS`, arrêt mesuré jusqu'à 3 m,
  2 frames, silences 4 s / 10 s), parlé après le feu et avant les
  personnes (`CrossingVehicleWatch`, chaîne `crossingPhraseOf`) ; le
  silence post-ordre est partagé
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
  clip `cross_unsure` et ses MP3 sont supprimés). Le chemin vert est
  (arbre + porte + annonce immédiate) : seul le sens sûr change de nom.
- **Sorties confirmées** : audit du 31/10 — la voix exigeait alors 4 frames
  (1 depuis, même jour, ordres user : à 4, P(annonce) = p⁴ ; à 2,
  l'intermittence ratait encore des verts persistants), mais
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

Ordre user : à moins de 6 m en face (3 m le 31/10, 6 m depuis le 01/11, §17), iSee aligne l'user à la voix pour qu'il
soit bien sur le passage. Avant, le latéral retombait dès que le mode
s'armait — pile quand l'user s'en approchait, plus d'alignement ni de
« Sur le passage. ».

- **seuil** : `CrossingGuidance.GUIDE_METERS = 6,0 m` (3,0 m le 31/10) — au-delà ou sans
  preuve de proximité (mesure ou boîte prouvée sous les pieds,
  `CrossingOffer.seesNearCrosswalk` avec `maxMeters = GUIDE_METERS`,
  fail-closed), silence comme avant ;
- **mots** : les mêmes qu'hors traversée (« Gauche. », « Droite. »,
  « Tout droit. » en clips < 100 ms, « Sur le passage. » après 2 frames),
  doux (`urgent = false`) — le feu, le veto et les véhicules gardent la
  priorité du son, le latéral attend le silence ;
- **arrivée dernière** (ordre user du 31/10) : « Sur le passage. » passe
  derrière toutes les autres annonces (danger > route > doux > arrivée) —
  parlée au silence seul, sinon gardée et redite en dernier, y compris en
  Traversée (rattrapage latéral, file obstacles coupée) ;
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

## 12. Traversée 100 % manuelle : « crosswalk on/off », fin de l'auto et de la proposition (31/10)

Ordre user : le § 8 est abrogé — plus d'armement automatique (ni vision,
ni GPS, ni retombée à la perte de vue) et plus de pastille « Crosswalk —
cross? ». Le mode est coupé par défaut et ne bascule que par la puce ou
l'ordre vocal : « crosswalk on » / « crosswalk off » (« crossing mode
on/off » et « traverser » conservés).

- **voix** : `CrossingVoiceCommand` n'accepte « crosswalk » qu'avec « on » /
  « off » **après** — « crosswalk » seul, « Find the crosswalk », « Crosswalk
  in 50 meters » et « On the crosswalk. » (dits par le guidage lui-même, que
  le micro peut réentendre) ne sont pas des ordres ;
- **supprimé** : `CrossingOfferWatch`, `CrossingOfferGate`,
  `observeCrossingAuto`/`observeCrossingLoss`/`crossingSightPerFrame`
  (ressuscités par erreur par un merge parallèle — morts, sans appelants),
  `seesCrosswalk`, les champs `LiveProbeState.crossingOffer`/`crossingVisible`,
  le paramètre `offer` de `crossingButtonLabel` et leurs tests ;
- **gardé** : `seesNearCrosswalk` + `crossingOfferMeters` pour le guidage
  latéral doux (« Gauche. », « Sur le passage. »), indépendant du mode ;
  l'ambre `CROSSING_ACCENT_OFFER` (bandeau de scan) ; `CrossingLossGate`
  et ses tests (décision de sortie, horloge couverte par
  `CrossingTriggerExitDecisionTest`).

## 13. Sans feu : pas de « regardez à gauche/droite », verdict toutes les 2 s (31/10)

Ordre user : sur un passage **sans feu**, le scan gauche/droite est coupé —
iSee dit les voitures en mouvement en permanence (voix véhicules, priorité
absolue en traversée, même loin) et, toutes les 2 s, si on peut traverser :
« Traversez. » / « Cross. » sans danger, « Attendez. » / « Wait. » sur
danger. Avec feu : scan > stop urgent > évitement > feu > véhicules doux
(§ 25 ; le scan garde la voix pendant sa procédure).

- **porte** : `NoLightGate` (core) — 4 frames sans boîte de feu → sans-feu
  (le faux « sans feu » se prouve comme un vert) ; toute boîte réapparue
  rebascule aussitôt côté feu (direction sûre) ;
- **verdict** : `NoLightAnnouncer` (core) — 2 frames stables avant de parler,
  redit toutes les 2 s dans les deux sens ; « Attendez. » coupe le délai
  après un « Traversez. » (miroir du rouge après vert) ; la voix véhicules
  recule le verdict (pas de doublon avec la phrase prioritaire) ;
- **danger** : veto trafic OU véhicule brut en mouvement — même en cooldown
  voix, jamais « Traversez. » sur un doute ;
- **câblage** : `crossingPatchOf` tait le feu et publie rouge (aucun vert lu —
  pastille, bouton et vibrations inchangés), `crossingPhraseOf` coupe le scan
  et parle véhicules d'abord (`NoLightCrossWatch`, reset avec les autres voix) ;
- **limites** : un feu cadré hors champ 4 frames passe en sans-feu —
  viser le feu quand il y en a un ; écran Direct inchangé (fail-closed :
  « attendez » sans vert).

## 14. Sans feu : le mouvement se dit à toute portée (31/10)

Ordre user : sans feu, une voiture en mouvement se dit **dès qu'elle est
détectée, même au-delà de 15 m** (« Car moving. » — la portée, pas la
position : depuis le 01/11 un mobile n'est plus situé, § 32 de
`VOIX-DANGER.md`). Seul le mouvement est illimité : l'arrêt garde 15 m (une voiture garée loin ne
bloque pas le « Traversez. »), et avec feu / en mode normal la portée reste
15 m.

- **noyau** : `VehicleStateWatcher.update` prend `movingMeters` (défaut
  `MOVING_METERS`) — la portée ne touche que le mouvement, jamais l'arrêt ;
- **câblage** : `CrossingVehicleWatch.update(noLight = true)` passe l'infini
  (branche sans-feu seule) ; le brut `movingNow` qui tait le verdict suit la
  même portée — au-delà de 15 m, « Attendez. » toutes les 2 s aussi ;
- **urgence inchangée** : loin = doux mais premier, proche ou non mesuré =
  « Stop, … » qui coupe tout (une voiture lointaine non mesurée reste dite
  comme proche, règle conservatrice existante) ;
- **transition feu→sans-feu** : le guetteur reste nourri sous quiet (seule
  la phrase est tue) — le verdict « Attendez. » ne rate aucun danger, même
  à plus de 15 m, dès la perte du feu.

## 15. Repli haut-de-poteau, veto tricolore et garée jamais bloquante (31/10)

### 15.1 Repli haut-de-poteau + veto tricolore

Le feu piéton est sur poteau (ordre user) : quand SAM rate le boîtier mais
voit le fût, la couleur se lit dans un carré ancré en haut du poteau
(`CrossingPrompts.selectPoleTopBox`, côté 3 largeurs de fût, plancher
24 px — serré comme la tête pour la garde bas de la porte). Sans lampe
allumée vue (`PedLightGate.hasLampSignal`), pas de boîte : la branche
sans-feu (`NoLightGate`) reste intacte. Le feu garde la priorité sur le
poteau ; `pole` rejoint les prompts traversée (sonde : déjà dans
`LivePrompts.default`).

Le prompt piéton capte aussi les gros feux voiture (même tête visuelle,
étiquette = wording du prompt) : `CarLightVeto` écarte les boîtes
piétonnes inscrites à moitié au moins dans un caisson voiture
(contenance, pas IoU — tailles disparates). **Veto inactif** : le wording
`vehicle traffic light` n'est envoyé nulle part tant que rappel voiture
et 0 capture piéton ne sont pas mesurés (~15 photos, `MESURES` §1) —
testable au champ « Follow » en attendant. Le carré poteau est vetoé
comme un feu.

### 15.2 Sans feu : une garée ne bloque jamais, même caméra en mouvement

Ordre user : seule une voiture **en mouvement** dit « Attendez. » — une
voiture garée (immobile) ne bloque jamais le « Traversez. », même proche,
même si l'user bouge/panote la caméra.

- **danger = mouvement brut seul** : le veto proximité (< 15 m) ne tait
  plus le verdict sans feu (champ `veto` du patch supprimé) — une garée
  proche se dit (« Voiture à l'arrêt… », info douce) sans bloquer ;
- **pan compensé** : le signal fond (`ProbeBackgroundMotion`, déjà utilisé
  en mode normal) juge les pistes avec feu comme sans feu — une boîte qui
  suit la caméra conclut `CAMERA_MOVED`, pas le mouvement (`null` = pas
  de signal : repli géométrie, comportement historique) ;
- **limites** : une trottinette/un vélo reste muet en traversée (politique
  cycles) ; avance franche vers une garée (zoom) : décote via
  `EgoCompensator`, à valider rue.

## 16. Réveil backend et déplois conditionnels (31/10)

Avant une prise : le backend dort quand personne ne s'en sert — `GET /warm`
le réveille SANS inférence (boot complet 25-38 s à froid, restauration
snapshot 2-7 s ensuite) et `/health` confirme la version servie (leviers
`batch_prompts/cuda_graph/edge:512/snapshot/attn:sdpa`, `vision_accel: ok`).
Depuis le 31/10 le déploiement CI est conditionnel (marqueur `[deploy-gpu]`
dans le message du push, `deploy-live-fast.yml`) : chaque deploy invalide le
snapshot, donc une prise qui suit un deploy de peu paie le boot complet —
prévoir le `/warm` dans le temps d'installation, pas dans la minute de prise.

## 17. Passage dit 6 m à l'avance, guidé dès 6 m (01/11)

Ordre user : quand un passage piéton est détecté, iSee le dit 6 m à
l'avance (« Passage piéton à gauche, à 6 mètres. ») et guide vers lui
(« Gauche. », « Droite. », « Tout droit. », puis « Sur le passage. »).
Avant, l'annonce parlait dès la détection, même à 10-20 m — trop tôt
pour s'aligner — et le guidage retombait au-delà de 3 m en Traversée.

- **annonce** : `CrossingApproachWatcher.ANNOUNCE_METERS = 6,0 m` — mesuré
  au-delà, silence (compte remis à zéro, annonce déjà dite gardée — dite
  une fois par vue) ; à 6 m ou moins, 2 frames puis la phrase localisée ;
  sans mesure, la direction seule parle comme avant (jamais de chiffre
  deviné) ;
- **guidage** : `CrossingGuidance.GUIDE_METERS = 6,0 m` (3,0 m le 31/10,
  §10) — en Traversée comme hors Traversée, les mots doux suivent le
  passage dès 6 m mesurés (ou boîte prouvée sous les pieds, fail-closed),
  sous la priorité du feu, du veto et des véhicules ;
- **preuves** : `CrossingApproachTest` (silence au-delà de 6 m, borne 6 m
  incluse, loin-puis-proche), `CrossingGuidanceTest` et
  `CrossingOfferTest` (bornes 6 m).
