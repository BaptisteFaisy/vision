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
  jambe »), escaliers (« Escalier montant. », inchangé), personne proche
  (« Attention à la personne, passez à droite. », `HazardConcepts` :
  personne, barrière, borne, clôture, portail — FR/EN, 4 m la personne,
  3 m le reste, 2 frames, 10 s de silence ; le poteau ne parle plus, § 26)
  et mobilier suivi parlent en douceur (`urgent = false`, synthèse réseau,
  jamais de coupure) ;
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

## 16. Alertes sécurité immédiates : voiture en mouvement + rebord (ordre user du 30/10)

Le user tranche : quand une voiture en mouvement est détectée, ou qu'un
rebord est détecté, iSee le dit **aussitôt, à l'oral** — même si une autre
info est en train d'être dite, même si le user parle. Dérogation au § 11
(douceur) pour ces deux seuls cas : le reste (arrêt, bateau, mobilier,
escaliers, cycles) parle toujours en douceur.

- **phrases** (langue de la voix, anglais par défaut) :
  - mouvement : `Stop, car moving.` / `Stop, voiture en mouvement.` (nom du
    véhicule : bus, camion, moto — `VehicleStateText.safetyPhrase`),
    Traversée seule depuis le § 22 (plus d'alerte en mode normal) ;
  - rebord imminent : `Montez le rebord.` / `Descendez le rebord.`
    (`Step up.` / `Step down.` — `CurbText.safetyStepPhrase`, plus de
    « Stop » depuis le § 22) ;
    la pré-alerte à 1 m est douce depuis le § 19 (plus d'alerte immédiate) ;
- **canal** : voix de sécurité dédiée (`SafetyVoice`, TTS système
  hors-ligne) — pas de synthèse réseau, pas de clé exigée, parle sans
  condition de silence ; coupe la lecture ElevenLabs en cours avant de
  parler, et la nouvelle alerte coupe l'alerte en cours (flush, § 20) ;
- **survit à la dictée** : moteur séparé du lecteur ElevenLabs et de la
  voix route — la tenue/transcription du user et `silenceForGlasses` ne
  taisent pas une alerte ; l'état de parole n'est pas touché (le TTS
  système ne rend pas de fin, un fanion sans fin bloquerait la dictée) ;
- **gardes gardées** : confirmation 2 frames (un positif isolé ne crie
  jamais « Stop »), cooldowns des guetteurs (4 s mouvement, 10 s trottoir,
  ré-armements § 19) ; le rebord imminent passe en Traversée, le reste
  y est coupé (sa voix rouge/vert est propriétaire du son) ; le bateau
  (« Bateau, pas de marche. ») et la voiture à l'arrêt restent doux ;
  depuis le § 22, le stop mouvement ne se dit qu'en Traversée
  (inversion : plus d'alerte `SafetyVoice` en normal) ;
- **supprimé** : la priorité stop > marche imminente (`ProbeVoice.stopRecent`,
  fenêtre 2 s) — une alerte de sécurité ne se tait pour rien.

Verrouillé par `VehicleStateTest` (+2 : stop mouvement EN/FR, arrêt muet),
`CurbWatcherTest` (+2 : imminent montant/descendant, pré-alerte parlée,
bateau muet), `SafetyVoiceTest` (4 : coupe + flush, alerte coupe l'ancienne,
blanc ignoré, locale), `ProbeWiringWatchersTest` (imminent passe toujours)
et `ProbeVoiceTest` (stopRecent supprimé, phrases gardées).

Recette téléphone : voiture qui roule cadrée → « Stop, car moving. »
coupe le chat en lecture (Traversée seule depuis le § 22) ; rebord sous
le pied en dictée → « Montez le rebord. » parlé quand même —
**non mesuré**, exige un APK de cet arbre sur appareil réel.
## 17. Véhicules : mouvement annoncé à 15 m, stop sous 8 m (ordre user du 30/10)

Le user veut entendre les voitures en mouvement jusqu'à 15 m, et en
Traversée savoir si elles roulent ou sont arrêtées — tout en anglais
(langue de la voix, défaut En comme avant).

- **portée** : le mouvement parle jusqu'à 15 m (`MOVING_METERS`, même
  `VehicleStateWatcher`, mêmes 2 frames, mêmes cooldowns) — en normal
  comme en Traversée (même watcher, mêmes phrases) ;
- **deux bandes** : proche (sous 8 m, `NEAR_MOVING_METERS`, ou non mesuré
  — jamais prouvé loin) = « Stop, car moving. » (Traversée seule depuis
  le § 22, urgent côté sonde) ; loin (8-15 m) = info douce « Car moving,
  12 meters away. » (voix ElevenLabs, attend le silence), en normal
  comme en Traversée ;
- **cooldowns** : le stop a son propre cooldown 4 s — une info lointaine
  ne fait jamais taire un stop qui suit ; à l'inverse une info ne reparle
  pas dans les 4 s d'un stop (le danger vient d'être dit) ;
- **Traversée** : `CrossingVehicleWatch` parle l'alerte « Stop, … » si
  le mouvement est proche, la phrase douce sinon (après le feu, avant
  les personnes) : le mouvement ≤ 15 m et l'arrêt ≤ 3 m s'y disent
  (« Stop, car moving. » / « Car moving/stopped, X meters away. »),
  le veto rouge restant l'autorité passage (amendé § 22 : le stop
  mouvement se dit en Traversée, plus en normal) ;
- **inchangé** : arrêt ≤ 3 m, cycles (`CycleWatcher`), urgent (tau + ≤ 8 m
  + centre), anglais par défaut.

Verrouillé par `VehicleStateTest` (+3 : info loin 12 m douce, stop proche
sans attendre le cooldown, cooldown 4 s entre stops ; portée 9 m → 16 m)
et `CrossingVehicleTest` (+1 : mouvement 12 m annoncé en Traversée).

Recette téléphone : voiture qui roule à ~12 m → « Car moving, 12 meters
away. » doux ; qui s'approche sous 8 m → pareil en doux en normal,
« Stop, car moving. » urgent en Traversée ; arrêt « Car stopped… » doux
— **non mesuré**, exige un APK de cet arbre sur appareil réel.
## 18. Personne à 4 m, 3e priorité, rattrapage (ordre user du 30/10)

Le user marche avec l'app : les personnes proches ne sont pas dites, ou
trop tard — une personne entre 3 et 4 m était ignorée
(`HAZARD_METERS = 3 m`), et la phrase douce tue par le chat ou la route
était mémorisée puis perdue (jamais redite). Nouveau contrat, qui amende
le § 11 pour la seule personne :

