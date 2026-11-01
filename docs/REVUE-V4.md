# Revue v4 — contradictions résiduelles entre les 14 branches

> Relevé du 01/11/2026 (~16h45), relu dans le **code des branches**
> (aucun rapport frère fourni — pas de section `Resolved`, pas de handoff :
> la revue porte sur les refs `feat/isee-*` elles-mêmes, commits + WIP).
> Les branches bougent en parallèle : états « committé / WIP / vide » à
> l'heure du relevé — le parent re-vérifie avant d'intégrer.
> Base frères : `6700e26d`. `origin/master` : `4a3c2bcf` (≈ 11 commits
> d'écart, lunettes D1-D5 + tests + ProbeWiring : **rebase nécessaire**
> avant toute intégration). Cette branche (`feat/isee-docs-revue`) est sur
> `origin/master`.
>
> Convention : `R-0x` bloquantes (l'intégration produit un bug ou viole la
> spec si non tranché), `R-1x` majeures, `R-2x` trous, oublis et collisions,
> `§ D` décisions proposées au parent. Chaque point cite branche + fichier
> + ligne. 28 points au total (R-01 à R-29, R-26 non attribué).

## Bloquantes

### R-01 — Deux `AntiRepeat` incompatibles

- `feat/isee-antirepetition` (`cf30e914`) :
  `core/.../live/AntiRepeat.kt` (179 l.) — `allow` (:130), consts `KEY_*`,
  `gps-reminder` → 0 (:156, sans délai), `crosswalk-*` → `ONCE_MS` (:154),
  clé `misc` (:86), `forget` (:172), clés paramétrées `base:état`.
- `feat/isee-echelle-overwrite` (`850f008d`) :
  `app/.../isee/AntiRepeat.kt` (95 l.) — `allow` (:81), consts sans préfixe,
  `gps-reminder` → **15 000** (:61), `crosswalk-*` → **0** (:66-68, jamais
  tus), pas de `misc` (niveau 7 entier sous `pole`), pas de `forget`, pas
  de `verdict:état`.
- Les deux compilent (packages distincts) mais deux tables vivent : selon
  l'import, le rappel GPS est bridé à 15 s ou libre, le passage est « 1
  fois » ou libre, le verdict n'a pas de changement immédiat.
- Usages echelle à reporter : `ProbePriority.kt:104,120,121,127,137,146`.

### R-02 — Niveau 0 : « Car moving. » au lieu de « Car. »

- `echelle` : `core/.../traverse/VehicleStateText.kt` — 6 noms parlés
  (:26-31, car/bus/truck/moto/bike/scooter), `movingPhrase` (:65 :
  « Car moving. » / « Voiture en mouvement. »), `movingPluralPhrase` (:75 :
  « N vehicles moving. »), clips `vehicle_*_moving` (:93).
  `vehicleWish` (`ProbePriority.kt:102-105`) poste `cue.phrase` tel quel.
- Spec + `clips-en` (`IseeClipsEn.kt:27-30`) : « Car. » / « Cars. », clips
  `car` / `cars`. Présence seule.
- Conséquence : phrases FR possibles au niveau 0 + clips `vehicle_*`
  (ancien pack) au lieu du catalogue v4.

### R-03 — Verdict : texte et clipIds incompatibles avec le catalogue

- `verdict` : `core/.../crossing/CrossingVerdict.kt:36-52` — « Cross. » /
  « Don't cross. », clips `verdict-cross` / `verdict-dont-cross` (tirets).
- `clips-en` : `IseeClipsEn.kt:115-118` — « **Cross now.** » /
  « Don't cross. », ids `verdict_cross` / `verdict_wait` (underscores).
- Avec la politique clips-en (inconnu = silence, `IseeClipPlayer.kt:48`),
  le verdict branché sur le lecteur v4 est **muet** ; avec la politique
  echelle (synthèse sinon), il part en **réseau** (violation § 0 voix).

