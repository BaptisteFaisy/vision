# Guidage « tout droit » — contrat, seuils, recette

Fiche relue dans le **code de cet arbre** le 2026-10-31 : chaque affirmation porte un
`chemin:ligne`. Le contrat HTTP de la voix (endpoint, modèle, clé) est dans
`docs/ELEVENLABS-TTS.md` ; l'annonce des positions (l'autre voix de l'app) dans
`docs/VOIX-POSITIONS.md`. Ici : **la consigne de marche** — ce que la page caméra iSee
dit à quelqu'un qui avance.

Les mesures d'appareil sont datées et attribuées. Ce qui n'a pas été relevé s'écrit
**non mesuré** — jamais deviné.

## 1. Le contrat, en une phrase

Dire à voix haute **où mettre le prochain pas** — la direction dite **en
continu toutes les 2 s**, un mot dit **en moins de 100 ms** (ordre user du
31/10) : voix TwinLite quand le couloir est bouché (toujours le côté de
sortie, même au contact — jamais de stop), mots SAM sur voie libre
(cap, décalage, retour trottoir) — calculé **sur le téléphone depuis les
seuls objets SAM** — **aucun appel LLM**, le mot part en clip
pré-synthétisé, jamais de synthèse réseau au moment de parler.

## 2. Le trait et la portée

- **Trait** : le cap droit projette le marcheur devant lui — `RAY_HALF_WIDTH_M
  = 0,25 m` de chaque côté de l'axe `x = 0,5`, à la distance de l'objet
  (`Guidance.kt`, `rayBlocks`) : large de près (à 1 m, ±0,19), fin de loin (à
  4 m, ±0,05 ; à 15 m, ±0,013). Une boîte SAM qui coupe le trait bloque, même décentrée ;
  l'objet étroit à côté libère, même centré dans l'ancienne bande. La
  consigne ne dit ni cadran ni label : le mot suffit.
- **Repli prudent** : sans bords (`x0`/`x1`) ou sans distance connus, la bande
  centrale historique (`centerX` entre 0,34 et 0,66, `PositionText.SIDE_EDGE`,
  `inCorridor`) tranche — on ne devine jamais un passage.
- **Portée** : l'axe voit loin — `AXIS_RANGE_METERS = 15f` (`Guidance.kt`,
  ordre user du 01/11) : un obstacle pile sur le trait bloque dès 15 m, le
  temps de préparer l'évitement. Le reste (caps latéraux, côtés, chemin
  optimal, décalage latéral) juge au champ proche — `RANGE_METERS = 4f`.
- **Distance inconnue = prudence** : un objet de la bande de repli sans distance
  mesurée (classe inconnue, boîte coupée) bloque quand même, et le mot reste
  le côté de sortie (`inRange`, `nearRank`, `Guidance.kt`) — on ne juge pas le
  contact sans distance.
- **Contact mesuré** : même à 0,5 m, le mot reste le côté de sortie —
  jamais de stop pour un obstacle, cap droit (on se faufile dans une
  foule) comme caps latéraux (ordre user du 31/10, `STOP_METERS`
  supprimé de `Guidance.kt`) : la voix dit directement comment l'éviter.
- **Côté de sortie** : le moins encombré — moins d'objets à portée, sinon obstacle
  connu le plus loin, sinon l'opposé du décalage de l'obstacle dans le couloir
  (pilier légèrement à gauche ⇒ on passe à droite), pile au centre ⇒ à gauche
  (`freerSide`, `Guidance.kt`).
- **Trou de grillage** : sur ce repli seul (chemin optimal sans issue), un
  grillage ouvert à gauche ou à droite (intervalle ≥ 0,80 m à ≤ 4 m,
  `FenceOpeningSignal.holeSide`) oriente la sortie vers le trou — sauf humain
  à moins de 80 cm de ce côté (`PeoplePath.clearsSide`) : l'évitement des
  humains prime toujours. Mêmes mots (voix TwinLite), mêmes clips < 100 ms.

