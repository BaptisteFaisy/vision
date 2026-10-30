# Voix danger — priorité, trottoir, décalage chiffré

> **Dépôt** : `BaptisteFaisy/vision` — **décision user du 28/10/2026**.
> **Nature du document** : décision produit + contrat. Le § 1 rappelle
> l'existant (chaque affirmation porte un `chemin:ligne` lu sur `master` =
> `611939f1`) ; les § 2 et § 3 sont **livrés le 28/10/2026** (branche
> `feat/voix-danger-impl`) — seuils et phrases restant à valider sur
> téléphone. Tout ce qui n'a pas été mesuré s'écrit **non mesuré**.

## 1. Priorité au danger (confirmé — déjà l'architecture)

L'info dite en premier est le **danger de collision** (proche **et** qui
s'approche), pas l'objet le plus proche brut : un piéton immobile à 2 m n'est
pas le danger, une voiture qui arrive vite à 15 m si. C'est déjà l'ordre de
parole du code :

1. **veto trafic** — un véhicule (score > 0,5) qui approche (boîte
   croissante) ou proche (< 15 m sténopé) force « attendez », même sur vert
   (`core/.../traverse/TraverseVeto.kt:224,227`) ; le suivi temporel force le
   veto sous 3 s avant collision (tau = largeur / vitesse,
   `core/.../traverse/VehicleApproach.kt:130`) ;
2. **guidage couloir bouché** — l'obstacle qui entre dans le couloir parle
   tout de suite (`urgent`, `core/.../live/GuidanceAnnouncer.kt`) ;
3. **positions** de la scène, puis narration — jamais avant un danger.

L'urgence saute le délai de 4 s (`AnnouncementGate.admit(urgent = true)`,
`core/.../live/AnnouncementGate.kt:40`) et coupe la lecture en cours
(`TraverseVoice.speak`, `core/.../traverse/TraverseVoice.kt:157`).

Les classes rue demandées (humains, voitures, vélos, trottinettes
électriques) sont déjà les concepts suivis par défaut : `person`, `car`,
`bicycle`, `electric scooter` (+ `pole`, `sidewalk`, `crosswalk`, `wall`,
`window`, `stairs`), plafond 16 = `PROMPT_LIMIT` serveur
(`core/.../live/LivePrompts.kt:28,39`,
`server/backend/tracking.py:53`). Rien à changer ici.

## 2. Trottoir et dénivelés — « levez la jambe » (livré le 28/10/2026)

**Règle** : un bord de trottoir (ou une marche, une différence de terrain)
détecté **dans le couloir marché à 3 m ou moins** déclenche une annonce
**urgente** (1 m le 28/10 → 3 m le 29/10, ordre user : 1 m = une seconde
pour réagir à pied). La bande basse suit (0,45) : à 3 m le bord tombe en
bande moyenne selon le tangage. Marcher *sur* le trottoir n'alerte plus
(plan vs bord, voir ci-dessous).

- FR : `Trottoir, levez la jambe.`
- EN : `Curb, lift your foot.`

**Pourquoi c'est séparé du guidage** : un trottoir ne bouche pas le couloir
au sens de `Guidance` (on ne le contourne pas, on le monte) — c'est le miroir
de « Escalier devant. » (`core/.../stairs/StairWatcher.kt:21-23`), pas une
consigne de côté.

**Distance** : les plans au sol (`stairs`, `sidewalk`, `crosswalk`, `pole`)
sont `DEPTH_ONLY` (`core/.../distance/KnownSizes.kt:88`) — aucune hauteur
typique n'est défendable, donc **jamais de sténopé**. La distance vient de la
branche profondeur embarquée (médiane par masque calibrée, `docs/DEPTH.md`)
ou du plan de sol si la pose est réglée.

**Garde-fous** (miroir `StairWatcher`) : confirmation sur N frames
consécutives (un positif isolé ne parle pas), long cooldown (un trottoir ne
bouge pas), `urgent = true` (coupe la phrase en cours, comme le couloir
bouché).

