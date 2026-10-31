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
## 18. Poteau à 4 m, 3e priorité, rattrapage (ordre user du 30/10)

Le user marche avec l'app : les poteaux ne sont pas dits, ou trop tard —
un poteau entre 3 et 4 m était ignoré (`HAZARD_METERS = 3 m`), et la phrase
douce tue par le chat ou la route était mémorisée puis perdue (jamais
redite). Nouveau contrat, qui amende le § 11 pour le seul poteau :

- **portée 4 m** : un poteau dans le couloir à 4 m ou moins est détecté
  (`HazardConcepts.isPole`, `HazardWatcher.POLE_METERS`) ; les autres
  mobiliers restent à 3 m ;
- **imminent ≤ 2 m** : annonce dès la 1re frame, urgente (miroir de la
  marche imminente, `CurbWatcher.onNear`) ;
- **3e priorité** : le poteau ne coupe ni ne chevauche P1/P2 (alertes
  `SafetyVoice`, § 16) — `ProbeWiring.p1p2Busy` (mouvement dit il y a
  moins de 4 s, marche imminente dite il y a moins de 2 s) tient la
  phrase en file (`ProbePriority`, `busy`) ;
- **dit une fois puis ré-armé** : après l'annonce, silence jusqu'à 3
  frames sans poteau (`POLE_REARM_FRAMES`) — un poteau qui clignote à la
  frontière ne rabâche pas ; cooldown 10 s inchangé ;
- **rattrapage** : la phrase douce tue part en file (une seule, la plus
  fraîche) et est redite au silence dans les 3 s
  (`ProximityVoice.flushPending`, `PENDING_TTL_MS`) — une alerte
  confirmée ne se perd plus.

Fenêtres 4 s / 2 s / 3 s provisoires, non mesurées : à valider rue (si P3
parle sur P1/P2, élargir ; s'il se tait trop, resserrer).

Verrouillé par `HazardWatchTest` (+5 : poteau 3,5 m détecté, 4,5 m muet,
2 frames doux, imminent urgent, ré-armement), `HazardConceptsTest` (+1 :
`isPole`), `VehicleStateTest` (+1 : `lastMovingAlertMs`),
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
## 21. Voitures au pluriel, poteau qui dit comment l'éviter, rebord à 0,7 m (ordre user du 31/10)

Le user marche avec l'app : plusieurs voitures en mouvement n'étaient dites
qu'au singulier (la plus proche), le poteau ne disait pas comment l'éviter,
et la marche imminente parlait à 0,4 m — trop tard pour lever le pied.
Nouveau contrat, qui amende les § 16-19 :

- **pluriel véhicules** : deux types mobiles ou plus dans la même frame =
  « 2 vehicles moving, 5 meters away. » / « 2 véhicules en mouvement, à 5
  mètres. » (générique : les pistes sont par type, pas par instance —
  deux voitures sous le même label comptent pour une, limite connue,
  distance du plus proche) ; le « Stop » suit (« Stop, 2 vehicles
  moving. ») ; le singulier est inchangé (`VehicleStateText`,
  `VehicleStateCue.movingCount`) ;
- **poteau + évitement** : « Attention au poteau, à 2 heures, passez à
  gauche. » / « Attention, pole, at 2 o'clock, pass on the left. » — le
  côté conseillé est l'opposé du poteau (poteau à gauche de l'axe = on
  passe à droite) ; axe pile ou heure inconnue = droite par défaut
  (prior, à valider rue : on serre à droite) ; doux comme imminent
  (`HazardText.polePhrase`) ; les autres mobiliers restent descriptifs ;
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
droite ; 3 attentes poteau mises au nouveau contrat), `CurbWatcherTest`
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

## 26. Voix obstacles : hiérarchie P1-P5, poteau imminent en sécurité (ordre user du 31/10)

Le user marche avec l'app : quand SAM confirme plusieurs obstacles en même
temps, l'ordre de parole n'était pas défini (trois guetteurs indépendants,
chacun sa voix) et le poteau imminent partait en synthèse réseau (lente).
Nouveau contrat, qui amende les § 16, § 18 et § 20 dans le cadre du § 22
(jamais de « Stop » hors Traversée) :

- **hiérarchie** : P1 voiture en mouvement > P2 bordure > P3 poteau > P4
  barrière > reste (arrêt, bateau, bornes…, dans l'ordre de détection) ;
- **un seul guetteur** (`LiveProbe.observeObstacles`) calcule les trois
  signaux (véhicules, bordure, mobilier) sur la même frame et parle une
  fois : les alertes de sécurité fusionnent dans l'ordre des rangs
  (« Bordure à 0,5 mètre. Poteau, passez à droite. », voix de sécurité,
  immédiat, sans « Stop »), les phrases douces aussi (voix ElevenLabs,
  file d'attente au silence) — une alerte confirmée ne se perd plus,
  elle attend son rang ; le véhicule ne fournit que du doux ici (son
  stop roulant ne se dit qu'en Traversée, § 22), P1 en tête ;
- **poteau imminent en sécurité** : à 2 m ou moins, « Poteau, passez à
  droite. » / « Pole, pass on the right. » (côté d'évitement gardé,
  heure abandonnée : à un pas, le côté prime) — deuxième consigne de
  sécurité après le rebord (§ 22), qui coupe tout ; l'imminent ne
  consomme pas le cooldown doux (la barrière suit sans attendre 10 s) ;
- **poteau avant barrière** : même frame, le poteau parle d'abord (signal
  préféré), la barrière suit en repli dès le poteau dit (compteurs de
  confirmation séparés par sorte, cooldown doux partagé) ;
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
véhicule, barrière après poteau, reste en ordre de détection, poteau
imminent, silence, fenêtre), `HazardWatchTest` (+6 : préférence poteau,
repli barrière, compteurs par sorte, consigne poteau FR/EN, cooldown
gardé, sorte divers), `HazardConceptsTest` (+2 : `isBarrier`, `kindOf`),
`VehicleStateTest` (+1 : flag `moving`) et `ProbeWiringWatchersTest`
(`p1p2Busy`/`shouldCutForHazard` supprimés, remplacés par l'arbitre).

Recette téléphone : voiture qui roule + marche + poteau cadrés ensemble
→ « Bordure à 0,5 mètre. Poteau, passez à droite. » puis « Car moving,
12 meters away. … » en doux, premier son immédiat, aucun « Stop » —
**non mesuré**, exige un APK de cette branche sur appareil réel.
