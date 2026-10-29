# Guidage « tout droit » — contrat, seuils, recette

Fiche relue dans le **code de cet arbre** le 2026-10-29 : chaque affirmation porte un
`chemin:ligne`. Le contrat HTTP de la voix (endpoint, modèle, clé) est dans
`docs/ELEVENLABS-TTS.md` ; l'annonce des positions (l'autre voix de l'app) dans
`docs/VOIX-POSITIONS.md`. Ici : **la consigne de marche** — ce que la page caméra iSee
dit à quelqu'un qui avance.

Les mesures d'appareil sont datées et attribuées. Ce qui n'a pas été relevé s'écrit
**non mesuré** — jamais deviné.

## 1. Le contrat, en une phrase

Dire à voix haute **où mettre le prochain pas** — un mot parmi quatre
(`Straight.`, `Left.`, `Right.`, `Stop.`), calculé **sur le téléphone depuis
les seuls objets SAM** — **aucun appel LLM**, le mot part en clip
pré-synthétisé **en moins de 100 ms**, jamais de synthèse réseau au moment
de parler.

## 2. Le couloir et la portée

- **Couloir** : la bande centrale, `centerX` entre 0,34 et 0,66
  (`PositionText.SIDE_EDGE`, `core/.../live/Guidance.kt`, `inCorridor`) — les seuils
  sont partagés avec les positions, mais la consigne ne dit ni cadran ni label :
  le mot suffit.
- **Portée** : `RANGE_METERS = 4f` (`Guidance.kt`) — au-delà, un objet au centre est
  trop loin pour un pas et ne bloque pas.
- **Distance inconnue = prudence** : un objet du couloir sans distance mesurée
  (classe inconnue, boîte coupée) bloque quand même, et le mot reste le côté
  de sortie (`inRange`, `nearRank`, `Guidance.kt`) — on ne juge pas le contact
  sans distance.
- **Stop** (contact mesuré) : `STOP_METERS = 0,6f` (`Guidance.kt`) — en deçà
  (mesuré), contourner n'est plus un pas : le mot est « stop ». À 1,2 m/s,
  0,6 m c'est un pas avant le contact.
- **Côté de sortie** : le moins encombré — moins d'objets à portée, sinon obstacle
  connu le plus loin, sinon l'opposé du décalage de l'obstacle dans le couloir
  (pilier légèrement à gauche ⇒ on passe à droite), pile au centre ⇒ à gauche
  (`freerSide`, `Guidance.kt`).

## 3. Ce que la consigne dit

| Situation | fr | en |
|---|---|---|
| Voie libre | Tout droit. | Straight. |
| Obstacle, sortie à gauche | Gauche. | Left. |
| Obstacle, sortie à droite | Droite. | Right. |
| Contact mesuré (≤ 0,6 m) | Stop. | Stop. |
| Décalage latéral | Gauche. / Droite. | Left. / Right. |
| Retour trottoir | Gauche. / Droite. | Left. / Right. |

- Quatre mots (ordre user du 29/10, `GuidanceWord`) : ni nom d'obstacle, ni
  cadran, ni distance, ni décalage — le mot seul, langue de la voix.
  Remplace le contournement chiffré du 29/10 (voir `docs/VOIX-DANGER.md` § 9
  et § 10).
- Les labels SAM n'étant plus dits, leur non-traduction ne touche plus le guidage
  (elle reste vraie des positions, `docs/VOIX-ANGLAIS.md`).

## 4. Quand elle parle : le mot nouveau, aussitôt

