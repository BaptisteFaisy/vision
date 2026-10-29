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

## 9. Contournement chiffré remplace le « Stop. » (ordre user du 29/10)

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