**Non tranché, à valider sur téléphone (non mesuré)** : les labels SAM réels
(`curb` ? `step` ? — `sidewalk` seul ne dit pas le bord), montée vs descente
(deux phrases ? — la géométrie les distingue, une seule phrase), fiabilité
de la profondeur à moins d'1 m, et l'extension aux autres différences de
terrain (pente, rampe, gravier — détection **non définie** à ce stade).
Tranché le 29/10 : seuil 3 m (ordre user), plan sous les pieds ignoré
(montee/descente par géométrie de boîte, seuils 0,95/0,5 priors).

**Plan vs bord (29/10)** : pour les labels de plan (`sidewalk`…) — montée
= bas de boîte décollé des pieds (< 0,95), descente = bas aux pieds mais
haut borné (≥ 0,5), sinon sol ignoré ; labels de bord (`curb`…) : règle
inchangée (`CurbSignal.nearEdge`, `core/.../curb/CurbWatcher.kt`). Seuils
priors à valider dehors : en cas de doute ça parle, jamais l'inverse.

## 3. Décalage latéral chiffré en mètres (livré le 28/10/2026 — REMPLACÉ par le § 5 le 29/10, gardé pour mémoire)

**Règle** : une consigne de côté ne dit plus seulement le côté, elle dit
**de combien se décaler**, pour être précis :

- Aujourd'hui : `Personne à 2 mètres à midi, va à gauche.`
  (`core/.../live/Guidance.kt:80-92`).
- Demain : `Personne à 2 mètres à midi, décalez-vous de 1 mètre sur la gauche.`
  (EN : `Person 2 meters at 12 o'clock, move 1 meter to the left.`)

**Définition** : le décalage = la distance latérale à franchir pour sortir du
couloir bloqué vers le passage conseillé, **arrondie au demi-mètre
supérieur**, avec une marge d'épaule. Le cadran et la distance de l'obstacle
sont inchangés ; seule la consigne devient chiffrée. La signature
anti-répétition (`Guidance.key`, `core/.../live/Guidance.kt:103`) inclut le
palier annoncé — si le décalage change, ça reparle.

**Non tranché, à valider sur téléphone (non mesuré)** : les paliers (0,5 m ?
1 m ?), la marge d'épaule, et la géométrie latérale (centre + distance →
mètres : à câbler sur `core/.../distance/`, pas à deviner dans la phrase).

## 4. Coutures livrées

- § 2 : `core/.../curb/` (`CurbConcepts`, `CurbWatcher`, miroir de
  `StairWatcher` : 3 confirmations, cooldown 10 s, phrase urgente), signal lu
  dans les lectures affinées (profondeur dès qu'une ancre fixe l'échelle) ;
  câblé dans `ProbeWiring.observeCurb` (zéro ligne dans `LiveProbe`, 499/500),
  voix via `ProximityVoice` urgent (coupe, miroir `TraverseVoice`) ; coupé en
  mode Traversée.
- § 3 : `Guidance.decide` calcule `shiftMeters` (demi-couloir + marge d'épaule
  0,3 m, demi-mètre supérieur, focale 67° par défaut), `Guidance.phrase` dit
  « décalez-vous de X vers … », `Guidance.key` porte le palier ; distance
  inconnue = consigne legacy non chiffrée.
- Recette téléphone : trottoir réel à 1 m → phrase urgente ≤ 1 s après
  confirmation ; obstacle latéral → décalage annoncé puis vérifié au pas —
  **non mesuré**, exige un APK de cet arbre sur appareil réel.

## 5. Vocabulaire minimal + stop + escaliers parlants (décision du 29/10)

Le 29/10, le user marche avec l'app et tranche l'inverse du § 3 : en marchant,
**chaque mot retarde le « stop »** — la consigne chiffrée est trop lente quand on
fonce sur quelqu'un. Nouveau contrat, qui **remplace** le § 3 :