### R-04 — Politique de repli contradictoire (silence vs synthèse)

- `echelle` : `ProximityVoice.kt` (KDoc :13-16) — « clip enregistré
  d'abord, synthèse ElevenLabs sinon ».
- `clips-en` : `IseeClipPlayer.kt:24-33` — « clip manquant ou inconnu =
  silence, jamais de synthèse de repli ».
- Toute phrase sans clip (verdict R-03, niveaux R-24, find R-09) parle
  réseau avec l'une, se tait avec l'autre. Incompatible.

### R-05 — Murs et clôtures : niveau 7 au lieu de 4 (deux sources de vérité)

- `echelle` : `hazardWish` (`ProbePriority.kt:112-123`) recalcule le niveau
  depuis `HazardKind` : `OTHER` → 7 (`POLE`). Or `OTHER` regroupe « poteau,
  clôture, mur, lampadaire, panneau… » (`seuils`,
  `HazardConcepts.kt:199-200`).
- `seuils` : `HazardCue.level` / `antiRepeatKey` (`HazardWatcher.kt:124-126`,
  `LEVEL_FENCE = 4` :417, `KEY_FENCE` :429) — la source correcte.
- Tel quel : clôtures et murs annoncés niveau 7 avec 2 s au lieu de
  niveau 4 avec 4 s.

### R-08 — Ordres find : deux parseurs, deux doctrines

- `routeur` : `DictationOrders.parse` (`DictationOrder.kt:63-88`) — préfixe
  « mode » obligatoire, strict, le reste part au LLM.
- `find` (WIP) : `FindIndoorOrder.parse` (`FindIndoor.kt:88-100`) — sans
  « mode », verbes `find`/`locate`, articles FR élidés, « please » ignoré.
  « mode find my keys » y rend `null` (mot 1 = « mode », pas un verbe).
- Pire : `isUnknownFind` (`FindIndoor.kt:113-117`) — « find the elephant »
  est **consommé avec un message d'aide, jamais une question LLM** :
  contradiction directe avec la spec (« tout le reste = question LLM »)
  et avec le routeur.

### R-09 — Clips find : 70+ ids hors catalogue → recherche muette

- `find` (WIP) : `FindIndoorClips` (`FindIndoor.kt:183-213`) — clips
  pré-composés `find_start_keys`, `find_found_keys_9` (« Keys at 9
  o'clock. »), `find_off` (« Search cleared. »), bases `medicine_box`,
  `wall`, `person`…
- `clips-en` : morceaux `find_looking_for` (« Looking for ») + `keys` /
  `medbox` / `walls` / `human_person`… + `found` + `clock_*`,
  `find_stopped` (« Search stopped. »), assemblés par `speakSequence`.
- Aucun id find n'est au catalogue (`isee-clips-en-catalog.json`) : via
  `IseeClipPlayer`, toute la recherche dedans est muette. Textes aussi
  divergents (« Search cleared. » vs « Search stopped. »).

### R-10 — Confirmations d'ordres en synthèse réseau FR

- `routeur` (WIP) : `speakNavigation` (`IseeViewModel.kt:467-472` —
  `voice.synthesize`, « ElevenLabs + lecteur partagé ») ; `onFindOff`
  (:219-223) parle `DictationRouterText.findStopped`
  (`IseeDictationRouter.kt:81-87` : « Recherche arrêtée. » / « Search
  stopped. ») par ce chemin. Tous les effets (`onOutdoor`…:210-227)
  rejouent des ordres historiques qui confirment par synthèse.
- Spec : confirmations en **clips anglais** (`outdoor_on`, `find_stopped`,
  `nav_stopped`…), **zéro réseau hors chat/GPS**. Chaque ordre exécuté
  fait aujourd'hui un appel réseau non-chat non-GPS.

### R-24 — Phrases seuils FR non clippables (aucun clip EN)

- `seuils` : `HazardText.personPhrase` (`HazardWatcher.kt:153-166` :
  « Attention à la personne, à 2 heures, à 3 mètres, passez à gauche. »),
  `StairText` (`StairWatcher.kt:43` : « Escalier montant à 4 mètres. » /
  « Stairs up **at** 4 meters. », :86 « Plus que N marches. »),
  `CurbText`, `SidewalkText`… — FR ou EN-synthèse, jamais des clips.
- Catalogue : « Person. », « Stairs up**,** 4 meters. », « Curb up. »…
  Aucune phrase seuils n'a de clip (même le jalon EN diffère : « at » vs
  « , »). Via echelle → synthèse réseau FR (double violation) ; via
  clips-en → silence.