`core/.../live/GuidanceAnnouncer.kt` — machine à états **pure** (l'horodatage est fourni) :

1. **mot nouveau** — un mot différent du précédent parle **dès la 1re frame**
   (`firstSight`), **sans délai ni stabilité** : à 5 ips, attendre, c'est rater
   le pas. Le même mot ne se répète jamais (scène stable = silence, pas de
   bégaiement) ;
2. **signature** — le mot (`w|left`, `GuidanceWord.key`) : tout le reste est
   pareil, seule une consigne différente fait reparler ;
3. **priorité** — stop (contact) > côté de sortie (bouché) > décalage
   (personne qui s'approche par un côté) > retour trottoir (côté vu, sinon
   cap) > cap marché. Le bouché prime toujours : la sécurité ne négocie jamais.
4. **urgence** — seul « Stop. » coupe la lecture en cours, chat compris
   (`LiveProbeState.spokenUrgent`, vrai pour le seul mot stop) : les mots
   doux (gauche/tout droit/droite) attendent le silence
   (`ProbeWiring.observeSpoken`). Le stop interne (contact ≤ 0,6 m) est gardé
   en attendant le modèle d'urgence externe (`docs/URGENCE-EXTERNE.md).

Différence assumée avec les positions : **la voie libre se dit** (« Tout droit. »,
une fois par période libre) là où une scène vide reste muette
(`AnnouncementGate.clearScene`, via `PositionAnnouncer`). Le guidage confirme qu'il
veille ; les positions décrivent ce qu'il y a.

## 5. Câblage : une seule voix, le guidage prime

| Élément | Fichier |
|---|---|
| Décision, mot, signature | `core/.../live/Guidance.kt`, `core/.../live/GuidanceWord.kt` |
| Machine mot nouveau immédiat | `core/.../live/GuidanceAnnouncer.kt` |
| Clips `guide_*` (8 MP3, voix Laure) | `tools/voicepack-catalog.json`, `app/src/main/res/raw/vp_guide_*`, gardés par `tools/check-voicepack.py` |
| Sonde iSee : `guide` prime sur `announce` | `app/.../isee/LiveProbe.kt` (`phraseOf`), `ProbeWiring.kt` (`configureFrom`) |
| Voix : mot ⇒ clip (< 100 ms), sinon synthèse + cache | `app/.../isee/ProximityVoice.kt` (`speakWord`), pack `ProbeWiring.kt` (`guideVoicePack`) |
| Réglage | `Settings.ttsGuidanceStraight` (défaut `true`), effectif `guideStraight = bascule && ttsConfigured` (`Settings.kt`), bascule « Guider tout droit (caméra iSee) » (`app/TtsSettings.kt`) |

Le Direct et l'analyse photo gardent l'annonce des positions : le guidage ne concerne
que la page caméra iSee, la seule où l'utilisateur marche. Couper la bascule rend la
page caméra à l'annonce des positions (si elle est active) ; sans clé ElevenLabs, le
guidage est muet — par construction, pas par bug.

Aucun import réseau dans `Guidance.kt`, `GuidanceWord.kt` ni `GuidanceAnnouncer.kt` :
le guidage ne peut pas appeler le LLM, même par accident.

Tests JVM qui verrouillent le contrat : `GuidanceTest` (18), `GuidanceWordTest` (6,
dont seul-stop-coupe), `GuidanceObstaclesTest` (8, mur + liste bloquants de bout en
bout, § 8), `GuidanceAnnouncerTest` (15), `SettingsGuidanceTest` (4),
`LiveProbeGuidanceTest` (5, sonde branchée sur un faux client SAM : voie libre →
mot, obstacle → mot de sortie, mots doux + positions non urgents, stop interne au
contact mesuré = urgent, positions seules → silence sur voie libre),
`ProbeWiringSpokenTest` (3 : même mot ré-émis → une synthèse qui aboutit, mot frais →
dernier gagnant, mot doux → muet chat en lecture), `ProximityVoiceWordTest` (4 :
clip sans synthèse, synthèse mise en cache, mot unique, urgence externe coupe le
chat en clip).

## 6. Recette

1. Réglages → **clé ElevenLabs** + **ID de voix** renseignés (sinon muet) et bascule
   « Guider tout droit (caméra iSee) » **activée** ;
2. page caméra, champ vide → **une** fois « Tout droit. » (ou « Straight. »), puis
   silence ;
3. une personne entre au centre à ~2 m → « Gauche. » (ou à droite si la gauche
   est encombrée), **aussitôt** ;
4. la personne s'approche → silence (même mot) ;
5. la personne est à ~0,5 m → « Stop. », qui coupe la phrase en cours, chat
   compris ; main devant l'objectif (boîte coupée, non mesurée) → « Gauche. » ;
6. le couloir se dégage → « Tout droit. » aussitôt ;
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
- **Non mesuré** : les quatre mots sur appareil (clips < 100 ms non chronométrés ;
  les libellés restent verrouillés par les tests JVM) et le comportement en marche
  réelle (couloir qui se dégage, obstacle qui s'approche).

## 8. Mur, liste user, trous — vérifié en JVM le 2026-10-29

Chaîne `LiveProbe.publish` rejouée en JVM (`core/.../live/GuidanceObstaclesTest.kt`) :
sténopé, profondeur serveur, `placedLabels`, `decide`, mot, phrase — 8/8 sur r630.
Aucun trou de blocage trouvé : chaque concept bloque, mesuré ou par prudence.

- **Mur** : `wall` dans les défauts (`LivePrompts.kt:31-35`), `mur`/`wall` 2,50 m
  (`KnownSizes.kt:68`). À ~2 m le mur remplit l'image (boîte coupée haut/bas) :
  pas de lecture sténopé, le couloir bloque par prudence avec le côté de sortie
  (`GuidanceObstaclesTest.kt:90`). Mesuré (~3 m) : blocage + décalage chiffré
  (`:109`). À 0,5 m le mot n'est « stop » que mesuré — par la profondeur
  (`:119`, ancre personne + `with_depth`) ; au sténopé seul, un mur ou une
  personne à 0,5 m remplit le cadre (coupé) et le mot reste le côté de sortie —
  même physique que la main devant l'objectif de la recette § 6.5.
- **Liste user** : barrière 1,00 m, voiture 1,45 m, humain 1,70 m, vélo 1,10 m
  (`KnownSizes.kt:52-71`) — à 2 m, lus ≈ 2,0 m et bloquants (`:144`).
  Escaliers et marches (`stairs`, `escalier`, `step`, `steps`, `marche`) : sans
  hauteur sténopé (volontaire, `KnownSizes.kt:15-22`), lus sans distance,
  bloquants par prudence (`:161`). « marche » est hors reconnaissance
  (`traverse/StairConcepts.kt:37-38`, `CurbConcepts.kt:15-16`) mais le couloir
  bloque tout label : la reco ne conditionne pas le blocage.
- **Trou** : aucun concept, aucune taille inventée (`heightMeters("trou")` et
  `("hole")` = `null`) — la prudence bloque (`:179`). Une vraie distance
  demanderait la profondeur ou le plan de sol : suivi, pas ici.
- **Suivis notés, non faits** : `StairDetector` (Canny) non branché, seuils à
  recalibrer sur captures réelles avant tout câblage (`StairDetector.kt:44-51`) ;
  `stairs.StairConcepts` reconnaît « marche » quand `traverse.StairConcepts` le
  refuse (voix, autre agent) ; boîte coupée en haut seul : la ligne des pieds
  reste valide mais `DistanceMeter.readOne` (`DistanceEstimator.kt:156`)
  écarte toute la lecture — assouplir serait deviner, la prudence suffit.
