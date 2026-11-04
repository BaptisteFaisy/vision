# Mode traversée — spec v4 (contrat actuel)

> **Spec v4, 01/11/2026** (niveaux revus le 02/11/2026 : verdict **100**,
> puis 101/102). Le mode traversée dit
> à une personne aveugle **quand traverser** : verdict exclusif (feu piéton
> puis voitures), niveaux **100** (verdict) puis 101/102, chat et GPS muets,
> dictée active.
> Historique pré-v4 (recherche 10 angles, `TraverseVeto`, hystérésis,
> voix « Vert, traversez. » / « Rouge, attendez. ») :
> `git show 4a3c2bcf:docs/TRAVERSEE.md`.

## 1. Le verdict (niveau 100)

Règle, dans cet ordre :

1. **Feu piéton rouge = « Don't cross. »**, quoi que fassent les voitures.
2. **Feu vert confirmé = « Cross. »**, même avec des voitures en mouvement
   (ordre user du 02/11/2026) ; **sans feu** : une voiture en mouvement ?
   **« Don't cross. »** : **« Cross. »** (le premier sans feu exige 3 s de
   calme, voir plus bas).
3. **Véhicules = voiture uniquement** (`car`, `cars`, `voiture`,
   `voitures`) : bus, vélo, camion, moto ne tranchent jamais.

- Clips : `verdict-dont-cross` « Don't cross. », `verdict-cross` « Cross. »
  (verdict : tirets, textes courts — tranche la contradiction du § 6).
- Répétition : **2 s, changement immédiat** (ordre user du 02/11/2026) —
  le premier verdict parle aussitôt, un changement parle aussitôt, un
  verdict maintenu se répète toutes les 2 s (clé `verdict` / `verdict:état`).
  `CrossingVerdictAnnouncer.REPEAT_MS = 2_000L` (`CrossingVerdict.kt`).
- **Phrase refusée = rejouée** : le verdict est posté au cran **100**, donc
  un mot d'obstacle dehors (2 à 12) le précède. `VoiceBus.post` rend un
  `Boolean` ; sur `false`, `CrossingVerdictAnnouncer.rearm` remet
  l'échéance de répétition à zéro et la phrase repart à la frame suivante
  (≤ 7 fois/s, `post` est `@Synchronized` ; A9 § 4.4). C'est cette protection — le
  ré-armement — qui remplace l'escalade de priorité : le cran 0 reste
  **sans producteur** (ordre user du 02/11/2026, voitures muettes dehors).
- Le verdict vibre (`IseeHaptics.crossing`) et s'affiche sur la pastille
  du chip (`Crossing: GREEN/RED`) — plus de cadre rouge/vert sur la vidéo
  (ordre user du 03/11/2026) : voix, vibration et pastille portent l'état
  confirmé, la vidéo reste celle d'avant.

## 2. Ordre exclusif : verdict > humains > bordures

