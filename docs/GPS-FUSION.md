# Fusion GPS + obstacles — une voix, meilleur chemin temps réel

Ordre user du 29/10 : le guidage GPS et les obstacles SAM sont liés — une
seule voix (Laure), un meilleur chemin calculé en temps réel compte tenu des
obstacles. Le local contourne, le global replanifie.

## 1. Une seule voix

Les manœuvres GPS (« Dans 100 mètres, tournez à gauche ») passent par la
voix Laure (`ProximityVoice`), comme les mots et les « Attention … » : plus
de voix robot par-dessus. `VoiceNavEngineDriver.say` garde la même politique
(`VoiceNavAnnouncer`) et la même urgence (`SpeechArbitration` : sortie de
trace et recalcul coupent, le reste attend) — seul le tuyau change
(`speakRoute`, câblé par `IseeVoiceNav` ; `null` = voix système, repli).
`stopRoute` coupe la parole route en fin de guidage.

Manœuvre perdue (voix occupée) : pas de file — la manœuvre suivante
ré-émet (loin puis près), et un virage raté sort de trace → recalcul.
Le détour ne perd jamais le voyageur, il le replanifie.

## 2. Le cap route marche le couloir

`driver.onRouteCap` → sonde (`setRouteCap`/`clearRouteCap`, câblé par
`IseeViewModel` — émis dans le vide jusque-là) : tant qu'il est posé, le
couloir marché est celui de la route (`RouteSteeringFusion` : bouché →
passage conseillé, sinon retour trottoir, sinon cap route).

## 3. Détour subi → recalcul Valhalla

La sonde publie le détour (`LiveProbeState.detour`, faux en Traversée),
`observeDetour` le pousse au pilote (`IseeVoiceNav.onProbeDetour`), et
`DetourReroute` (8 s subies continues → feu, un par minute au plus) déclenche
le même recalcul qu'une sortie de trace : Valhalla depuis la position GPS
temps réel, « Nouvel itinéraire » parlé. Pas de `avoid_locations` : le
transitoire (personne, trottinette) ne mérite pas d'éviter le tronçon, la
nouvelle position suffit.

Non-fait volontaire : la voix rouge/vert du mode Traversée reste la sienne
(un mode distinct, pas une manœuvre).

Verrouillé par `DetourRerouteTest` (5), `LiveProbeGuidanceTest` (6, dont
détour publié) ; le câblage pilote (`say`, `onDetour`) est une colle fine
vérifiée sur téléphone (guidage réel : départ parlé Laure, détour de 8 s →
« Nouvel itinéraire »).