## 3. Ce que la consigne dit

| Situation | fr | en |
|---|---|---|
| Voie libre | Tout droit. | Straight. |
| Obstacle, sortie à gauche | gauche. (TwinLite) | left. (TwinLite) |
| Obstacle, sortie à droite | droite. (TwinLite) | right. (TwinLite) |
| Obstacle au contact | gauche. / droite. (TwinLite) | left. / right. (TwinLite) |
| Décalage latéral | Gauche. / Droite. | Left. / Right. |
| Retour trottoir | Gauche. / Droite. | Left. / Right. |
| Direction stable (répétée toutes les 2 s) | Tout droit. / Gauche. / Droite. | Straight. / Left. / Right. |

- Deux voix, un seul parle à la fois (ordre user du 29/10, `TwinLiteWord` +
  `GuidanceWord`, arbitrés par `GuidanceAnnouncer`) : ni nom d'obstacle, ni
  cadran, ni distance, ni décalage — le mot seul, langue de la voix.
  Remplace le contournement chiffré du 29/10 (voir `docs/VOIX-DANGER.md` § 9
  et § 10).
- Les labels SAM n'étant plus dits, leur non-traduction ne touche plus le guidage
  (elle reste vraie des positions, `docs/VOIX-ANGLAIS.md`).

## 4. Quand elle parle : en continu, sauf overwrite