| Niveau | Quoi | Règle |
|---|---|---|
| **100** | Verdict | § 1, devant les humains (101) et les bordures (102), jamais coupé par eux. Il passe **après** les mots d'obstacle dehors (2 à 12) et après la parole du user (1) : la protection contre l'écrasement est le **ré-armement** (§ 1, `VoiceBus.post` rend `false`, `rearm` rejoue), pas une escalade de priorité — décision du 02/11/2026, voitures muettes dehors et cran 0 sans producteur (A9 § 3 D8, § 4.4) |
| 101 | Humains du passage + alignement | Après le verdict (humains : même phrase qu'au niveau 3 : phrase + cadran + distance, imminent immédiat ; alignement passage : mot + annonce + arrivée, niveau 2 dehors, 101 en Traversée) |
| 102 | Bordures du passage | Après les humains (montée / descente) |

Chaque place `null` se tait (pas de remplissage : un verdict seul ne fait
pas parler les bordures). Les niveaux dehors (0 à 12) ne parlent pas en
traversée et leurs producteurs s'y taisent — sauf la parole du user (1),
qui **précède** le verdict (plus petit nombre = plus forte priorité, donc
1 passe avant 100) : aucun cran de la Traversée ne la coupe.

## 3. Exclusivité : chat et GPS muets, dictée active

- **Chat muet** : les réponses attendent (file du niveau 9) et reprennent à
  la sortie du mode, jamais perdues.
- **GPS muet** : ni manœuvres (11), ni rappels (4) tant que le mode est
  actif. La progression route se tait ; au rappel proche, le mode s'arme
  une seule fois (« Mode traversée activé. » reste en synthèse ? Non :
  clip `cross_on` « Crossing mode on. »).
- **Dictée active** : les 11 ordres « mode … » restent écoutés, `mode
  crossing off` compris.

## 4. Détection en traversée

- Base unique normal/traversée (20 dehors, `docs/VOIX-DANGER.md` § 7),
  sonde comme direct : la traversée ajoute les véhicules en tête et écarte
  poubelle, souvenirs, lampadaire (ordre user 03/11) — 20 envoyés,
  chien conservé. À la coupure, retour à la liste du monde.
  Le verdict lit le feu
  (`pedestrian traffic light`) et les voitures (`car`) ; les niveaux 101
  et 102 lisent `person` et `curb`.
- Couleur du feu : relue localement sur **chaque** frame caméra (le moteur
  du verdict est nourri par frame en Traversée, `LiveProbePublish.computeRest`) ;
  position : cadence SAM. Hystérésis gardée côté voix (le verdict ne bégaye pas).
- Mouvement voiture : suivi client (association IoU, vitesse lissée,
  ego-motion visuel : repères fixes § 20, fond à défaut) — une boîte qui suit
  la caméra (pan sur une voiture garée) n'est pas un mouvement. Une voiture
  **prouvée garée** (§ 19) sort du veto et du mouvement.

## 5. Proposition du mode (niveau 2)

Le passage piéton < 4 m (bord proche : sol d'abord, profondeur en repli) s'annonce (« Crosswalk. » devant — joué < 500 ms
redit toutes les 2 s hors Traversée — « Left. »/« Right. » sur les côtés,
1 fois par vue), l'alignement se dit **au changement** (« Aligned with the
crosswalk. »), l'arrivée **1 fois** (« On the crosswalk. »). La pastille
de carte propose le mode (« Passage piéton — traversée ? », ambre, un tap
= `crossing on`) ; un refus se tait une minute. Entrer en traversée ne se
fait jamais seul : seuls la puce et l'ordre vocal arment le mode.

## 6. Tranché par la revue v4 (à appliquer à l'intégration)

- **Texte et clips du verdict** : « Cross. » / « Don't cross. », ids
  `verdict-cross` / `verdict-dont-cross` (branche verdict). Le catalogue
  clips-en (`verdict_cross` / « Cross now. », `verdict_wait`) est aligné
  dessus à l'intégration — pas l'inverse (le verdict est la source).
- **Niveaux** (appliqué) : `CrossingLevels` (`:core`, 100/101/102) est
  l'unique source ; il n'y a pas de `CrossingLevel` côté `:app`, rien à
  replier.
- **Antirépétition du verdict** : clé `verdict:état` de la table centrale
  (2 s, changement immédiat) ; l'annonceur maison (`CrossingVerdictAnnouncer`)
  est replié dessus ou supprimé — pas deux mécanismes.
- **Ancien pipeline** (`TraverseVeto`, `TraversePipeline`, voix « Vert,
  traversez. ») : **décâblé et supprimé** (missions morts-* vides :
  l'intégrateur le fait). Tant qu'il vit, double voix FR + EN en traversée.
- **Veto vélos** (`CycleWatcher`, « Attention, vélo en mouvement ») :
  **supprimé** (vélo sorti de la spec) — il parle encore sur toutes les
  branches via `CrossingVehicleVoice` et `LiveVehicleVoice`.

## 18. Sans feu : « Traversez. » après 3 s sans voiture en mouvement (01/11)

Ordre user : en Traversée sans feu, « Traversez. » ne se dit que si
aucune voiture en mouvement n'a été détectée durant les 3 dernières
secondes. Avant, 2 frames calmes (~0,4 s) suffisaient — une voiture qui
venait de passer libérait le verdict aussitôt.

- **noyau** : `NoLightAnnouncer.CALM_MS = 3_000L` — le premier
  « Traversez. » exige 3 s de calme continu (mouvement brut seul, une
  garée ne rouvre jamais la fenêtre) ; tout danger la rouvre, et une
  phrase véhicules sur calme (« à l'arrêt ») recule le délai sans la
  rouvrir. « Attendez. » inchangé (2 frames, coupe le délai après un
  « Traversez. », redit toutes les 2 s comme le verdict) ;
- **avec feu** : le vert confirmé décide seul (« Cross. » même avec des
  voitures en mouvement) ; hors vert, le veto trafic s'applique comme
  avant — cette règle ne couvre que la branche sans-feu ;
- **preuves** : `NoLightCrossingTest` :core (silence avant 3 s, fenêtre
  rouverte par un danger, phrase calme qui ne rouvre pas). Le câblage
  :app (`NoLightCrossingVoice`, supprimé par la v4) n'est plus testé.

## 19. Garée prouvée sur fenêtre : « Cross. » devant des voitures garées (04/11)

Ordre user : sur la vidéo Samsung `20261104_101016` (2 vans garés, passage sans
feu, téléphone à la main), l'app disait « Don't cross. » parce qu'elle voyait une
voiture, alors qu'une voiture garée doit laisser dire « Cross. ».

- **Cause** : rejeu des boîtes SAM réelles dans le vrai code : « Don't cross. »
  44 frames sur 44, veto sur 43, « en mouvement » sur 11. La preuve d'arrêt du
  veto se jouait sur **une paire de frames** (dérive sous 2 %/s, portée à 0,5 m),
  sous le bruit réel : une garée filmée à la main tremble de 2 à 7 %/s d'une
  frame à l'autre. Jamais prouvée immobile, elle bloquait à vie sous 15 m. Le
  fond ne rattrapait rien : `BlockMotionSource` se tait sur les petits mouvements
  (181 frames sur 182 sans signal sur ces vidéos). Et SAM rend `van`, `truck` et
  `car` sur le même véhicule, avec des morceaux qui vont et viennent.
- **Mesure** (5 vidéos Samsung, boîtes réelles, frames 320 px) : sur 1 s, en
  médianes, une garée reste sous 0,06 image/s à 5 ips (0,09 au pire à 10 ips) ;
  une voiture qui roule dépasse 0,4. Plus d'un ordre de grandeur d'écart, là où la paire de frames n'en
  laissait aucun.
- **Fait** : `ParkedProof` (`:core`) suit les **véhicules physiques** sur une
  fenêtre glissante. Prouvée au bout de 600 ms et 4 vues : dérive ≤ 0,07 image/s,
  taille tenue. Ce qu'elle prouve sort du veto (`TraverseVeto`) et du mouvement du
  verdict (`CrossingVerdictEngine`, paramètre `parked`). Le guidage obstacles,
  lui, garde l'objet proche.
- **Sûreté** : la preuve **ne fait qu'exempter** ; tout ce qui n'est pas prouvé
  garde le veto d'avant. Ne se prouvent jamais : une piste jeune, une dérive
  au-dessus du seuil, une boîte qui grandit ou rétrécit, une boîte sans bord libre
  (voiture qui entre dans le cadre ou le remplit), une boîte fixe sous pan rapide
  (> 0,2 image/s). Une preuve tombe au-delà de 0,12 image/s (démarrage à
  2 m/s² à 10 m : moins de 1,2 s) ou dès que la taille sort du bruit sur 3 s
  (approche, même lente, sans se reprouver). Un deux-roues n'hérite jamais
  de la preuve du véhicule qu'il croise.
- **Limite assumée** : une voiture qui rampe (moins de ~1 m/s à 10 m, droit vers
  la caméra) lit comme garée au début ; au sens de la voix, elle ne roule pas.
  Une voiture à l'arrêt qui repart redevient « Don't cross. » avec moins d'une
  seconde de retard, là où le veto la bloquait d'office.
- **Faux feux** : le dos d'un panneau rond sortait en feu piéton (0,36 à 0,55) et
  relançait 8 frames de « Don't cross. ». `SignLightVeto` écarte une boîte de feu
  faible (< 0,6), inscrite dans un `sign` mieux noté, **sans lampe allumée** : une
  lampe vue l'emporte toujours. Aucune frame de vrai feu perdue sur 186.
- **Preuves** : `ParkedProofTest`, `TraverseVetoParkedTest`, `SignLightVetoTest`
  (crops réels) et `CrossingParkedVideoTest` (vidéos réelles dans le vrai moteur :
  garées → « Cross. », voiture qui passe ou traverse au loin → « Don't cross. »).
  Fixtures : `app/src/test/resources/real-motion/samsung-*.json`.

## 20. Balayage : « Cross. » même si le téléphone bouge (04/11)

Ordre user : « même si je balaye cela me dise cross et si une voiture bouge don't
cross », léger. Après le § 19, un simple balayage faisait dire « Don't cross. »
devant des garées.

- **Cause** : `ParkedProof` mesurait la dérive dans l'image. À 0,1 image/s (~4°/s),
  une garée dépasse le seuil de preuve, et `BlockMotionSource` se tait à cette
  vitesse. Le label `car` passait aussi « en mouvement » par le latéral brut.
- **Fait** : `AnchorMotion` (`:core`) lit le mouvement de la caméra sur les objets
  fixes que SAM rend déjà (poteau, panneau, feu, mur, bordure…) : médiane des
  bords libres, 3 repères d'accord au moins. Aucun capteur (décision 100 %
  visuelle, gyro retiré le 02/11), aucun pixel. `ParkedProof` juge alors dans le
  repère de la rue, et le veto comme le verdict reçoivent ce mouvement à la place
  du fond (`TraverseVeto.cameraMotion`).
- **Mesure** (vidéo Samsung `20261104_101016` recadrée, 20 prompts, sans feu,
  39 frames après la preuve) : « Cross. » 25 → 39 téléphone à la main, 13 → 37 à
  0,10 image/s, 3 → 37 à 0,20 image/s ; « en mouvement » tombe à zéro.
- **Sûreté** : un véhicule, ou un objet posé sur un véhicule, n'est jamais un
  repère : une file qui roule ne passe pas pour un balayage. Sans accord en
  largeur, tout retombe sur le brut d'avant. Une voiture qui roule garde sa
  vitesse dans le repère de la rue, même filmée en la suivant. Une preuve sans
  fenêtre jugeable expire après 1 s (sous 3 ips, rien ne la rejugeait).
- **Sortie du cadre** : une preuve reste 5 s à sa place dans la rue tant que les
  repères tiennent ; le véhicule qui y revient la reprend pour 1 s, le temps
  d'être rejugé. Une boîte coupée qui grandit ne bloque pas si son bord libre
  suit la caméra ; une voiture qui entre plus vite garde son veto.
- **Limites assumées** : une voiture jamais vue qui entre dans le cadre dit
  « Don't cross. » jusqu'à sa preuve (600 ms). En marchant, rien ne change : la
  parallaxe n'a pas de repère commun. Une voiture sous ~0,07 image/s dans la rue
  (0,5 m/s à 10 m) lit comme garée, comme au § 19. Sous balayage, une voiture très
  rapide peut laisser une frame sans veto quand ses boîtes ne se recouvrent plus
  (déjà vrai avant ce correctif).
- **Preuves** : `AnchorMotionTest`, `ParkedProofTest`, `TraverseVetoParkedTest`,
  `CrossingSweepVideoTest`. Fixtures `real-motion/samsung-balayage-*.json`. Sur
  `rue-arret-*.json`, des taxis à l'arrêt filmés par une caméra qui tourne : le
  mouvement mesuré est celui du balayage injecté, à ±0,002 image près.

## 21. Feux fantômes : plus de « Don't cross. » sans feu (04/11)

Terrain du 04/11, passage **sans aucun feu** (confirmé par le user), build
`1.0.1926` : 27 « Don't cross. » en moins de 2 minutes. Sur les 14 lignes
`verdict` bloquées, 7 viennent de `feu=Unknown` ou `feu=Red`.

- **Cause** : SAM sort « pedestrian traffic light » sur des morceaux de rue, et
  `selectLightBoxes` n'a pas de plancher de score. Une seule frame disait
  « Don't cross. » puis relançait 8 frames de doute (`LightEvidenceGate`).
- **Mesure** : sans feu, ces boîtes tiennent 2 frames au plus (scores 0,36 à
  0,67). Un vrai feu est là sur 93 frames sur 93.
- **Fait** : `LightGhostGate` (`:core`) exige 3 frames de suite d'une boîte sans
  lampe lue, avant la porte de preuve. La frame refusée se lit comme une frame
  sans boîte.
- **Sûreté** : une lampe lue (`redProven` ou vert) compte tout de suite. Un feu
  confirmé reparle en une frame, jusqu'à 8 frames sans boîte (l'asymétrie sûre du
  03/11 est gardée). Coût : un vrai feu dont la lampe n'est pas lue bloque au bout
  de 3 frames (~600 ms) au lieu d'une.
- **Reste à mesurer** : l'autre moitié des blocages du même terrain vient de
  `roule=true`, sans cause connue : les logs n'avaient pas les boîtes. La ligne
  debug `ISEE_CROSS` les donne maintenant à chaque frame bloquée.
- **Preuves** : `LightGhostGateTest`, `CrossingSweepVideoTest`.