- vocabulaire minimal : `Continue.` / `Va à gauche, 2 mètres.` /
  `Va à droite, 2 mètres.` / `Stop.` (en : `Continue.` / `Go left, 2 meters.` /
  `Go right, 2 meters.` / `Stop.`) — côté + distance de l'obstacle au mètre
  entier, ni nom d'obstacle, ni cadran, ni décalage latéral (`Guidance.phrase`,
  `DistanceText.spokenShort`) — le « Stop. » est **remplacé**, voir § 9 ;
- **stop** (remplacé, voir § 9) : obstacle mesuré à `STOP_METERS = 0,6 m`
  ou moins ⇒ « Stop. », urgent (coupe la phrase en cours, chat compris) ;
- **positions** : le plus proche d'abord, puis le plus en face (distance, puis
  écart à l'axe), et la distance au mètre entier remplace le cadran
  (`PositionText`) : `Personne à 1 mètre, chien à 3 mètres.` ;
- **escaliers parlants** : les libellés SAM « stairs » dans les lectures sont
  annoncés en info urgente (`observeStairs`, miroir de `observeCurb`, zéro ligne
  dans `LiveProbe`) — `Escalier montant.` / `Escalier descendant.` /
  `Escalier devant.` (sens inconnu). Le sens vient des mots du libellé
  (« stairs going up », exact) sinon de la hauteur de la boîte (escalier cadré
  net au-dessus du bas = montée, `StairLabelSignals`) ; le Canny seul ne voit
  pas le sens et le gradient de profondeur embarqué reste le successeur prévu.
  Un escalier ne bloque jamais le couloir : c'est une info, pas une consigne
  d'évitement.

Contrat détaillé : `docs/GUIDAGE-TOUT-DROIT.md`.

## 6. Réactivité maximale (décision du 29/10)

Le 29/10, le user marche avec l'app : elle est trop lente — devant une personne
sur qui on fonce, elle ne dit parfois **rien**. Cause : la clé de stabilité
contient le seau de distance, donc un obstacle qui s'approche change de clé à
chaque frame et n'est jamais « stable » ; et les synthèses se suivaient en file,
une consigne datée passant devant la fraîche. Nouveau contrat (le user accepte
que la voix se coupe et parle souvent) :

- **danger dès la 1re frame** : un obstacle qui entre dans le couloir parle
  aussitôt, sans stabilité ni délai (`firstSight`, `AnnouncementGate`) ; un
  obstacle qui s'approche reparle à chaque seau (`3 m → 2 m → 1 m`, puis
  centimètres sous un mètre — le « → Stop. » est **remplacé**, voir § 9) ;
  la nouveauté reste (une clé déjà dite ne reparle pas) ;
- **dernier-gagnant** : une phrase plus fraîche annule la synthèse en cours
  (`collectLatest` côté sonde) au lieu de faire la queue derrière ;
- **« Stop. » pré-synthétisé** (remplacé, voir § 9) : clip en mémoire
  (`ProximityVoice.prewarmStop`), zéro réseau au moment critique ;
- **l'urgent coupe tout**, dictée comprise (`canPlay` ne s'applique qu'aux
  phrases ordinaires) ;
- une scène de positions nouvelle parle aussi dès la 1re frame (délai 4 s
  gardé, qui borne le bavardage) ; les escaliers voix confirment en 2 frames.

## 7. Matière proche non mesurée + voix qui finit ses phrases (fix du 29/10)

Le user marche avec l'app : elle ne dit plus les obstacles, même en approchant.
Deux causes, lues dans le code de cet arbre :

- **la même phrase ré-émise à chaque frame annulait sa propre synthèse** :
  `state` est publié à chaque frame et `collectLatest` annulait le bloc en
  cours — synthèse lente + frames continues = silence total. Nouveau contrat :
  seule une phrase **plus fraîche** annule (`distinctUntilChanged` sur
  `spoken`, `ProbeWiring.observeSpoken`) ; les guetteurs trottoir/escaliers,
  rares et confirmés, restent séquentiels (`collect`) : une alerte confirmée
  ne s'annule pas ;