- De plus « passez à droite/gauche » (consigne d'évitement) n'a pas de
  clip et n'est pas dans la spec du niveau 2 (phrase + cadran + distance).

## Majeures

### R-06 — Doublon niveaux traversée

- `CrossingLevel` (`echelle`, `ProbePriority.kt:67-76`) vs `CrossingLevels`
  (`verdict`, `CrossingVerdict.kt:11-20`) : mêmes constantes 100/101/102,
  deux objets. Unifier.

### R-07 — Deux antirépétitions du verdict

- `CrossingVerdictAnnouncer` (`verdict`, `CrossingVerdict.kt:94-142`,
  `REPEAT_MS = 3 s`, changement immédiat maison) vs clé `verdict:état` de
  la table centrale (`antirepetition`). Même règle, deux mécanismes,
  personne n'utilise `verdict:état`. Replier sur la table.

### R-13 — Foule : reconnue par seuils, plus suivie par prompts (+ ajouts hors-spec)

- `seuils` : `HazardConcepts.kt` — « Foule (audit du 01/11, **gardé v4**) » :
  poussette, fauteuil roulant, trottinette, chien, enfant + chat, poubelle,
  souvenirs… et **ajoute** `beanie`/`bonnet`, `benne`/`dumpster`,
  `socks`/`chaussettes` **dehors**.
- `prompts` (WIP) : dehors 16 **sans** `stroller`, `child`, `dog`,
  `electric scooter`, `beanie` (« plus les anciens doublons (tête du feu,
  trottinette, poussette, enfant, chien, bonnet) »).
- SAM ne segmentera plus la foule → phrases mortes. Et `socks` dehors
  contredit la spec (chaussettes = dedans uniquement) ; `beanie`/`benne`
  sont absents de la spec dehors.
- `morts-divers` (WIP) se range du côté seuils : `HazardConcepts.kt:63-66`
  (poussette nommée) et `:125` (`stroller` → `PERSON`) gardent la foule —
  2 branches contre 1 (`prompts`). En revanche `morts-divers` purge
  `KnownSizes` (bus, camion, moto, vélo, trottinette, borne, portail,
  arbre — `KnownSizes.kt:29,140`), ce que `seuils` n'avait pas fait :
  convergent, à garder.

### R-14 — Direct : anciens ordres sans « mode » toujours actifs

- `routeur` (WIP) : `acceptNavOrder` (`IseeViewModel.kt:191-195`) — routeur
  **puis** `voiceNav.run` **puis** `steering.runCrossing` en replis.
  « je veux aller à X » et « traverser » sans « mode » déclenchent encore
  GPS et traversée sur le direct, alors que l'accueil est routeur-exclusif
  (`transcribeAndAnswer`). Le direct viole « tout le reste = question LLM ».

### R-15 — BUG : « mode glasses off » peut ouvrir la session

- `routeur` (WIP) : `onGlasses(on)` (`IseeViewModel.kt:226`) rejoue
  `glassesOrder.run("iSee")` pour on **et** off (bascule, pas d'API
  explicite). Sans garde d'état, « off » sur session fermée l'ouvre.

### R-18 — Réf fausse : « VoiceBus vit sur la branche routeur »

- `verdict` : `CrossingVerdictWiring.kt:20`. Faux : `VoiceBus`
  (`app/.../isee/VoiceBus.kt`) est sur `feat/isee-echelle-overwrite`
  (`850f008d`). Le parent branche `poster` sur `VoiceBus.post` (echelle).

### R-21 — Types véhicules non restreints côté watcher

- `echelle` : `vehicleWish` (`ProbePriority.kt:102-105`) poste tout
  `cue.moving`, quel que soit le type ; `VehicleStateText` garde 6 noms
  (`VehicleStateText.kt:26-49`, bus/truck/moto/bike/scooter) ;
  `KnownSizes.kt:137-140,201-204` garde leurs tailles.
- Prompts sortis (`prompts` WIP) mais watcher non restreint : un « bus »
  via le champ Follow parle « Bus en mouvement » au niveau 0. Restreindre
  à `isCarLabel` (`CrossingVerdictRule`, `CrossingVerdict.kt:64-68`).
- `gyro` : `CrossingVehicleWatch.update` garde le repli cycles
  (`CrossingVehicleVoice.kt:74`, « Attention, vélo en mouvement »).

### R-23 — Clés dedans sans effet

- `find` (WIP) : `FindIndoorRepeat` (`FindIndoor.kt:160-172`) — `find-found`
  2 s, `indoor-doorwall` 4 s. Clés **inconnues** des deux `AntiRepeat`
  (inconnue = toujours autorisée) → durées sans effet. Ajouter au tableau
  unifié.

## Trous et oublis

### R-11 — TROU : personne ne rend le chat et le GPS muets en traversée

- `verdict` : « Chat et GPS sont tus par le routeur en traversée, pas ici »
  (`CrossingVerdictWiring.kt:49`) ; `CrossingExclusivity`
  (`CrossingVerdict.kt:184-193` : `chatMuted`/`gpsMuted`).
- `routeur` (WIP) : `IseeDictationRouter.run` (`IseeDictationRouter.kt:58-72`)
  route les ordres, **ne mute rien**. Câblage parent manquant.

### R-12 — TROU : repli niveau 12 non implémenté

- Constante `FALLBACK = 12` seule (`ProbePriority.kt:59`, défaut de
  `speak` dans `ProximityVoice.kt:86`). Aucune logique « rien à dire +
  silence 300 ms → gauche/droite/tout droit » (grep `repli|FALLBACK`
  vide sur echelle/seuils/voix-gps/routeur). Aucune mission ne le
  revendique. La recette rue item 11 échouera jusqu'à son implémentation.

### R-16 — TROU : arbitrage des modes (outdoor/indoor/crossing) inexistant

- `VoiceBus` (`echelle`, `VoiceBus.kt:36-108`) : pas de mode ; le
  `crossingActive` est passé par les vœux (`hazardWish`, `curbWish`).
- `find` (WIP) : `FindIndoorLevels` (`FindIndoor.kt:133-151`, FIND=0,
  USER=1, HUMAN=2, CHAT=9, DOOR_WALL=13) — « Le routeur arbitre par mode
  exclusif : dedans, seuls ces cinq crans parlent ».
- Le routeur-dictée ne fait que parser des ordres vocaux : **aucun
  arbitrage de mode n'existe**. Qui tait les producteurs dehors en indoor
  (et inversement) ? Personne. (Le cran 13 porte/murs est une extension
  hors spec 0-12/100-102 — acceptable si documentée, mais le bus
  l'ignore.)

### R-19 — Google Maps : décâblé, suppressions en cours (WIP)

- `routeur` (WIP) supprime `NavigationHook` du ViewModel (diff
  `IseeViewModel.kt`) — le handoff Maps ne part plus de l'accueil.
- `morts-divers` (WIP) supprime `GoogleMapsSettings.kt` ;
  `tests-contrats` (WIP) supprime `GoogleMapsSeedTest`,
  `NavigationLauncherTest`, `MapsUriTest`.
- **Restent** (relevé sur les WIP) : `NavigationLauncher.kt`, `MapsUri.kt`,
  usages (`IseeVoiceNav.kt`, `VoiceNavSession.kt`), `strings nav_*`, docs
  `NAVIGATION-VOCALE.md` (MVP Maps) et `NAVIGATION.md`. À finir avant
  l'intégration.

### R-20 — Code mort : suppressions en cours, trous béants

- `morts-divers` (WIP) supprime les clips `vp_cycle_*` (vélo/trottinette,
  8 MP3) et `vp_hazard_bollard/gate_*` (borne/portail, 4 MP3) + catalogue
  ; `morts-voix` (WIP) supprime `VoiceOfflineFallback.kt` (hors-ligne),
  `GuidanceStarvation.kt`, `IseeVoiceGate.kt`, `docs/URGENCE-EXTERNE.md`,
  et le repli TTS système de `ProximityVoice` (import `SystemTtsEngine`
  retiré — mais voir R-29 : collision avec la réécriture echelle).
- **Restent vivants** (ni supprimés ni décâblés sur aucune branche) :
  `CycleWatcher`/`CycleConcepts` (les vélos parlent encore via
  `CrossingVehicleVoice` + `LiveVehicleVoice`), `TraverseVeto` /
  `TraversePipeline` + voix « Vert, traversez. » (**double voix FR + EN**
  en traversée si le verdict v4 est câblé sans les décâbler).

### R-22 — États des branches à l'heure du relevé (16h45)

- **Committés** (9) : echelle (`850f008d`), antirepetition (`cf30e914`),
  seuils (`eb1703b2`), verdict (`7c09364a`), voix-gps (`45f64960`),
  clips-en (`accecb49`), attente-chat (`25e3922d`), prompts-listes
  (`3ce22146` — committé pendant cette revue), gyro (`350f02a7` +
  `db0b9547` qui intègre le WIP `lastLumaW/H`).
- **WIP non committés** (5) : `routeur-dictee` (9 fichiers), `find-indoor`
  (13 fichiers), `morts-divers` (33 fichiers), `morts-voix` (9 fichiers),
  `tests-contrats` (8 fichiers + `core/.../spec/` nouveau). Zéro branche
  vide — mais les WIP bougent : le parent re-vérifie avant d'intégrer.

### R-27 — COLLISION : `LivePrompts.kt` réécrit différemment deux fois

- `prompts-listes` (`3ce22146`) : dehors 16 v4 exacts (`fence`, `barrier`,
  `sign`, `street lamp`, `animal`, `trash can`, tête du feu / foule /
  boutique sorties), dedans 10 v4, `outdoor` replié sur `default`.
- `morts-divers` (WIP, `LivePrompts.kt:43-48`) : dehors 16 **divergents**
  (`pedestrian signal head`, `beanie`, `socks`, `stroller`, `child`, `dog`
  **gardés** ; `fence`, `barrier`, `sign`, `street lamp`, `animal`,
  `trash can` **absents**), dedans 9 historiques (pas les 10 v4), preset
  `outdoor` **supprimé** (pas replié).
- Même base (`6700e26d`), même fichier, contenus incompatibles : le merge
  textuel passera peut-être, le produit sera faux dans un cas sur deux.
  **Décision** : la version `prompts-listes` est la source (seule conforme
  à la spec) ; `morts-divers` abandonne son `LivePrompts.kt`.

### R-28 — Tests-contrats : tautologiques, et « Wait. » n'existe nulle part

- `tests-contrats` (WIP, `core/.../spec/`, 4 fichiers) : tests
  **auto-porteurs** — helpers locaux (`SpecAntiRepeat()`,
  `IseeSpecV4LevelsTest.kt:19-36` : `levelCarMoving = 0` en dur puis
  `assertEquals(0, levelCarMoving)`), aucune référence au prod
  (`VoiceBus`/`AntiRepeat` des branches). Ils passent toujours, même si
  le prod dit autre chose : ils verrouillent la spec sur le papier,
  pas le code.
- Pire : `verdict()` (`IseeSpecV4ArbitrationTest.kt:27-28`) fige
  « **Wait.** » — troisième phrase, ni « Don't cross. » (verdict,
  spec) ni « Cross now. » (clips-en). Si le parent aligne le prod sur
  ces tests, il introduit une phrase hors spec.
- **Décision** : réécrire ces tests contre le prod intégré (D-12)
  avec « Don't cross. » ; en l'état, ils ne prouvent rien.

### R-29 — Collisions de merge prévisibles (même fichier, deux branches)

- `ProximityVoice.kt` : réécriture totale echelle (`850f008d`, 498 l.)
  × suppression du repli hors-ligne morts-voix (WIP, −36 l. sur la
  version `6700e26d`) — le diff morts-voix ne s'applique plus.
- `HazardConcepts.kt` : seuils (`eb1703b2`, foule + niveaux) ×
  morts-divers (WIP, purge + foule) — contenus à fusionner à la main.
- `CurbWatcher.kt` : seuils (niveaux) × morts-divers (WIP) — idem.
- `docs/URGENCE-EXTERNE.md` : supprimé par morts-voix (WIP) — hors
  périmètre docs-revue (non relu ici), coordination à vérifier.
- Règle : merger echelle/seuils/prompts d'abord (versions sources),
  rejouer les suppressions morts-* dessus, jamais l'inverse.

## Vigilance (non bloquant, à trancher)

### R-17 — `VoiceBus.activeLevel` jamais remis à IDLE en fin de parole

- `VoiceBus.kt:44,63-66,101-107` : `activeLevel` ne retombe qu'au
  `reset()`/`userSpeaking`/`chatSpeaking` — pas de callback de fin de
  lecture. Cas limite : niveau 5 terminé sans reset + lecteur occupé
  hors-bus → un niveau 8 posté est jeté à tort (`8 > 5`, `!idle()`).
  Prévoir un callback de fin ou IDLE sur `onSpeaking("")`.

### R-25 — Détail : « Stairs up at 4 meters. » vs « Stairs up, 4 meters. »

- `StairWatcher.kt:43` vs catalogue clips-en. Conséquence de R-24 (les
  phrases seuils sont réécrites au mapping clips de toute façon).

## Cohérences vérifiées (bonnes nouvelles)

- Seuils (`seuils`, `eb1703b2`) : personne 10 m (`HazardWatcher.kt:398`),
  mobilier 6 m (:395), imminent 2 m (:401), jalons 4/2/1
  (`StairWatcher.kt:451`), niveaux 2/4/5/6/7/8/10 + clés littérales
  conformes au contrat sur tous les cues.
- Verdict (`7c09364a`) : règle rouge/mouvement exacte
  (`CrossingVerdict.kt:70-74`), voiture uniquement (:64-68), 100/101/102
  (:11-20), 3 s + immédiat (:94-142), dictée active (:192).
- GPS (`45f64960`) : rappel 15 s (`GPS_REMINDER_MS`), phrase consigne +
  distance restante EN (`VoiceNavGpsVoice.kt:23-27`), voix réseau.
- Clips (`accecb49`) : catalogue `#clips` + garde + `IseeClipPlayer`
  (silence si inconnu, `speakSequence` pour les composées) ; 10 objets
  indoor **dans l'ordre de la spec** (`IseeClipsEn.kt:294-297`).
- Prompts (`prompts`, WIP) : 16 dehors / 10 dedans exacts, suppressions
  faites, « Find my X ».
- Routeur (`routeur`, WIP) : 10 ordres stricts, « mode » préfixe, reste =
  LLM (`DictationOrder.kt:63-88`), actif partout traversée comprise.
- Chat (`25e3922d`) : `ChatWaitResume` rang 9, `yieldsTo = level < 9`,
  reprise depuis la coupure, overwrite.
- Gyro (`350f02a7`) : `EgoMotion` fond-prioritaire, 20 m dehors
  (`OUTDOOR_MOVING_METERS`), `announceStopped = false` dehors.
- Échelle (`850f008d`) : `VoiceBus.post(level, text, clipId?)`
  (`VoiceBus.kt:59`) — signature exacte du contrat ; `OutdoorLevel` 0-12
  exacts ; `userHoldsMic` seul-0-passe (:62).

## Décisions proposées au parent (ordre d'application)

1. **D-01** : `AntiRepeat` unique = `core.live` (`antirepetition`) + clés
   dedans (`find-found` 2 s, `indoor-doorwall` 4 s) ; supprimer
   `app.isee.AntiRepeat` ; reporter les usages echelle.
2. **D-02** : niveau 0 = « Car. » / « Cars. » (clips `car`/`cars`), type
   voiture seul (`isCarLabel`) ; supprimer les 5 autres noms parlés.
3. **D-03** : verdict = « Cross. » / « Don't cross. », clips
   `verdict-cross` / `verdict-dont-cross` ; aligner le catalogue clips-en
   (déjà tranché en `TRAVERSEE.md` § 6).
4. **D-04** : repli = **silence si clip manquant** hors chat/GPS
   (supprimer la synthèse de `ProximityVoice.speak`) ; chat et GPS seuls
   en synthèse réseau.
5. **D-05** : le câblage lit `cue.level` / `cue.antiRepeatKey` (seuils) ;
   supprimer le mapping kind→niveau d'echelle (`hazardWish`…).
6. **D-06** : niveaux traversée uniques (`CrossingLevel`), verdict sur clé
   `verdict:état` (supprimer `CrossingVerdictAnnouncer` ou le replier).
7. **D-07** : mapping phrase→clips EN par niveau (table au parent,
   ex. niveau 2 = `human_person` + `clock_*` + `meters_*`, sans
   « Attention… » ni « passez à… ») ; réécrire `HazardText`, `StairText`,
   `CurbText`, `SidewalkText`, `CrossingText` en assembleurs de clips.
8. **D-08** : find = parseur routeur seul (« mode find my X ») ;
   `FindIndoorOrder` interne après routage ; `isUnknownFind` → LLM ;
   clips find = morceaux clips-en via `speakSequence` (supprimer les
   pré-composés).
9. **D-09** : confirmations d'ordres en clips (`IseeClipPlayer`), jamais
   `speakNavigation` (réservé chat/GPS) ; direct = routeur exclusif
   (supprimer les replis `acceptNavOrder`) ; `glasses on/off` explicites.
10. **D-10** : câblage parent : mode global (outdoor/indoor/crossing),
    producteurs filtrés par mode, `chatMuted`/`gpsMuted` en traversée,
    `userSpeaking`/`chatSpeaking` branchés, repli 12 implémenté (300 ms).
11. **D-11** : suppressions (WIP morts-* à finir, R-29) : finir Maps
    (`NavigationLauncher`, `MapsUri`, usages, `nav_*`,
    `NAVIGATION-VOCALE.md`, `NAVIGATION.md`), cycles
    (`CycleWatcher`/`CycleConcepts` + câblages), `TraverseVeto` /
    `TraversePipeline` + voix FR, foule/benne/bonnet/socks dehors de
    `HazardConcepts`, 5 noms véhicules ; garder la purge `KnownSizes`
    (morts-divers) ; abandonner le `LivePrompts.kt` morts-divers (R-27).
12. **D-12** : `tests-contrats` à réécrire contre le prod intégré (R-28) :
    un test commun qui verrouille `VoiceBus.post`, `AntiRepeat.allow` +
    table, niveaux, clips (tout id posté existe au catalogue), avec
    « Don't cross. » (pas « Wait. »).