- **portée 4 m** : une personne dans le couloir à 4 m ou moins est détectée
  (`HazardConcepts.isPerson`, `HazardWatcher.PERSON_METERS`) ; les autres
  dangers restent à 3 m ;
- **imminente ≤ 2 m** : annonce dès la 1re frame, urgente (miroir de la
  marche imminente, `CurbWatcher.onNear`) ;
- **3e priorité** : la personne ne coupe ni ne chevauche P1/P2 (alertes
  `SafetyVoice`, § 16) — `ProbeWiring.p1p2Busy` (mouvement dit il y a
  moins de 4 s, marche imminente dite il y a moins de 2 s) tient la
  phrase en file (`ProbePriority`, `busy`) ;
- **dit une fois puis ré-armé** : après l'annonce, silence jusqu'à 3
  frames sans personne (`PERSON_REARM_FRAMES`) — une personne qui
  clignote à la frontière ne rabâche pas ; cooldown 10 s inchangé ;
- **rattrapage** : la phrase douce tue part en file (une seule, la plus
  fraîche) et est redite au silence dans les 3 s
  (`ProximityVoice.flushPending`, `PENDING_TTL_MS`) — une alerte
  confirmée ne se perd plus.

Fenêtres 4 s / 2 s / 3 s provisoires, non mesurées : à valider rue (si P3
parle sur P1/P2, élargir ; s'il se tait trop, resserrer).

Verrouillé par `HazardWatchTest` (+5 : personne 3,5 m détectée, 4,5 m
muette, 2 frames doux, imminente urgente, ré-armement),
`HazardConceptsTest` (+1 : `isPerson`), `VehicleStateTest` (+1 :
`lastMovingAlertMs`),
`CurbAnnounceTest` (+1 : `lastNearMs`), `ProbeWiringWatchersTest` (+2 :
`p1p2Busy`, `shouldCutForHazard`) et `ProximityVoiceQueueTest` (8 : file,
péremption, P1/P2, fraîcheur, route, reset, sans-file-perdu, busy).

## 19. Rebord dit à 1 m puis à 0,4 m, montée comme descente (ordre user du 30/10)

Le user marche avec l'app : les rebords sont détectés (masques SAM) mais
iSee ne les dit pas. Contrat tranché : un rebord dans le couloir marché
parle **deux fois** — à 1 m (info douce, avec l'heure) puis à 0,4 m
(imminent, alerte immédiate § 16) — en montant comme en descendant,
anglais par défaut :

- **phrases** : 1 m `Step up in 1 meter.` / `Step down in 1 meter.`
  (`Marche dans 1 mètre.` / `Descente dans 1 mètre.`, `CurbText.phrase`,
  heure du cadran quand le centre est connu) ; 0,4 m `Step up now.` /
  `Step down now.` (`Marche, maintenant.` / `Descente, maintenant.`,
  `CurbText.stepPhrase`, sans heure : à un pas, l'heure retarde la
  consigne) + `Montez le rebord.` / `Descendez le rebord.` (`Step up.` /
  `Step down.`, consigne immédiate sans « Stop » depuis le § 22) ;
- **paliers** (`CurbWatcher`) : suivi jusqu'à 3 m mais annonce dès 1 m
  (`FAR_ANNOUNCE_METERS`), imminent à 0,4 m (`STEP_NEAR_METERS`) ;
  hystérésis 1,0 / 1,4 m (pré-alerte, une fois par approche) et
  0,4 / 0,6 m (imminent) ; confirmation 2 frames partout ; entrée
  directe en imminent = seul l'urgent parle (pas de rattrapage) ;
  bateau supprimé 10 s après une marche (traversée du bord) ;
- **pourquoi c'était muet** : `rebord`, `curb ramp`, `bateau`,
  `gutter`, `caniveau` renvoyés par le serveur mais rejetés par
  `CurbConcepts` (ajoutés) ; le tracker envoyait le FR brut au CLIP
  anglais (traduit par `model_concept` dans `tracking.start`, dédup
  sur le concept modèle) ; la pré-alerte coupée en traversée
  consommait le cooldown (porte déplacée avant le watcher) ; la
  synthèse douce bloquait les frames suivantes (coroutine fille) ;
  (`curb` est suivi par la liste unique à 16 de
  `LivePrompts.default`) ;
- **non tranché, à valider sur téléphone (non mesuré)** : montée vs
  descente reste une heuristique de boîte (priors 0,95 / 0,5, bord
  `curb`/`step` lu montant par défaut — en cas de doute ça parle
  montant, jamais l'inverse) ; sans profondeur ni ancre, un trottoir
  seul ne produit aucune lecture de distance (silence, voir `R1-R3`
  d'audit) ; `max_objects` réglé à 8 par le user peut évincer un
  rebord faible score (comportement global, réglable à 32).

Verrouillé par `CurbWatcherTest` + `CurbAnnounceTest` (double annonce
EN 1 m / 0,4 m, hystérésis, verrous, bateau post-marche),
`KnownSizesTest` (famille bordure profondeur), `SidewalkKeepTest`
(marches ≠ trottoir), `concepts_street_tests.py` (formes longues,
`kerb`, `rigole`, `passage bateau`) et `test_tracking` (dédup FR/EN,
texte traduit).

Recette téléphone : marques craie à 1,0 m et 0,4 m, montée puis
descente — premier son ≤ 1 s après confirmation, bande 1,0 ± 0,3 m /
0,4 ± 0,25 m, sens juste 4/4 jour, bateau 0 `Stop`, traversée :
pré-alerte tuée et imminent passant — **non mesuré**, exige un APK de
cette branche sur appareil réel.
## 20. Alertes P1/P2 : la nouvelle coupe l'ancienne, jamais de file (ordre user du 31/10)

Le user marche avec l'app : entre l'obstacle détecté et la parole, des
latences de plusieurs secondes — une alerte P1/P2 qui arrivait pendant
qu'une autre parlait attendait sa fin en file TTS (`QUEUE_ADD`,
contrat § 16). Nouveau contrat, qui amende le § 16 pour les deux
alertes de sécurité (voiture en mouvement, rebord) :

- **flush toujours** : `SafetyVoice.speak` parle en `QUEUE_FLUSH` même
  quand le TTS système parle — la nouvelle alerte coupe l'ancienne, un
  par un, la plus fraîche gagne (une alerte datée ne sert à rien, le
  danger a changé) ; ne dépend plus du flag `speaking` ;
- **inchangé** : coupe la lecture ElevenLabs avant de parler
  (`stopCurrent`), survit à la dictée, parle hors-ligne sans clé ;
  P3 (poteau — qui dit désormais aussi par quel côté passer, § 21) attend
  toujours P1/P2 en file douce (§ 18) ; le reste parle doux et attend
  le silence (§ 11).

Verrouillé par `SafetyVoiceTest` (4 : coupe + flush, alerte coupe
l'ancienne, blanc ignoré, locale).

Recette téléphone : deux alertes rapprochées (voiture qui roule puis
rebord sous le pied) — la seconde coupe la première, pas de
chevauchement, pas d'attente — **non mesuré**, exige un APK de cette
branche sur appareil réel.
## 21. Voitures au pluriel, personne qui dit comment l'éviter, rebord à 0,7 m (ordre user du 31/10)

Le user marche avec l'app : plusieurs voitures en mouvement n'étaient dites
qu'au singulier (la plus proche), la personne proche ne disait pas comment
l'éviter, et la marche imminente parlait à 0,4 m — trop tard pour lever le
pied.
Nouveau contrat, qui amende les § 16-19 :

- **pluriel véhicules** : deux types mobiles ou plus dans la même frame =
  « 2 vehicles moving. » / « 2 véhicules en mouvement. » (générique : les
  pistes sont par type, pas par instance — deux voitures sous le même
  label comptent pour une, limite connue ; plus de position depuis le
  01/11, § 32) ; le « Stop » suit (« Stop, 2 vehicles
  moving. ») ; le singulier est inchangé (`VehicleStateText`,
  `VehicleStateCue.movingCount`) ;
- **personne + évitement** : phrase douce (2-4 m) « Attention à la
  personne, à 2 heures, passez à gauche. » / « Attention, person, at 2
  o'clock, pass on the left. » — le côté conseillé est l'opposé de la
  personne (personne à gauche de l'axe = on passe à droite) ; axe pile
  ou heure inconnue = droite par défaut (prior, à valider rue : on serre
  à droite) (`HazardText.personPhrase`, `avoidSide` seule source du
  côté) ; l'imminente (≤ 2 m) ne dit plus cette phrase : elle dit le mot
  seul d'évitement en clip (voir § 26, ordre du 01/11) ; les autres
  dangers restent descriptifs ;
- **rebord à 0,7 m** : la marche imminente parle à 0,7 m
  (`STEP_NEAR_METERS`), hystérésis 0,7 / 0,9 m (`STEP_REARM_METERS`) ; la
  pré-alerte à 1 m, les phrases (« …, maintenant. » ≈ un pas), le sens
  montant/descendant et le bateau sont inchangés ;
- **montée/descente confirmée** : le sens reste la géométrie de boîte
  (§ 19, priors 0,95 / 0,5) — bord `curb`/`step` lu montant par défaut,
  en cas de doute ça parle montant, jamais l'inverse ; verrouillé par les
  tests ci-dessous, à valider rue (sens juste 4/4 jour).

Verrouillé par `VehicleStateTest` (+1 : deux types mobiles = pluriel EN/FR,
stop pluriel), `HazardWatchTest` (+1 : évitement côté opposé FR/EN, défaut
droite ; 3 attentes personne mises au nouveau contrat), `CurbWatcherTest`
(palier 0,7) et `CurbAnnounceTest` (double annonce 1 m / 0,7 m, ré-armement
0,9, frontière 0,65 parlé / 0,75 doux).

Recette téléphone : deux voitures qui roulent → « 2 vehicles moving… » ;
poteau à 3 m à 11 h → « …, passez à droite. » ; marques craie à 1,0 m et
0,7 m, montée puis descente — sens juste 4/4 jour — **non mesuré**, exige
un APK de cette branche sur appareil réel.
## 22. Stop seulement en Traversée, voitures en mouvement (ordre user du 31/10)

Le user marche avec l'app : iSee dit trop souvent « stop » — elle doit
dire directement ce qu'il faut faire. Nouveau contrat, qui achève le
retrait du stop des §§ 10/11/13 (le cap droit ne dit déjà plus stop)
et amende les §§ 16/17/19 et le § 21 pluriel :

- **guidage** : un obstacle dit son côté de sortie, même au contact
  mesuré (`Guidance.word`, `STOP_METERS` et `GuidanceDecision.stop`
  supprimés) — « gauche. » / « left. » TwinLite, jamais
  « obstacle, stop. » ; la voix dit directement comment éviter ;
- **voitures** : « Stop, car moving. » / « Stop, voiture en mouvement. »
  (et « Stop, 2 vehicles moving. » au pluriel) ne se dit qu'en Traversée,
  mouvement proche (≤ 8 m ou non mesuré,
  `CrossingVehicleWatch`, urgent côté sonde via
  `VehicleStateText.isSafetyPhrase`) ; en normal, mouvement comme arrêt
  parlent doux (`observeVehicleState`, plus d'alerte `SafetyVoice`) ;
- **rebord** : jamais de « Stop » — l'imminent dit la consigne
  (« Montez le rebord. » / « Descendez le rebord. », `Step up.` /
  `Step down.`, `CurbText.safetyStepPhrase`), toujours par la voix de
  sécurité (coupe tout, survit à la dictée) ;
- **P1/P2** : `SafetyVoice` ne porte plus que le rebord (flush § 21
  inchangé) — le stop mouvement est parlé urgent (voix ElevenLabs),
  pas en alerte système ;
- **inchangé** : « Stop. » de l'urgence externe (`reportEmergency`,
  contrat `docs/URGENCE-EXTERNE.md`), veto rouge, cooldowns (4 s
  mouvement, 10 s arrêt/trottoir), mots et clips `guide_stop`/`tl_stop`
  au catalogue (miroir `tools/voicepack-catalog.json`, inutilisés).

Verrouillé par `GuidanceTest` + `GuidanceAnnouncerTest` +
`GuidanceObstaclesTest` (contact ⇒ côté),
`VehicleStateTextTest` (+2 : `isSafetyPhrase`, singulier + pluriel),
`CurbAnnounceTest` (consigne sans stop), `CrossingVehicleTest` (stop
EN/FR en Traversée), `LiveProbeGuidanceTest` (contact ⇒ côté non
urgent) et `ProbeWiringWatchersTest` (+1 : `spokenUrgentOf`).

Recette téléphone : obstacle au contact → côté de sortie, pas de stop ;
voiture qui roule sous 8 m → doux en normal, « Stop, car moving. » en
Traversée ; rebord imminent → « Montez le rebord. » — **non mesuré**,
exige un APK de cette branche sur appareil réel.
## 23. Scan traversée : stop, regardez à gauche et à droite (ordre user du 31/10)

Le user veut qu'iSee, sur le passage piéton, dise à l'utilisateur de
s'arrêter et de regarder à gauche puis à droite, et vérifie qu'aucune
voiture n'est en mouvement — consigne à l'user + verdict explicite.
Nouveau contrat, en mode Traversée seul :

- **déclencheur** : arrivé sur le passage (bas touché, pieds proches ou
  profondeur ≤ 1,5 m, `CrossingGuidance`) **et** passage à moins de 2 m
  (profondeur/sol, jamais sténopé — `scanTriggerOf`), 2 frames de suite
  (un positif isolé ne dit jamais « Stop ») ;
- **séquence** (`CrosswalkScan`, core pur) : « Stop. » (mot du guidage,
  clip < 100 ms, seul urgent — § 11) → « Regardez à gauche. » → virage à
  gauche prouvé (60° de lacet signés depuis le cap du scan — face à la
  route —, `YawMeter`, ou 6 s) → observation 2,5 s → constat parlé
  (« Attention, voiture à gauche. » / « Rien à gauche. ») → même chose à
  droite (« Regardez à droite. », virage à droite prouvé, constat) →
  silence si libre (re-vérification sinon, voir ci-dessous) ;
- **priorité** : feu > scan > véhicules > personnes (`crossingPhraseOf`,
  amendé par le § 25 : l'évitement passe devant le feu, le scan garde la
  voix pendant sa procédure) — le feu parle, les horloges du scan gèlent
  et le mouvement n'est pas compté : stop, consignes et constats se disent
  entiers après (jamais couverts ni tronqués) ;
- **re-vérification obligatoire** : un constat danger relance un cycle
  complet après 3 s d'attente silencieuse (le temps que le véhicule
  passe — consignes seules, sans « Stop. »), jusqu'à 3 cycles par
  traversée ; au-delà, le scan rend la voix (le veto garde la traversée,
  les voix douces reprennent) ;
- **mauvais sens corrigé** : tourner à droite quand on a demandé la
  gauche (30° depuis l'entrée du côté, prior) redit aussitôt « Non,
  regardez à gauche. » (miroir à droite) au lieu d'attendre le timeout ;
- **écran miroir** : le bandeau Traversée montre la consigne en cours
  et les constats latéraux déjà rendus (« Pas de voiture à gauche en
  mouvement. » / « Voiture à gauche ! », FR/EN), rouge/vert/ambre —
  rien hors scan ;
- **mouvement latéral** : une voiture qui traverse le champ à taille
  stable est désormais « en mouvement » (vitesse x lissée > 8 %/s,
  `VehicleApproach`, prior à valider rue) — le tau ne la voyait pas, et
  c'est le danger n° 1 d'un piéton ; le veto ne suit que l'approche
  (inchangé), les annonces d'état et le scan suivent les deux ;
- **regard stabilisé seul** : l'observation ne compte que le lacet lent
  (≤ 20°/s — un téléphone en rotation fait glisser les boîtes garées,
  qui ressembleraient à du mouvement) ; sans capteur, repli temporisé
  (le verdict vaut pour les deux fenêtres d'observation, limite connue).

Verrouillé par `CrosswalkScanTest` (21 : séquence, virages signés,
bouclage ±180°, repli temporisé, constats par côté, gel feu, regard
tournant ignoré, re-vérification, plafond 3 cycles, mauvais sens),
`VehicleApproachTest` (+4 : latéral franc, dérive lente,
approche muette, panoramique tuée par le fond), `VehicleStateLateralTest`
(2 : latéral annoncé, brut par frame), `CrosswalkScanWiringTest`
(10 : déclencheur 2 m, priorités) et `CrosswalkScanDisplayTest`
(10 : textes FR/EN, cycles, couleurs).

Recette téléphone : fumée Redmi OK (install + lancement + pid vivant,
capteur rotation présent sur les deux téléphones) ; sur un passage,
téléphone tenu face — « Stop. Regardez à gauche. … Rien à gauche.
Regardez à droite. … Rien à droite. » ; virage à droite quand on a
demandé la gauche : attente (pas de validation) ; voiture qui passe
pendant un côté → « Attention, voiture à … » puis re-vérification —
recette passage réel **non mesurée**, exige une sortie rue.
## 24. Bordure dite dès détection puis à 2 / 1,5 / 1 / 0,7 / 0,5 m (ordre user du 31/10)

Le user marche avec l'app : la bordure n'était dite qu'à 1 m puis à
0,7 m (§§ 19/21). Nouveau contrat, qui **remplace** la double annonce
des §§ 19/21 (gardés pour mémoire) : dès qu'une bordure est détectée
dans le couloir marché, elle est dite aussitôt, puis redite à chaque
palier franchi en s'approchant :

- **phrases** (`CurbText`, FR/EN) : détection `Bordure détectée.` /
  `Curb detected.` (avec l'heure du cadran quand le centre est connu,
  § 12) ; paliers `Bordure à 2 mètres.` / `Bordure à 1,5 mètre.` /
  `Bordure à 1 mètre.` / `Bordure à 0,7 mètre.` / `Bordure à 0,5
  mètre.` (`Curb at 2 meters.` / `1.5 meters` / `1 meter` /
  `0.7 meters` / `0.5 meters`), sans heure — courts pour s'enchaîner ;
- **paliers** (`CurbWatcher`) : suivi jusqu'à 3 m (inchangé), détection
  confirmée en 2 frames, une fois par approche (cooldown 10 s contre
  le clignotement) ; chaque palier dit une fois par approche dès
  2 frames sous le seuil, **sans** cooldown entre paliers (l'approche
  à pied les enchaîne en ~2 s) ; hystérésis +0,2 m par palier (recul
  au-delà = le palier reparle) ; palier survolé entre deux frames =
  sauté sans rattrapage (jamais de distance périmée) ; signal perdu =
  détection et paliers ré-armés ;
- **sécurité gardée** : 0,7 m et 0,5 m urgents (`NEAR`, passent en
  Traversée comme l'imminent § 19) ; 0,5 m porté par la voix de
  sécurité (coupe tout, §§ 16/22) avec le même texte — plus de
  « Montez le rebord. » ; bateau inchangé.

Verrouillé par `CurbAnnounceTest` (20 : détection, compte à rebours
FR/EN, entrée directe proche, palier survolé, hystérésis, cooldowns,
bateau) et `CurbWatcherTest` (phrases détection + paliers FR/EN).

Recette téléphone : approche réelle d'une bordure → « Bordure
détectée. », « Bordure à 2 mètres. » … « Bordure à 0,5 mètre. » —
**non mesurée**, exige une sortie rue.

## 25. La voix du guidage ne se tait plus sans raison (fix du 31/10, ex-§24)

Le user marche avec l'app : le guidage (right/left/straight) s'arrête
d'un coup, ne dit plus rien, puis reprend tout seul — écran allumé.
Trois causes lues dans le code, trois correctifs :

- **répétition identique qui annulait sa propre synthèse (régression
  §7)** : le `spokenSeq` (directions en continu, § 10) faisait passer
  chaque ré-émission dans `collectLatest`, qui annulait la synthèse
  réseau en vol de la même phrase — synthèse lente + redites 2 s =
  silence (voix non-Laure, premier cache, positions). Nouveau contrat :
  seule une phrase fraîche écrase (`observeSpokenEmissions` : répétition
  identique en vol gardée, changed = dernier-gagnant, clips rejoués
  après fin) — le §6 ne visait que les phrases fraîches ;
- **attentes silencieuses qui taisaient la sécurité** : `canAnnounce`
  (transcription, réflexion LLM, synthèse réseau — jusqu'à 75 s) taisait
  les mots du guidage sans qu'aucun son ne joue. Nouveau contrat : la
  voix du guidage cède au son réel (lecture chat, voix du user en
  tenue) mais jamais au silence (`canGuideSpeech`) ; l'état parlant du
  chat lu en continu ne couvre que la lecture réelle, tranche par
  tranche (`IseePlayback`) — le guidage s'intercale dans les synthèses ;
- **famine de boucle muette** : panne SAM/réseau ou conteneur froid =
  `spoken` figé, silence total sans explication. Nouveau contrat : passé
  6 s sans consigne fraîche alors qu'on guide (vidéo, guidage, hors
  Traversée), la voix de sécurité prévient (« Guidance paused.
  Reconnecting. », TTS système hors-ligne — la famine, c'est souvent le
  réseau), puis annonce la reprise (« Guidance resumed. ») —
  `GuidanceStarvation` (core pur) + `observeStarvation`.

Verrouillé par `GuidanceStarvationTest` (7 : seuil, unicité, reprise,
hors guidage, reset, phrases EN/FR), `ProbeSpokenFlowTest` (5 :
répétition gardée, overwrite, rejeu, nul, porte), `IseePlaybackTest`
(+1 : état parlant = lecture réelle) et `ProbeWiringSpokenTest`
(inchangé, adaptateur).

Recette téléphone : conteneur froid au départ → « Guidance paused.
Reconnecting. » puis « Guidance resumed. » à la première frame ;
question posée en marchant → le guidage continue pendant la réflexion
et entre les tranches lues ; voix non-Laure → les mots finissent par
se dire puis partent en clips — **non mesuré**, exige un APK de cette
branche sur appareil réel.
## 25. Évitement prioritaire en Traversée : gauche/droite d'abord (ordre user du 31/10)

Le user marche avec l'app : en Traversée, le guidage est coupé et
l'évitement des personnes parle en dernier — l'app dit le feu pendant
qu'on fonce sur quelqu'un. Nouveau contrat, qui amende le § 23 : dire
gauche/droite et éviter les humains passe devant tout, même en
Traversée. Combiné à la traversée muette (même jour : personnes jamais
décrites) : les mots d'évitement parlent en premier, « Tout droit. » en
queue de chaîne (battement 300 ms — la voix ne se tait jamais hors
procédure scan).

- **ordre traversée** (`crossingPhraseOf`) : consigne du scan quand il
  parle (procédure entière, jamais tronquée) > stop urgent « voiture en
  mouvement » (§ 22, absolu devant l'évitement doux : on ne s'écarte pas
  devant une voiture qui arrive) > ordre d'évitement (mot du guidage
  d'abord, puis mot des personnes) > feu > véhicules doux > « Tout
  droit. » (battement 300 ms, jamais muet hors scan ; personnes jamais
  décrites) ; sans feu : stop urgent > évitement > véhicules doux >
  verdict ;
- **mots prioritaires** : virages SAM (« Gauche. », « Droite. ») et
  TwinLite (« gauche. », « droite. ») + stop (`preemptsCrossing`) —
  « Tout droit. » ne couvre jamais le feu (voie libre, pas évitement) ;
- **procédure scan** (`scanRunning`) : l'user est arrêté sur les zébrures,
  le scan garde la voix (ordre § 23 — feu > scan > véhicules) — un
  « Gauche. » marché au milieu du « Regardez à gauche. » ferait confondre
  marcher et regarder ;
- **guidage coupé** : le réglage est respecté (pas de consultation) —
  l'évitement des personnes, lui, parle avec ou sans guidage (sécurité,
  comme le rebord imminent) ;
- **mots jamais tus** : l'évitement n'est plus tu par l'ordre du feu
  (`wordsMuted`, tu seulement pendant la procédure scan — le silence
  doux 2,5 s ne couvre plus que les véhicules).

Verrouillé par `GuidanceWordTest` + `TwinLiteWordTest` (+1 :
`isTurnWord`), `CrossingSteeringTest` (8 : préemption, mot devant le
feu, décalage sonde, voie libre, guidage coupé, scan), `CrossingPeopleTest`
(mot devant le feu, mots tus, personne seule muette, repli tout droit,
battement évitement 300 ms, attente scan silencieuse) et
`CrosswalkScanWiringTest` (+2 : scan tait le guidage, stop devant le
guidage ; repli tout droit aux frames d'attente).

Recette téléphone : personne sur le passage en Traversée → « Droite. »
ou « Gauche. » au lieu du feu, puis le feu entre deux mots ; sur les
zébrures, le scan parle entier sans mot marché ; voiture qui roule sous
8 m → « Stop, voiture en mouvement. » même avec un évitement ; sans
feu : évitement devant le verdict — **non mesuré**, exige un APK de
cette branche sur appareil réel.
*Note de fusion : les deux sections « 24 » concurrentes sont conservées
en entier — celle de la branche reste le §24 (priorité en cas de
doute), celle de master est renumérotée §26 (ex-§24, le §25 restant
occupé par la section « Voix obstacles » de la branche, conservée
ci-dessous telle quelle) ; aucun des deux contrats n'est fusionné ni
abandonné.*

## 26. Voix obstacles : hiérarchie P1-P5, personne imminente en mot seul (ordres user des 31/10 et 01/11)

Le user marche avec l'app : quand SAM confirme plusieurs obstacles en même
temps, l'ordre de parole n'était pas défini (trois guetteurs indépendants,
chacun sa voix) et la personne imminente partait en synthèse réseau (lente).
Nouveau contrat, qui amende les § 16, § 18 et § 20 dans le cadre du § 22
(jamais de « Stop » hors Traversée) :

- **hiérarchie** : P1 voiture en mouvement > P2 bordure > P3 personne > P4
  barrière > reste (arrêt, bateau, bornes…, dans l'ordre de détection) ;
- **un seul guetteur** (`LiveProbe.observeObstacles`) calcule les trois
  signaux (véhicules, bordure, danger) sur la même frame et parle une
  fois : les alertes de sécurité fusionnent dans l'ordre des rangs
  (voix de sécurité, immédiat, sans « Stop » — le rebord seul : la
  personne imminente ne passe plus par la voix de sécurité, voir
  ci-dessous), les phrases douces aussi (voix ElevenLabs, file
  d'attente au silence) — une alerte confirmée ne se perd plus, elle
  attend son rang ; le véhicule ne fournit que du doux ici (son stop
  roulant ne se dit qu'en Traversée, § 22), P1 en tête ;
- **personne imminente en mot seul** (ordre du 01/11, remplace la
  consigne de sécurité ci-dessus) : à 2 m ou moins, le mot seul
  d'évitement « Droite. »/« Gauche. » / « Right. »/« Left. » (côté
  opposé à la personne, `HazardText.avoidSide` — même côté que la
  phrase douce), parlé en clip `guide_*` existant (départ < 100 ms,
  jamais de synthèse réseau ni de voix de sécurité : à un pas, le mot
  prime sur la phrase) et répété toutes les 300 ms tant que la personne
  reste imminente (miroir du battement traversée, immédiat au
  changement de mot) ; le mot contourne le verrou dit-une-fois (doux
  puis approche = il parle) et ne consomme pas le cooldown doux (la
  barrière suit sans attendre 10 s) ; la phrase douce 2-4 m
  (« Attention à la personne, …, passez à … ») est inchangée ;
- **personne avant barrière** : même frame, la personne parle d'abord
  (signal préféré), la barrière suit en repli dès la personne dite
  (compteurs de confirmation séparés par sorte, cooldown doux partagé) ;
- **doux sérialisé** : la voix douce ne bloque plus la frame (coroutine
  fille + mutex — la sécurité reste instantanée même pendant une
  synthèse) et ne chevauche plus la sécurité (fenêtre
  `SAFETY_SPEECH_MS = 2,5 s`, provisoire : sous les 3 s de la file, pour
  que le rattrapage parle encore frais — à valider rue) ; seul le
  danger qui s'approche (`urgent` véhicule) coupe, comme avant ;
- **inchangé** : confirmation 2 frames partout, cooldowns, Traversée
  (seul l'imminent bordure y passe, stop roulant à part), flush (§ 20 :
  la frame suivante coupe la précédente), le reste parle doux comme
  avant.

Verrouillé par `ObstacleArbiterTest` (10 : ordre des rangs, fusion
sécurité P2-P3, stop roulant ignoré en normal, doux ordonnés, urgent
véhicule, barrière après personne, reste en ordre de détection, personne
imminente, silence, fenêtre ; +2 le 01/11 : clip du mot imminent,
`null` hors personne urgente en mot), `HazardWatchTest` (+6 : préférence
personne, repli barrière, compteurs par sorte, consigne personne FR/EN,
cooldown gardé, sorte divers — mot seul imminent le 01/11 : la consigne
sécurité devient le mot en clip, + répétition 300 ms et contournement du
verrou dit-une-fois), `HazardConceptsTest` (+2 : `isBarrier`, `kindOf`),
`VehicleStateTest` (+1 : flag `moving`) et `ProbeWiringWatchersTest`
(`p1p2Busy`/`shouldCutForHazard` supprimés, remplacés par l'arbitre).

Recette téléphone : voiture qui roule + marche + personne imminente
cadrées ensemble → « Montez le rebord. » en sécurité, « Droite. » ou
« Gauche. » en clip immédiat,
puis « Car moving,
12 meters away. … » en doux, premier son immédiat, aucun « Stop » —
**non mesuré**, exige un APK de cette branche sur appareil réel.
## 27. Évitement 80 cm partout, mesuré ou non (ordre user du 31/10)

Le user marche avec l'app : la garantie 80 cm aux personnes ne tenait
que par le chemin calculé (`PeoplePath.plan`, cap droit bouché à
géométrie complète) — décalage, repli sans mesure, retour trottoir et
côté serveur pouvaient envoyer vers quelqu'un. Nouveau contrat : tout
ordre d'évitement garantit 80 cm à toute personne, mesurée ou non.

- **prédicat partagé** (`PeoplePath.clearsSide`/`clearsAhead`) : un côté
  (ou l'axe) est dégagé si aucune personne à portée n'est à moins de
  corps + 0,8 m d'écart latéral, et aucune personne non mesurée de ce
  côté (prudence : l'inconnu bloque le côté) ;
- **repli et passage** (`Guidance.freerSide`, `bestPassage`) : un seul
  côté/secteur dégagé à 80 cm → lui, sinon l'ancien départage (foule :
  on se faufile) ; égalité de cap → le plus grand écart d'abord
  (`select`, puis la gauche comme avant) ;
- **décalage** (`LateralNudgeTracker`) : une personne non mesurée côté
  cible bloque le mot (la mesurée le bloquait déjà) ;
- **passage piéton** (`CrossingOnPathTracker`) : une personne côté
  cible, mesurée ou non, fait taire l'évitement (le couloir parle s'il
  est bouché) ;
- **côté serveur et retour trottoir** (`GuidanceAnnouncer`) : le côté
  SAM bascule si l'autre garantit 80 cm ; le retour trottoir garde le
  cap au lieu de revenir vers une personne proche ;
- **traversée direct** (`TraverseVeto`) : une personne non mesurable
  garde le guidage obstacles actif ;
- **inchangé** : `plan` (déjà 80 cm), `collect` (le repli est sûr
  maintenant), le passage serveur (veto local devant), `word` (sans
  labels, pas de vérification possible).

Verrouillé par `PeoplePathTest` (+7 : garantie par côté/axe, inconnues,
non-humains, égalité au plus grand écart), `GuidanceSteeringTest` (+2 :
repli et passage 80 cm — 5 réécrits : le dégagé prime sur le centre),
`LateralNudgeTest` (+2 : veto inconnue cible, témoin côté
approche), `CrossingOnPathTest` (+2 : veto cible, témoin côté occupé),
`GuidanceAnnouncerTest` (+2 : bascule SAM, garde sans dégagement —
contact latéral réécrit « right. »), `GuidanceAnnouncerSidewalkTest`
(+2 : retour gardé, retour parlé), `TraverseVetoTest` (+2 : inconnue
garde, véhicule non), `SteeringTest` + `LiveProbeSteeringTest` (1 + 2
réécrits : passage dégagé).

Recette téléphone : personne à gauche à 1 m, couloir bouché sans
mesure → « Droite. » ; approche gauche + personne à droite (même non
mesurée) → silence, pas de « Droite. » ; retour trottoir vers une
personne proche → le cap au lieu du retour — **non mesuré**, exige un
APK de cette branche sur appareil réel.
## 28. Escaliers jalonnés à 4 / 2 / 1 m (ordre user du 31/10)

Le user veut entendre l'escalier à 4 m, 2 m et 1 m en s'approchant.
Nouveau contrat, miroir du §24 bordure (détection puis compte à rebours) :

- **phrases** (`StairText`, FR/EN) : détection inchangée (« Escalier
  montant, à 11 heures. ») ; jalons « Escalier montant à 4 mètres. » /
  « à 2 mètres. » / « à 1 mètre. » (`Stairs up at 4 meters.` / `2
  meters` / `1 meter`) — le sens (montant/descendant/devant) est gardé,
  la distance entière (`DistanceText.spokenShort`) remplace le cadran,
  sans heure (miroir §5 positions) ;
- **paliers** (`StairWatcher`) : chaque palier dit une fois par approche
  dès N frames sous le seuil (N = confirmation du watcher, 2 voix
  sonde), **sans** cooldown entre paliers (l'approche à pied les
  enchaîne) ; hystérésis +0,2 m par palier (recul au-delà = le palier
  reparle, prior à valider rue comme le §24) ; palier survolé entre
  deux frames = sauté sans rattrapage (jamais de distance périmée) ;
  signal perdu = détection et paliers ré-armés ;
- **traversée seule** (différence bordure) : un palier ne parle que
  traversé (vu d'au-delà d'abord) — l'entrée directe (caméra allumée en
  haut des marches) parle standard sans distance puis « Dernière
  marche. » (verrou `StairLastStepGateTest` gardé) ;
- **montée gardée** : le jalon ouvre la montée comme la détection (le
  compte à rebours puis la dernière marche suivent) ; doux partout
  (`urgent = false`, §11) ; câblage sonde inchangé (`ProbeStairs` passe
  déjà la profondeur).

Verrouillé par `StairWatcherTest` (+9 : détection puis jalon 4 m,
compte à rebours 4/2/1, entrée directe, palier survolé, hystérésis
des deux côtés, signal perdu, reset, phrases FR/EN).

Recette téléphone : approche réelle d'un escalier → « Escalier
montant. », « … à 4 mètres. », « … à 2 mètres. », « … à 1 mètre. » —
**non mesurée**, exige une sortie rue.
## 29. Une seule voix de sécurité, grave (ordre user du 31/10)

Le user entend l'urgence dans la même voix que le normal (stop Traversée
en Laure, bordure comme le guidage GPS). Nouveau contrat, qui amende la
livraison du § 22 (gardée pour mémoire) : toute urgence parle par la voix
de sécurité (TTS système, pitch grave 0,75 —
`SystemTtsEngine.SAFETY_PITCH`), distincte de la voix route (même moteur,
pitch normal) comme de Laure (ElevenLabs).

- **tout urgent en sécurité** : stop, danger qui s'approche, sortie de
  trace, recalcul, arrivée (`ProximityVoice` urgent → `SafetyVoice`,
  `VoiceNavSpeaker` danger en grave) — coupe tout, hors-ligne, oral seul
  (aucun sous-titre, le TTS système ne rend pas de fin) ;
- **clips stop en héritage** : `guide_stop`/`tl_stop` restent au catalogue
  mais ne sont plus joués (l'urgent ne passe plus par les clips) ;
- **modèle externe** : `SafetyVoice.reportEmergency` (contrat
  `docs/URGENCE-EXTERNE.md`), « Stop. » grave aussitôt, sans clé.

Verrouillé par `SafetyVoiceTest` (+1 : urgence externe grave),
`VoiceNavSpeakerTest` (+1 : danger grave, consigne normale),
`SystemTtsEngineTest` (+2 : pitch transmis, bornes),
`ProximityVoiceWordTest` (stop en sécurité),
`ProximityVoiceRepeatTest` (+1 : urgent hors-ligne) et
`ProximityVoiceRouteTest` (route urgente en sécurité).

Recette téléphone : « Stop, car moving. » en Traversée et sortie de trace
sonnent graves, distincts du doux et du guidage — **non mesurée**, exige
un APK de cette branche sur appareil réel.

## 30. Annonces « Attention … » cadencées, parole du user devant tout (ordre user du 1/11)

Deux plaintes de recette, une même racine : le direct parle trop, et il
parle par-dessus le user.

- **cadence des « Attention … »** (`core/live/AttentionCadence`, branchée
  dans `ProximityVoice`) : une annonce déjà dite ne se redit pas avant
  20 s, deux annonces différentes sont séparées de 6 s, deux annonces
  urgentes de 2 s. La clé est la phrase sans ses nombres — « à 2 heures »
  et « à 3 heures » sont la même annonce, le cadran qui tremble ne fait
  pas une phrase neuve. Seules les phrases qui disent « Attention » /
  « Caution » passent par la cadence : états de véhicule, consignes de
  route et mots du guidage ne sont jamais filtrés.
- **parole du user devant tout** : tant qu'il tient le micro ou qu'une
  transcription court (`IseeViewModel` : `holding || transcribing`),
  aucune voix de proximité ne parle. La lecture en cours est coupée une
  fois (fanion rendu : `player.stop()` + `onSpeaking("")`, sinon le
  lecteur resterait cru occupé), les phrases attendent en file
  (`flushPending`, 5 s) et se redisent au silence. Une alerte de sécurité
  de la frame obstacles (`decideObstacles().safety`) ne part plus en voix
  de sécurité quand le user parle : elle rejoint le doux, derrière sa
  phrase, et attend le silence (`softOf`).
- **seule exception** : le stop de Traversée (`GuidanceWord.isStopPhrase`)
  n'attend jamais la fin de la dictée.

Verrouillé par `AttentionCadenceTest` (7), `ProximityVoiceUserTest` (7 :
silence pendant la parole, redite au silence, sécurité retenue, stop qui
passe, cadence) et `SoftHeldForUserTest` (4). Recette téléphone : parler
en marchant devant un obstacle fixe ne donne plus une annonce toutes les
4 s — **non mesurée**, exige un APK de cette branche.

## 31. Mouvement écrase tout : voiture > humain (ordre user du 01/11)

Le user marche avec l'app : une voiture en mouvement (ou une personne)
arrive pendant une autre annonce et se fait attendre — ou se noie dans
une phrase fusionnée. Nouveau contrat, qui amende le § 26 (la fusion
ordonnée ne vaut plus que sans mouvement) : le mouvement écrase tout
doux, et la voiture écrase l'humain.

- **gagnant seul** (`decideObstacles`) : voiture en mouvement présente →
  sa phrase seule ; sinon personne présente → sa phrase seule ; sinon
  fusion par rang comme avant (bordure > barrière > reste) ;
- **coupe le doux** (`VoicePreempt`, `ProximityVoice.onPhrase`) : la
  phrase du mouvement coupe la parole douce en cours (chat, route,
  annonce — le lecteur partagé est coupé) au lieu d'attendre le
  silence, voix Laure et sous-titres inchangés (pas la voix de
  sécurité) ;
- **sécurité et parole du user au-dessus** : fenêtre P1/P2, TTS système
  ou micro en cours → la phrase mouvement part en file comme le doux
  (jamais de chevauchement avec une alerte, jamais par-dessus le user,
  § 30) ; l'urgent garde la main (danger qui s'approche, bordure à
  0,7 m) ; le mot seul imminent (personne à 2 m ou moins) parle en
  clip même quand une voiture gagne (anti-collision) ;
- **file ordonnée** : dans la file douce, la voiture ne se fait pas
  remplacer par une personne, ni une personne par un doux (à rang égal,
  la plus fraîche gagne comme avant) ;
- **inchangé** : la voiture à l'arrêt (fusionnée avec le reste), la
  sécurité bordure (immédiate, devant le mouvement), la Traversée (voix
  propre), les cycles et escaliers (doux, coupés comme le reste), la
  cadence des « Attention … » (§ 30, qui filtre aussi la personne).

Verrouillé par `ObstacleArbiterTest` (+4 : voiture écrase bordure et
personne, personne écrase bordure et barrière, personne écrase l'arrêt,
sécu gardée avec mouvement ; 3 repris : stop roulant seul, fusion sans
mouvement, danger seul urgent) et `ProximityVoicePreemptTest` (8 :
coupe chat, coupe route, file P1/P2, file TTS système, file voiture
devant personne, personne remplace doux, voiture remplace personne,
urgent devant préemption).

Recette téléphone : bulle du chat en lecture + voiture qui passe → la
bulle est coupée, « Car moving, … » parle ; personne + bordure à 1 m →
la personne seule ; alerte rebord + voiture → le rebord d'abord, la
voiture juste après — **non mesuré**, exige un APK de cette branche sur
appareil réel.

## 32. Un mobile ne dit plus son emplacement (ordre user du 01/11)

Le user marche avec l'app : « ne me dis plus l'emplacement de la voiture
si tu vois une voiture qui bouge ». Une distance ou une heure dite sur un
véhicule qui roule est périmée avant la fin de la phrase — et l'app la
redit toutes les 4 s.

- **mouvement** : « Voiture en mouvement. » / « Car moving. » — nu, sans
  distance ni heure, singulier comme pluriel (« 2 vehicles moving. ») ;
  `VehicleStateText.movingPhrase` / `movingPluralPhrase` n'ont plus de
  paramètre de position (le compilateur garde la règle) ;
- **danger conservé** : le proche (sous 8 m, ou non mesuré) garde son
  « Stop, car moving. » (voix de sécurité, § 29) ; l'urgence du canal
  doux (`VehicleStateCue.urgent`, `safetyPhrase`) ne bouge pas ;
- **immobiles inchangés** : l'arrêt et le stationnement situent toujours
  (« Voiture à l'arrêt, à 2 mètres. », « Bus en stationnement, à 8
  mètres. », heure du cadran sans mesure) — c'est le repère qui ne bouge
  pas, donc la seule position qui tient ;
- **portée inchangée** : le mouvement se dit toujours jusqu'à
  `MOVING_METERS` (infini sans feu, § 14 de `TRAVERSEE.md`) ; seule la
  position tombe ;
- **inchangé ailleurs** : le décalage latéral en Traversée
  (« Attention, voiture à gauche. ») et le veto ne disent pas de
  position, rien à couper.

Verrouillé par `VehicleStateTextTest` (`mouvement jamais situe - arret et
stationnement si`, FR/EN, singulier/pluriel, arrêt et stationnement
conservés) et par les attentes réécrites de `VehicleStateTest`,
`VehicleStateRangeTest`, `VehicleStateBackgroundTest`,
`VehicleStateLateralTest`, `VehicleStateDepthTest`, `EgoVoteWatcherTest`,
`NoLightCrossingTest`, `CrossingVehicleTest`, `LiveVehicleVoiceTest`.

Reste à trancher : les cycles (`CycleWatcher`, « Attention, vélo en
mouvement, à 2 heures. ») disent encore leur heure — même problème, mais
l'ordre ne parlait que de « la voiture ». Recette téléphone **non
mesurée**.

## 33. Compteur de marches prioritaire : dit chaque seconde, coupe le doux (ordre user du 01/11)

Le user marche avec l'app : devant un escalier, le compte des marches se
fait attendre — il veut l'entendre toutes les secondes, en plus haute
priorité. La répétition existe déjà (le même compte se redit toutes les
1 s, `StairCountVoice.REPEAT_MS`) ; nouveau contrat, qui amende le § 31
(le mouvement n'est plus seul à couper) : le compte coupe la parole
douce au lieu d'attendre le silence, devant le mouvement lui-même.

- **rang escaliers** (`VoicePreempt.STAIRS`, `observeStairsCount`) : le
  compte (« 5 marches. ») coupe la parole douce en cours (chat, route,
  annonce — le lecteur partagé est coupé) au lieu d'attendre le
  silence, voix Laure et sous-titres inchangés (pas la voix de
  sécurité) ;
- **sécurité et parole du user au-dessus** : fenêtre P1/P2, TTS système
  ou micro en cours → le compte attend (jamais de chevauchement avec
  une alerte, jamais par-dessus le user, § 30) ; l'urgent garde la main
  (traité avant) ; Traversée et esquive coupent le compteur comme
  avant ;
- **file ordonnée** : dans la file douce, le compte ne se fait remplacer
  par personne — ni voiture, ni personne, ni doux (l'ordinal compare,
  à rang égal la plus fraîche gagne comme avant).

Verrouillé par `ProximityVoicePreemptTest` (+2 : escaliers remplacent
voiture, voiture ne remplace pas escaliers) et
`ProbeStairsCountVoiceTest` (+1 : compteur parlé chat en lecture).

Recette téléphone : bulle du chat en lecture + escalier nommé → la
bulle est coupée, « N marches. » parle puis se répète chaque seconde ;
voiture en mouvement + escalier → le compte d'abord — **non mesuré**,
exige un APK de cette branche sur appareil réel.