- **la matière au contact était non mesurée donc tue après un mot** : boîte
  coupée ⇒ pas de distance ⇒ clé `?` constante ⇒ nouveauté muette, et jamais
  de « Stop. » (il exigeait une mesure). Nouveau contrat (le « Stop. »
  immédiat est **remplacé**, voir § 9) : matière non mesurée qui remplit le
  champ en face (`STOP_HEIGHT_FRAC = 0,5`, `STOP_WIDTH_FRAC = 0,05`,
  `Guidance`) ⇒ « Stop. » immédiat — sauf peinture au sol (`crosswalk`),
  et un fil vertical lointain n'arrête pas (largeur) ;
- **le « Stop. » du guidage passe en urgent** (remplacé, voir § 9), comme
  l'annonçait le § 5 sans que le code le fasse : clip pré-synthétisé, coupe
  le chat (`ProbeWiring.observeSpoken`, `urgent = phrase == STOP_WORD`).

Verrouillé par `ProbeWiringSpokenTest` (3, sonde branchée sur le faux client :
même phrase ré-émise → une synthèse qui aboutit, phrase fraîche → dernier
gagnant, Stop → parlé chat en lecture) et `GuidanceTest` (22).

## 8. TwinLite se tait après une alerte danger (ordre user du 29/10)

> **Supprimé le 29/10** (guidage SAM seul, ordre user) : TwinLiteNet viré,
> `mutesTwinLite` et la fenêtre de silence 10 s n'existent plus. Ci-dessous
> l'historique.

TwinLiteNet ne voit que le sol praticable (gauche/droit/tout droit/stop) ;
trottoir et escaliers sont des dangers nommés par SAM et parlent en
prioritaires (`urgent`, coupe). Sans garde, la consigne sol suivante
(~2-4 s) coupait l'alerte en pleine phrase (un seul lecteur : dernier
gagnant).

Nouveau contrat : les 10 s qui suivent une alerte trottoir/escaliers, la
voix TwinLite se tait — la flèche reste affichée, la voix reprend ensuite
sans consigne en retard. La fenêtre couvre les cooldowns des guetteurs
(10 s) : danger persistant = alertes chaînées = silence TwinLite continu.

`ProbeHazards.mutesTwinLite` (`app/.../isee/ProbeWiring.kt`), branché sur
`TwinLiteWiring(muted)` par le ViewModel ; `lastAlertMs` exposé par
`CurbWatcher`/`StairWatcher` (pur, horloge explicite).

Verrouillé par `ProbeHazardsTest` (5 : silence initial, trottoir,
escaliers, expiration, reset) et `TwinLiteWiringTest` (+2 : voix tue +
flèche affichée, reprise sans alerte).

## 9. Contournement chiffré remplace le « Stop. » (ordre user du 29/10 — REMPLACÉ par le § 10, gardé pour mémoire)

Le user marche avec l'app : le « Stop. » en boucle devant un obstacle ne dit
pas où passer. Nouveau contrat, qui **remplace** le stop des § 5, § 6 et § 7
(gardés pour mémoire) : un obstacle, même au contact, se contourne — la voix
dit le côté et la distance (`Va à gauche, 1 mètre.`, `Va à droite,
40 centimètres.`), jamais d'ordre d'arrêt.

- **guidage** : `Guidance.phrase` ne connaît plus que `Continue.` et le
  contournement chiffré (`DistanceText.spokenShort` : mètre entier,
  centimètres sous un mètre, non chiffré sans mesure) ; `STOP_METERS`,
  `STOP_HEIGHT_FRAC`, `STOP_WIDTH_FRAC`, le champ `stop` de la décision et le
  marqueur `|stop` de la signature disparaissent, avec `LabelExtent`
  (l'emprise de boîte, introduite pour le stop, ne servait qu'à lui) ;
