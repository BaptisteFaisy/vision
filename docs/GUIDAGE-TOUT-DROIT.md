# Guidage — spec v4 (contrat actuel)

> **Spec v4, 01/11/2026.** Le guidage, c'est trois voix : le GPS (manœuvres
> 11 + rappel 4, synthèse réseau), le rail trottoir (10, clips), et le
> repli (12, clips). Les mots SAM/TwinLite (« gauche. », « Tout droit. »,
> « obstacle, arrêtez-vous. »), le contournement chiffré et le stop ont
> vécu : l'échelle 0 à 12 les remplace.
> Historique pré-v4 : `git show 4a3c2bcf:docs/GUIDAGE-TOUT-DROIT.md`.

## 1. Le contrat, en une phrase

Dire à voix haute **où mettre le prochain pas** — consigne GPS quand il y
a un itinéraire (11), rappel de la consigne après 15 s de silence (4),
correction de dérive trottoir (10), et à défaut (rien à dire + silence
300 ms) un repli gauche/droite/tout droit selon GPS + obstacles (12) —
GPS en synthèse réseau, le reste en clips anglais, **jamais deux voix en
même temps** (bus vocal, le plus petit gagne).

## 2. GPS : manœuvres (11) et rappel (4)

- **Manœuvres et événements (11)** : le moteur Valhalla parle aux
  événements (préavis, ordre de virage, sortie de trace, recalcul,
  arrivée) par la voix réseau ElevenLabs. Une seule voix : GPS et
  obstacles partagent le même tuyau.
- **Rappel (4)** : si **silence depuis 15 s ou plus**, répète la **dernière
  consigne + la distance restante** (« … 50 meters remaining. », synthèse,
  anglais). Un événement moteur recale la cadence (le rappel se tait sur
  un fix qui a parlé). Sans consigne encore parlée : repli ligne droite
  (« Continue straight ahead. … remaining. »).
- Le 15 s est le **déclencheur**, pas une fenêtre d'antirépétition (clé
  `gps-reminder` : sans délai).
- Sans `routeBaseUrl` : pas de guidage (« Guidance unavailable. »), **pas
  de repli Google Maps** (supprimé).
- En traversée : GPS muet (ni 11, ni 4). La parole du user (1) coupe la
  voix route ; seul le niveau 0 parle par-dessus.

## 3. Rail trottoir (10, comportement actuel)

Le trottoir n'est pas un obstacle : c'est un **rail**. L'app ne parle que
si l'utilisateur n'est plus dessus (dérive, contournement) — trottoir
droit devant = aucun mot. Annonces au changement seul (« Sidewalk. »,
« Sidewalk left. », « Sidewalk right. », « Sidewalk across. » — formes
courtes § 46, pastille 500 ms), silence 10 s (`sidewalk`). Coupé en traversée.

## 4. Repli (12) : gauche, droite ou tout droit

- **Déclencheur** : rien à dire (aucun niveau 0 à 11 ne parle) **et silence
  de 300 ms** sur le lecteur partagé.
- **Consigne** : « Left. » / « Right. » / « Straight. » (clips `guide_left`,
  `guide_right`, `guide_straight`) selon **GPS + obstacles** : cap GPS
  quand il y a un itinéraire, côté de sortie (le moins encombré) sinon.
- Une fois par période de silence (pas de boucle : le repli ne se
  re-déclenche que sur un nouveau silence après une parole).
- ⚠️ **Non implémenté** (constante `FALLBACK = 12` seule, aucune logique
  300 ms nulle part — voir `docs/REVUE-V4.md` R-12). À implémenter avant
  la première prise rue (item 11 de `docs/RECETTE-RUE.md`).

## 4 bis. Écart latéral : un seul juge (`ClearPath`, 04/11)

Deux voix disent « Left. » / « Right. » : le guetteur (niveaux 3/5/8, la voix
qu'on entend) et le repli (12). Elles lisent maintenant la **même réponse** :
`ClearPath.view`, calculée une fois par frame dans `GuidanceAnnouncer`, publiée
dans `LiveProbeState.clearView`, lue par `HazardSignal.steeredBy`.

- **Intrusion** : mesurée en mètres sur les **bords de boîte**, de l'axe marché
  au bord de l'objet, dès `ClearPath.ORDER_RANGE_M` (10 m, ordre user du 04/11), pour
  tous les obstacles. Écart voulu : 1 m aux
  corps et aux fixes. L'ordre tient tant que l'écart manque : c'est la fin de
  l'esquive, pas la sortie d'une bande d'image.
- **Côté** : vers le plus grand espace libre (`WidestGap`, ordre user du 04/11), pour
  tous les obstacles : personnes, murs, poteaux, barrières. La largeur d'image est
  découpée en trous entre les obstacles, épaules comprises ; seul compte ce qu'on va
  atteindre, jusqu'à 2 m derrière l'obstacle (jamais moins de 4 m). Un objet sans
  distance ni coupé par le bas ne compte pas. Une bordure ne se contourne pas.
  Bornée au trottoir, sauf si la borne renvoie vers une personne. Obstacle sans
  distance ou frame bouchée : trou de grillage, sinon le côté le moins encombré.
- **Égalité** : à droite, sans tenue de côté (ordre user du 03/11), si les deux trous
  sont à 6 % d'image près (corps à ±3 % de l'axe) et que la droite a de quoi passer.