`core/.../live/GuidanceAnnouncer.kt` — machine à états **pure** (l'horodatage est fourni) :

1. **mot nouveau (overwrite)** — un mot différent du précédent parle **dès la
   1re frame** (`firstSight`), **sans délai ni stabilité** : à 5 ips, attendre,
   c'est rater le pas. Il parle aussitôt, sans attendre l'échéance de
   répétition.
2. **répétition** — la même direction se redit toutes les 2 s
   (`GuidanceAnnouncer.DEFAULT_REPEAT_MS`, comme le rouge répété) tant qu'elle
   dure, libre comme bouchée (côtés TwinLite inclus). Seul le stop ne se répète
   jamais : parlé une fois, il ne se rabâche pas.
3. **signature** — le mot (`w|left` SAM, `tl|left` TwinLite,
   `GuidanceWord.key`, `TwinLiteWord.key`) : tout le reste est pareil ;
4. **arbitrage** — bouché ⇒ voix TwinLite (côté de sortie, même au contact —
   jamais de stop), libre ⇒ mots SAM (décalage, retour trottoir, cap marché).
   Une seule porte : un seul parle à la fois, jamais de doublon. Le bouché
   prime toujours : la sécurité ne négocie jamais.
5. **urgence** — seul le stop coupe la lecture en cours, chat compris
   (`LiveProbeState.spokenUrgent`, `spokenUrgentOf`) : les mots
   doux (gauche/tout droit/droite) et le feu de Traversée attendent le
   silence (`ProbeWiring.observeSpoken`). « Stop. » (urgence externe) et
   « Stop, car moving. » (Traversée, voitures en mouvement) coupent.

Différence assumée avec les positions : **la voie libre se dit** (« Tout droit. »,
puis redit toutes les 2 s) là où une scène vide reste muette
(`AnnouncementGate.clearScene`, via `PositionAnnouncer`). Le guidage confirme qu'il
veille ; les positions décrivent ce qu'il y a.

## 5. Câblage : une seule voix, le guidage prime

| Élément | Fichier |
|---|---|
| Décision, mot, signature | `core/.../live/Guidance.kt`, `core/.../live/GuidanceWord.kt`, `core/.../live/TwinLiteWord.kt` |
| Machine direction en continu + arbitrage | `core/.../live/GuidanceAnnouncer.kt` (`AnnouncementGate` avec `repeatMs`) |
| Clips `guide_*` + `tl_*` (16 MP3, voix Laure) | `tools/voicepack-catalog.json`, `app/src/main/res/raw/vp_guide_*` + `vp_tl_*`, gardés par `tools/check-voicepack.py` |
| Sonde iSee : `guide` prime sur `announce` | `app/.../isee/LiveProbe.kt` (`phraseOf`), `ProbeWiring.kt` (`configureFrom`) |
| Voix : mot ⇒ clip (< 100 ms), sinon synthèse + cache | `app/.../isee/ProximityVoice.kt` (`speakWord`), routage `ProbeWiring.kt` (`spokenClipId`), pack `ProbeWiring.kt` (`guideVoicePack`) |
| Réglage | `Settings.ttsGuidanceStraight` (défaut `true`), effectif `guideStraight = bascule && ttsConfigured` (`Settings.kt`), bascule « Guider tout droit (caméra iSee) » (`app/TtsSettings.kt`) |

Le Direct et l'analyse photo gardent l'annonce des positions : le guidage ne concerne
que la page caméra iSee, la seule où l'utilisateur marche. Couper la bascule rend la
page caméra à l'annonce des positions (si elle est active) ; sans clé ElevenLabs, le
guidage est muet — par construction, pas par bug.

Aucun import réseau dans `Guidance.kt`, `GuidanceWord.kt`, `TwinLiteWord.kt` ni
`GuidanceAnnouncer.kt` : le guidage ne peut pas appeler le LLM, même par accident.

Tests JVM qui verrouillent le contrat : `GuidanceTest` (18), `GuidanceRayTest` (8,
trait : précision, rappel, affinage au loin, replis, producteurs, voix),
`GuidanceWordTest` (6, dont seul-stop-coupe), `TwinLiteWordTest` (9, dont
stop-coupe FR/EN), `GuidanceObstaclesTest` (8, mur + liste bloquants de bout en
bout, § 8),
`GuidanceAnnouncerTest` (dont l'arbitrage des deux voix, la répétition 2 s sauf
overwrite et le stop unique), `GuidanceAnnouncerSteeringTest` (cap suivi + répétition),
`SettingsGuidanceTest` (4), `LiveProbeGuidanceTest` (5, sonde branchée sur un faux
client SAM : voie libre → mot, obstacle → voix TwinLite, mots doux + positions
non urgents, contact mesuré = contournement non urgent, positions seules
→ silence sur voie libre),
`ProbeWiringSpokenTest` (même mot ré-émis → une synthèse qui aboutit, mot frais →
dernier gagnant, mot doux → muet chat en lecture, mots → clips),
`ProximityVoiceWordTest` (clip sans synthèse, synthèse mise en cache, doux répété,
stop unique, urgence externe coupe le chat en clip).

## 6. Recette

1. Réglages → **clé ElevenLabs** + **ID de voix** renseignés (sinon muet) et bascule
   « Guider tout droit (caméra iSee) » **activée** ;
2. page caméra, champ vide → « Straight. » (ou « Tout droit. »), redit toutes
   les 2 s tant que le champ reste vide ;
3. une personne entre au centre à ~2 m → « gauche. » (TwinLite — ou à droite
   si la gauche est encombrée), **aussitôt** (overwrite), puis redit toutes les 2 s ;
4. la personne s'approche → même mot redit toutes les 2 s (continu) ;
5. la personne est à ~0,5 m → « gauche. » redit (jamais de stop) ;
   main devant l'objectif (boîte coupée, non mesurée) → « gauche. » ;
6. le couloir se dégage → « Tout droit. » aussitôt, puis redit toutes les 2 s ;
7. bascule **coupée** → retour aux annonces de positions, voie libre muette.

## 7. État des mesures (28/10/2026, appareil Redmi 2312DRA50G)

Relevé du 28/10/2026 sur le build `1.0.179-399a512` (= `master` HEAD du moment,
contenant le guidage) : Redmi 2312DRA50G, Android 15, écran 1220 × 2712, Clé
ElevenLabs + voix renseignées, bascule « Guider tout droit (caméra iSee) »
**activée** (captures d'écran des Réglages à l'appui).

- Lancement à froid → tap central → enregistrement : `iSee` au premier plan,
  aucune erreur fatale, aucun `live KO`.
- SAM en direct : `première analyse : 3 objets, 671 ms`, puis `masques décodés`
  en continu (2–17 ms) ; panneau debug `SAM · 7,1 ips · 138 ms · 3 objets`
  avec pastilles de distance (capture à l'appui).
- Consigne parlée : `cache/isee-play/voice.mp3`, **41 838 octets**, écrit pendant
  l'enregistrement — donc synthétisé par ElevenLabs puis joué (le lecteur est
  unique et l'intro passe par `playFile`, sans écrire ce fichier ; le guidage
  prime sur les positions quand sa bascule est active, donc ce fichier est une
  consigne de marche).
- **Non mesuré** : les mots des deux voix sur appareil (clips < 100 ms non
  chronométrés ; les libellés restent verrouillés par les tests JVM) et le
  comportement en marche réelle (couloir qui se dégage, obstacle qui s'approche).

## 8. Mur, liste user, trous — vérifié en JVM le 2026-10-29

Chaîne `LiveProbe.publish` rejouée en JVM (`core/.../live/GuidanceObstaclesTest.kt`) :
sténopé, profondeur serveur, `placedLabels`, `decide`, mot, phrase — 8/8 sur r630.
Aucun trou de blocage trouvé : chaque concept bloque, mesuré ou par prudence.

- **Mur** : `wall` dans les défauts (`LivePrompts.kt:52-57`), `mur`/`wall` 2,50 m
  (`KnownSizes.kt:104`). À ~2 m le mur remplit l'image (boîte coupée haut/bas) :
  pas de lecture sténopé, le couloir bloque par prudence avec le côté de sortie
  (`GuidanceObstaclesTest.kt:90`). Mesuré (~3 m) : blocage + décalage chiffré
  (`:109`). À 0,5 m le mot reste le côté de sortie (« right. », voix
  TwinLite), mesuré ou non — jamais de stop ; la mesure profondeur
  (`:119`, ancre personne + `with_depth`) sert le décalage et
  l'obstacle élu — même physique que la main devant l'objectif de la
  recette § 6.5.
- **Liste user** : barrière 1,00 m, voiture 1,45 m, humain 1,70 m, vélo 1,10 m
  (`KnownSizes.kt:52-71`) — à 2 m, lus ≈ 2,0 m et bloquants (`:144`).
  Escaliers et marches (`stairs`, `escalier`, `step`, `steps`, `marche`) : sans
  hauteur sténopé (volontaire, `KnownSizes.kt:15-22`), lus sans distance,
  bloquants par prudence (`:161`). « marche » est reconnu
  (`stairs/StairConcepts.kt`, `CurbConcepts.kt:15-16`) mais le couloir
  bloque tout label : la reco ne conditionne pas le blocage.
- **Trou** : aucun concept, aucune taille inventée (`heightMeters("trou")` et
  `("hole")` = `null`) — la prudence bloque (`:179`). Une vraie distance
  demanderait la profondeur ou le plan de sol : suivi, pas ici.
- **Suivis notés, non faits** : `StairDetector` (Canny) branché à la voix
  escaliers (`StairCannyCount`, 30/10), seuils recalibrés téléphone 256×103
  (30/10, validation live recommandée) ;
  doublon `traverse.StairConcepts` supprimé le 01/11 (un seul concept `stairs`,
  qui reconnaît « marche ») ; boîte coupée en haut seul : la ligne des pieds
  reste valide mais `DistanceMeter.readOne` (`DistanceEstimator.kt:156`)
  écarte toute la lecture — assouplir serait deviner, la prudence suffit.