- **urgence gardée** : le contournement coupe la lecture en cours, chat
  compris — le flag vient de l'état (`LiveProbeState.spokenUrgent`, publié
  avec la phrase dans la même écriture), plus d'un mot magique
  (`ProximityVoice.STOP_WORD` et le clip pré-synthétisé disparaissent) ;
- **traversée inchangée** : « Stop, mur » (feu rouge) n'est pas un obstacle,
  il reste tel quel.

Contrat détaillé : `docs/GUIDAGE-TOUT-DROIT.md`.

Verrouillé par `GuidanceTest` (18), `GuidanceAnnouncerTest` (12),
`LiveProbeGuidanceTest` (4, dont l'urgence du contournement seul) et
`ProbeWiringSpokenTest` (3 : contournement parlé chat en lecture).

## 10. Quatre mots en moins de 100 ms (ordre user du 29/10)

Le user marche avec l'app : pas assez d'infos par seconde, il en veut 5
minimum — et le contournement chiffré du § 9 (remplacé, gardé pour mémoire)
est trop lent à dire. Nouveau contrat : la voix du guidage ne dit qu'un mot
par changement de consigne, **en moins de 100 ms** (décision → premier son) —
voix TwinLite quand c'est bouché, mots SAM sur voie libre (§ 12).

- **mots** : `GuidanceWord` + `TwinLiteWord` (core pur) — voie libre ⇒ cap
  SAM, bouché ⇒ côté de sortie TwinLite, contact mesuré (≤ 0,6 m) ⇒
  « obstacle, arrêtez-vous. », décalage et retour trottoir ⇒ côté SAM ;
  sans mesure, le mot reste le côté (on ne juge pas le contact sans
  distance). FR/EN, langue de la voix ;
- **déclenchement** : mot nouveau ⇒ dit aussitôt, dès la 1re frame, sans
  délai ni stabilité (`GuidanceAnnouncer`, `firstSight` + urgent partout) ;
  même mot ⇒ silence (scène stable = pas de bégaiement). À 5 ips, un
  changement peut se dire à chaque frame ;
- **< 100 ms** : clips pré-synthétisés `guide_*` + `tl_*` (16 MP3 voix
  Laure, `tools/voicepack-catalog.json`, gardés par
  `tools/check-voicepack.py`), joués depuis le disque (`VoicePack` +
  `SpeechPlayer.playFile`, zéro réseau) ; voix non-Laure ⇒ synthèse mise en
  cache à la première occurrence (`ProximityVoice.speakWord`) ;
- **voix unique** : un seul parle à la fois (arbitrage § 12, jamais de
  doublon), les annonces de positions gardent la synthèse réseau
  (vocabulaire infini), les alertes trottoir/escaliers et la traversée
  (« Stop, mur ») sont inchangées.

Contrat détaillé : `docs/GUIDAGE-TOUT-DROIT.md`.

Verrouillé par `GuidanceTest` (18), `GuidanceWordTest` (6, dont
seul-stop-coupe), `TwinLiteWordTest` (9, dont stop-coupe FR/EN),
`GuidanceAnnouncerTest` (18, dont l'arbitrage),
`LiveProbeGuidanceTest` (5, mots doux + positions non urgents, stop interne
TwinLite au contact mesuré = urgent), `ProbeWiringSpokenTest` (3, mot doux
muet chat en lecture) et `ProximityVoiceWordTest` (4 : clip sans synthèse,
synthèse mise en cache, mot unique, urgence externe coupe le chat en clip).

## 11. Guidage doux + « Attention à … », stop seul urgent (ordre user du 29/10)

Le user recadre : iSee/SAM sert aux situations **non urgentes** (décrire, guider
en douceur), un **autre modèle** (en cours) gérera l'urgence. Nouveau contrat,
qui remplace l'urgence du § 10 (gardée pour mémoire) :

- **mots doux** : gauche/tout droit/droite ne coupent plus rien — dits aussitôt
  dès la 1re frame (clips < 100 ms inchangés), ils attendent le silence quand
  le chat lit (`LiveProbeState.spokenUrgent` = `GuidanceWord.isStopPhrase`,
  + `TwinLiteWord.isStopPhrase` depuis § 12) ;
- **« Attention à … »** : trottoir (« Attention à la marche. », ex-« levez la
  jambe »), escaliers (« Escalier montant. », inchangé) et mobilier suivi
  (« Attention à la barrière. », `HazardConcepts` : barrière, poteau, borne,
  clôture, portail — FR/EN, 3 m, 2 frames, 10 s de silence) parlent en douceur
  (`urgent = false`, synthèse réseau, jamais de coupure) ;
- **stop gardé en attendant** : le stop interne (contact mesuré ≤ 0,6 m,
  « obstacle, arrêtez-vous. » TwinLite depuis § 12) reste le seul urgent
  (coupe tout, chat compris) jusqu'au branchement du modèle externe —
  « Stop. » SAM pour l'urgence externe, pas de conflit
  (`docs/URGENCE-EXTERNE.md`).

Contrat détaillé : `docs/GUIDAGE-TOUT-DROIT.md` (§ 4 : seul le stop coupe).

Verrouillé par `GuidanceWordTest` (6, dont seul-stop-coupe),
`HazardConceptsTest` (5), `HazardWatchTest` (8), `LiveProbeGuidanceTest` (5,
mots doux + positions non urgents, stop interne TwinLite au contact mesuré
= urgent), `ProbeWiringSpokenTest` (3, mot doux muet chat en lecture),
`ProximityVoiceWordTest` (4, dont urgence externe coupe le chat en clip) et
`ProbeHazardsTest` (3, reset trottoir + escaliers + mobilier).

## 12. Les « Attention … » disent l'heure du cadran (ordre user du 29/10)

Les infos précisent les heures, au cadran des aveugles : « Attention à la
barrière, à 2 heures. », « Attention à la marche, à midi. », « Escalier
montant, à 11 heures. » (EN : « …, at 2 o'clock. »).

- **une seule règle** : `PositionText.withHour` (base + heure, `null` =
  sans heure — jamais d'heure devinée : le flux Canny, sans géométrie,
  garde « Escalier devant. ») ;
- **signaux porteurs** : `HazardSignal`, `CurbSignal`, `StairLabelSignal`
  portent le centre normalisé (lus dans la boîte retenue), les guetteurs le
  passe à la phrase ; les positions l'avaient déjà (inchangées).

Verrouillé par `PositionTextTest` (+2 : cadran FR, EN + sans heure),
`HazardWatchTest` (9), `CurbWatcherTest` (19), `StairWatcherTest` (8) et
`StairLabelSignalTest` (5, centre retenu).

## 13. Arbitrage voix : obstacle → TwinLite, sinon SAM (ordre user du 29/10)

Complémentaire du § 12 (qui fixe le *texte* des « Attention … », heure du
cadran incluse) : ici on fixe *qui* parle. Le guidage SAM seul (§ 8 :
TwinLiteNet viré) parlait le côté de sortie et « Stop. » d'une seule voix.
Nouvel ordre : **si obstacle → voix TwinLite, sinon → mots SAM** — un seul
parle à la fois, jamais de doublon. Le guidage doux (§ 11) s'applique aux
deux voix : seul le stop coupe.

- **bouché** : `TwinLiteWord` — contact mesuré (≤ 0,6 m) ⇒ « obstacle,
  arrêtez-vous. » / « obstacle, stop. » (seul urgent : `isStopPhrase`, coupe
  tout), sinon côté de sortie TwinLite (« gauche. », « droite. »,
  « tout droit. », doux). Textes et clips `tl_*` restaurés du chemin
  voix-obstacle viré (`d13ac26a`, PR #306 : `TwinLiteText` +
  `TwinLiteVoice.idFor` lus dans `7d133f3f`) — sans la segmentation
  (ni LiteRT, ni masque : la décision bouché/libre reste 100 % SAM) ;
- **libre** : `GuidanceWord` inchangé (cap, décalage, retour trottoir, doux) ;
- **arbitre** : `GuidanceAnnouncer` (une seule porte `AnnouncementGate`,
  clés `tl|*` séparées des `w|*`) — scène stable = silence des deux côtés ;
  le câblage (`ProbeWiring.observeSpoken`) route les phrases TwinLite vers
  `speakWord` (clips < 100 ms) comme les mots SAM.

## 14. Le trottoir nommé : « Trottoir à gauche. » (ordre user du 29/10)

Le guidage ordonnait le retour (« Gauche. ») sans jamais nommer le trottoir.
Nouveau guetteur (`SidewalkWatcher`, miroir bordure/marches/mobilier) : la
position lue dans la voix guidée (`ProbeVoice.lastSidewalk` — rien dans
l'état ne porte le plan du trottoir, non mesuré), deux frames pour confirmer,
puis annonce douce au changement seul (« Trottoir devant. », « Trottoir à
gauche. », « Trottoir à droite. » / EN « Sidewalk ahead/on the left/on the
right », synthèse réseau, jamais de coupure). Stable = silence ; absent ou
guidage coupé = silence (la voix des positions nomme déjà le trottoir en
mode annonce). Coupé en mode Traversée. Câblé en 4e guetteur
(`ProbeWiring.observeSidewalk`, `ProbeHazards.sidewalk`, reset commun).

Verrouillé par `SidewalkWatcherTest` (8) et `ProbeHazardsTest` (+1 : reset
trottoir nommé).

## 15. Filtre tête des panneaux : enseignes tues, panneaux bas gardés

Une rue commerçante, c'est dix enseignes par vitrine : chacune au centre
bloquait le couloir et la voix bavardait à chaque pas. Nouveau contrat :
un panneau haut ne se percute pas — on passe dessous — donc il sort du
couloir ; un panneau à hauteur d'homme reste un obstacle, annoncé comme
les autres.

- **règle** : `SignConcepts.isHighSign` (concept panneau FR/EN : sign,
  panneau, enseigne, affiche… — casse, accents, pluriel simple
  neutralisés — plus les composés du backend reconnus au mot :
  « traffic sign », « stop sign », « no entry sign ») : centre
  au-dessus de `HEAD_TOP` (0,22, exclu) et mesuré
  dans `HEAD_RANGE_M` (4,0 m) ⇒ ignoré par `Guidance.decide` (même filtre
  que le sol marchable) ;
- **prudence gardée** : distance inconnue ou non finie ⇒ panneau gardé —
  le filtre ne statue que sur du mesuré (« panneau »/« sign » sont au
  sténopé, 2,20 m, `KnownSizes`) ; un non-panneau n'est jamais filtré,
  même haut et proche ;
- **seuils provisoires** : 0,22 / 4,0 m sont des valeurs de départ, pas
  des mesures — la calibration téléphone ci-dessous les fixera.

Verrouillé par `SignConceptsTest` (10 : reco FR/EN, frontières `HEAD_TOP`
et `HEAD_RANGE_M`, prudence distance inconnue, composés backend, `Guidance.decide`
ignore le panneau haut et bloque sur le panneau bas) ; `*Guidance*` +
`*SignConcepts*` verts en JVM (102 tests, 0 échec, relevé du 29/10/2026).

### Calibration téléphone (protocole — corpus en attente, non mesuré)

But : faire des deux seuils une règle calibrée. Filmer avec le preset
Extérieur (suit « sign »), téléphone tenu à hauteur de poitrine, tangage
neutre, et relever pour chaque détection panneau : `centerY` normalisé
(0 = haut de l'image), `meters` (sténopé ou profondeur), annoncé ou non.

1. **corpus A** : 10 panneaux à hauteur d'homme (affiches basses,
   panneaux d'info, pub à hauteur des yeux) — attendu : 100 % annoncés
   à < 3 m ;
2. **corpus B** : 10 panneaux routiers ou pubs hautes (enseignes de
   vitrine, mâts, 4×3) — attendu : 0 annonce (0 bavardage) ;
3. **réglage** : choisir `HEAD_TOP` entre le plus haut des A et le plus
   bas des B (tous les A sous le seuil, tous les B au-dessus) ;
   `HEAD_RANGE_M` = portée maximale où la séparation tient ;
4. **report** : remplir les tables ci-dessous, mettre à jour les
   constantes + les tests de frontière (`SignConceptsTest`), dater le
   relevé ici — aucun seuil ne change sans son corpus.

Corpus A — panneaux à hauteur d'homme (non mesuré) :

| # | lieu | panneau | centerY | meters | annoncé ? |
|---|------|---------|---------|--------|-----------|
| 1 | | | | | |
| 2 | | | | | |
| 3 | | | | | |
| 4 | | | | | |
| 5 | | | | | |
| 6 | | | | | |
| 7 | | | | | |
| 8 | | | | | |
| 9 | | | | | |
| 10 | | | | | |

Corpus B — panneaux routiers / pubs hautes (non mesuré) :

| # | lieu | panneau | centerY | meters | annoncé ? |
|---|------|---------|---------|--------|-----------|
| 1 | | | | | |
| 2 | | | | | |
| 3 | | | | | |
| 4 | | | | | |
| 5 | | | | | |
| 6 | | | | | |
| 7 | | | | | |
| 8 | | | | | |
| 9 | | | | | |
| 10 | | | | | |

## 16. Portée de la voix : 4 m, véhicules dits jusqu'à 10 m (V1, 30/10/2026)

Au-delà de 4 m, la voix se tait — sauf pour les véhicules, dits jusqu'à
10 m. Nouveau contrat (interprétation B : liste stricte, seuils francs) :

- **mentions informatives** (positions au cadran, sonde iSee et direct) :
  à 4 m ou moins (`Guidance.RANGE_METERS`), tout se dit (inchangé) ;
  au-delà et sous `MENTION_RANGE_M = 10 m`
  (`core/.../live/VehicleConcepts.kt`), seuls les véhicules :
  `car`, `bicycle`, `electric scooter` + français « voiture », « vélo »
  (casse, accents, pluriel simple neutralisés, miroir `HazardConcepts`) ;
  à 10 m et plus, silence total ;
- **distance inconnue : dite** (prudence, inchangé — comme `Guidance`,
  qui bloque sur l'inconnu) : photo, chasse picto et objets non mesurés
  parlent comme avant ;
- **guidage, traversée, feu, urgent inchangés** : le couloir bloque
  toujours à 4 m (`Guidance.decide` intouché), les guetteurs (trottoir,
  escaliers, mobilier ≤ 3 m) et les alertes gardent leurs seuils — seuls
  les véhicules lointains gagnent une mention anticipée.

Décisions (interprétation B) : `electric scooter` inclus dans les
véhicules (mobile rapide, concept suivi par défaut) ; camions, bus,
motos et « trottinette » seule exclus (hors concepts `bicycle`/`car`,
liste stricte — à élargir sur ordre user) ; seuils francs (≤ 4 m dit,
< 10 m mentionné, ≥ 10 m tu).

Verrouillé par `VehicleConceptsTest` (7 : véhicules 4-10 m, FR/casse/
pluriel, non-véhicules tus, ≤ 4 m, ≥ 10 m, inconnu, liste),
`ProbeVoiceTest` (+3 : voiture 7 m dite et personne 6 m tue, voiture
10 m tue, 4 m inchangé) et `LivePositionVoiceTest` (+1 : voiture 7 m
dite, personne 6 m tue).