- **Balancement** : il s'efface dans la mesure, pas dans la décision. Le côté est
  recalculé à chaque frame sur le centre moyen de la piste, sur 1 s (`steadyX`,
  `ClearPath.axisX`). Essai Samsung du 04/11 : changements de côté 15 → 4 sur 176
  frames, sans marge. Une marge de 15 % sans ce lissage disait droite vers un
  obstacle décalé à droite.
- **Mur longé** (`ClearPath.isRail`) : objet long, boîte qui sort du cadre sur un
  côté sans franchir l'axe. Son bord intérieur est un point de fuite : seule sa
  distance au bord du cadre le situe. Il ne parle que sous 0,5 m. S'il franchit
  l'axe, le cap finit dedans : c'est un obstacle.
- **Longé ou de face** : une boîte seule ne sépare pas un mur longé d'une barrière
  dont on frôle le bout. Le mur reste à la même distance quand on avance, la
  barrière se rapproche : à 0,7 m/s de fermeture sur 800 ms de mesures au moins
  (`closingSpanMs`), l'objet n'est plus longé. Le lacet d'une marche (≈ 5°) balaie
  le point de fuite : il est toléré, sauf sur un objet mesuré d'approche inconnue.
- **Sans distance** : un objet coupé par le bas est proche (`UNSEEN_DEPTH_M`,
  1,5 m) ; un objet aux bords connus reste dans le plan à la portée, il ne
  l'annule plus. Sans bords ni distance, le plan garde son repli.
- **Passage piéton** : hors Traversée, l'alignement (niveau 2) se tait pendant un
  ordre d'écart, l'annonce « Crosswalk. » reste. Il disait « Left. » sans voir les
  obstacles, et écrasait les mots d'esquive (46 sur 56 s le 04/11).
- **Repli du guetteur** : quand le chemin ne conseille rien, l'opposé du centre,
  avec la même règle plein axe à droite (5 % du cadre autour de l'axe).
- **Serveur** : `resolveDecision` est local d'abord. `passage` ne juge que des
  centres en fraction d'image, sans mètres ni murs : son « libre » ne tait plus
  un bouché local, et il n'ajoute un bouché que si le local n'en a pas.
- **Hors périmètre** : Traversée et dedans gardent le guetteur d'avant
  (`hazardSignalOf`). La sécurité bordure possède sa frame, y compris entre deux
  redites, et retire son côté de la place libre.

Le palier lointain ne change pas : corps et poteau jusqu'à 15 m dans la bande
centrale, avec le côté du chemin lointain quand il en a un. Limite connue :
l'obstacle sorti du cadre n'a plus de mémoire, tout se juge dans l'image.

## 5. Ce que le guidage n'est plus (supprimé en v4)

- Mots SAM/TwinLite à chaque frame, arbitrage bouché/libre, « obstacle,
  arrêtez-vous. », contournement chiffré, stop : **supprimés** (échelle).
- Filtre tête des panneaux, décalage latéral chiffré, TwinLiteNet,
  urgence externe : **supprimés ou hors spec**.
- Voix FR de guidage : **supprimée** (clips anglais, GPS en anglais).

## 6. Câblage

| Élément | Fichier |
|---|---|
| Niveaux 0-12, décision frame, vœux | `app/.../isee/ProbePriority.kt` (`OutdoorLevel`, `decideFrame`, `offer`) |
| Bus vocal (plus petit gagne, écrasé jeté) | `app/.../isee/VoiceBus.kt` (`post`, `userSpeaking`, `chatSpeaking`) |
| Antirépétition (table centrale) | `core/.../live/AntiRepeat.kt` (`allow`, clés § 5 voix) |
| Clips anglais (catalogue + lecteur) | `core/.../speech/IseeClipsEn.kt`, `tools/isee-clips-en-catalog.json`, `app/.../isee/IseeClipPlayer.kt` |
| Écart latéral : intrusion, côté, mur longé | `core/.../live/ClearPath.kt`, `GuidanceExit.kt`, `PeoplePath.kt` |
| Voix de proximité (sans file) | `app/.../isee/ProximityVoice.kt` (`speak`, clips d'abord) |
| Moteur GPS + rappel 15 s | `app/.../voicenav/VoiceNavEngineDriver.kt`, `VoiceNavGpsVoice.kt` (`gpsReminderPhrase`) |
| Chat : attente + reprise | `app/.../isee/ChatWaitResume.kt` (rang 9) |
| Verdict traversée 100-102 | `core/.../crossing/CrossingVerdict.kt`, `app/.../isee/CrossingVerdictWiring.kt` |
| Routeur dictée (11 ordres) | `core/.../speech/DictationOrder.kt`, `app/.../isee/vm/IseeDictationRouter.kt` |
| Listes SAM 20 dehors + 10 dedans | `core/.../live/LivePrompts.kt` (`default`, `indoor`, `forMode`) |

Contradictions résiduelles entre ces fichiers : `docs/REVUE-V4.md`.
