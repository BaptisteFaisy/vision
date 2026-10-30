# P6 (backlog) — Obstacles via les lunettes Meta : note de cadrage

> Statut : **backlog — ne pas implémenter**. Cette note est le seul livrable.
> Rédigée le 2026-10-29 depuis `origin/master` (`f1f550f`), par lecture du code
> cité. Aucun matériel (ni lunettes, ni téléphone) n'était disponible : tout ce
> qui exige du terrain est marqué **à mesurer**.
> Mise à jour le 2026-10-30 (branche `p6-prepa-autonomie`, `origin/master`
> `892b9fb`) : P1–P5 intégrés sur master (commit `4023874`, voir §3.1),
> protocole P6-0 versé (`docs/P6-0-AUTONOMIE.md`), points ouverts n° 1–2
> instruits (`docs/P6-5-REPLI-POCHE.md`,
> `docs/P6-3-SERVICE-PREMIER-PLAN.md`). Campagne de mesure et décisions
> produit : toujours en attente.

Objectif rappelé : en poche / écran éteint, retrouver le guidage obstacles
depuis la caméra des lunettes — brancher `GlassesVideoLink` (frames DAT) vers
`LiveProbe` en mode lunettes (pompe, régulation, lifecycle sans CameraX,
arbitrage micro partagé via `MicBus`), avec repli téléphone quand les lunettes
décrochent. Démarrage interdit avant P1–P5 verts + mesure d'autonomie.

## 1. Existant vérifié (ce qui tourne déjà)

### 1.1. `GlassesVideoLink` — la passerelle frames DAT → accueil

Fichier : `app/src/main/java/dev/sam3vision/app/glasses/GlassesVideoLink.kt`.

- `GlassesCamera.pumpFrames` (`GlassesCamera.kt:145-169`) collecte `stream.videoStream`
  (15 i/s, `MEDIUM`, `compressVideo = true`, `GlassesCamera.kt:91-96`) et pousse
  chaque frame **en copie défensive** (`GlassesFrame` : octets, dimensions,
  `presentationTimeUs`, `compressed`, `codecConfig`) vers `GlassesVideoLink.push`.
- `GlassesVideoLink` expose : `streaming` (flux ouvert), `live` (vrai seulement à
  la première frame **rendue**), `frames` (dernière poussée conflated,
  `GlassesFrameBox` numérotée), `videoSize`, `takeSnapshot()` (photo de la rue
  affichée, fournie par l'UI via `attachSnapshot`).
- Référence complète : `docs/META-GLASSES.md` (§2–5, §13 pour les constats paire
  réelle du 27/10).

### 1.2. `LiveProbe` — la sonde SAM côté téléphone

Fichiers : `app/src/main/java/dev/sam3vision/app/isee/LiveProbe.kt`,
`LiveProbeStop.kt`, `ProbeWiring.kt`, `IseeCameraPane.kt`, `IseeVisionCard.kt`.

- Contrat d'entrée : `wantsFrame()` (faut-il convertir ?) puis
  `onFrame(jpegB64, captureAtNs)` (JPEG base64 320 px). Régulation interne :
  cadence adaptative (`LiveFrameCadence`), **une seule** frame en réserve
  (`LatestFrameSlot`, jamais de file), sessions normale + froide, masques →
  distances → guidage → voix (`ProbeVoice`, clips < 100 ms).
- Alimentation téléphone : `IseeCameraPane` (CameraX `ImageAnalysis`,
  `STRATEGY_KEEP_ONLY_LATEST`) appelle `wantsFrame`/`onFrame` via
  `IseeVisionCard.kt:330-339`. **Arbitrage actuel** : si
  `GlassesVideoLink.live` est vrai, la caméra du téléphone n'envoie plus rien
  (`wantsFrame = false`, `IseeVisionCard.kt:331`) — tout-ou-rien sur `live`.
- Arrêt : `LiveProbe.stop()` ferme les deux sessions et éteint le conteneur GPU
  (`POST /release`, coût 0 — sans lui, 60 s facturées).

### 1.3. Le pont actuel lunettes → sonde (écran allumé uniquement)

Fichier : `app/src/main/java/dev/sam3vision/app/isee/GlassesProbeWiring.kt`.

- `@Composable` + `LaunchedEffect(probe, glassesLive)` : sondage toutes les
  500 ms (`BRIDGE_POLL_MS`), `probe.wantsFrame()`, puis `snapshotJpeg()` :
  `GlassesVideoLink.takeSnapshot()` → `uprightScaled` → JPEG 320 q55 →
  `probe.onFrame`. Guidage, distances et voix suivent le même chemin que le
  téléphone (**parité par construction**).
- **Limite structurante** : `takeSnapshot()` photographie la rue **affichée**
  (`GlassesLiveDecoder` rend sur une surface Compose, `live` ne passe à vrai
  que sur du décodable rendu). En poche / écran éteint, il n'y a ni surface ni
  snapshot : le pont actuel ne peut pas servir au P6 tel quel.

### 1.4. `MicBus` — l'arbitre micro (téléphone uniquement)

Fichier : `app/src/main/java/dev/sam3vision/app/MicBus.kt`. Objet purement JVM.

- Propriétaires : `NONE`, `DICTATION` (appui long, PCM 16 kHz), `TAP_VIDEO`.
  Le second demandeur est **refusé**, jamais muet (l'appelant l'annonce).
- Côté lunettes, hors `MicBus` aujourd'hui : `GlassesAudioTap` (collecte unique
  du micro DAT pour le MP4 **et** la dictée — deux abonnés se voleraient des
  trames), `GlassesMicLink` (passerelle : `DictationFeature` prend la source
  lunettes si elle existe, sinon le téléphone), `GlassesAudioPlan` (palier
  16 kHz → 48 kHz → sans micro, `GlassesCamera.kt:68-87`).
- **Trou** : `MicBus` ne connaît aucun propriétaire lunettes ; l'arbitrage
  micro partagé (lunettes vs téléphone) n'existe pas.

### 1.5. Lifecycle — tout s'arrête à `onPause` aujourd'hui

- `MainActivity.onPause` (`MainActivity.kt:187-195`) → `isee.onBackground()` →
  `probe.onBackground()` + `probe.stop()` (`IseeViewModel.kt:483-488`) : la
  sonde est **arrêtée net**, sessions fermées, GPU éteint. De plus
  `ForegroundTracker` (`app/ForegroundTracker.kt`) fait jeter les frames reçues
  en arrière-plan (`wantsFrame` répond non).
- `GlassesController` est créé dans `MainActivity.onCreate` et fermé (`close()`)
  si l'activité se termine (`MainActivity.kt:228`) : la session DAT est
  aujourd'hui **liée à la vie de l'activité**.
- Le seul précédent de survie à l'écran verrouillé est `LockedMicService`
  (`docs/MICRO-VERROUILLE.md`) : service de premier plan type `microphone`,
  **armé depuis l'app visible** (Android 12+ refuse le démarrage depuis
  l'arrière-plan), réveil partiel pendant la capture seulement. Il ne fait
  **pas** de caméra : « le direct et la sonde s'arrêtent à `onPause` » y est
  écrit comme un fait.

## 2. Périmètre du chantier P6

### Dans le périmètre

1. **Pompe headless DAT → JPEG** : décoder les `GlassesFrame` H.265 **sans
   surface d'affichage** (MediaCodec sans `Surface`, ou reprise de
   `GlassesVideoDecoder`, aujourd'hui **jamais essayé** — `docs/META-GLASSES.md`
   §5) vers le format d'entrée de la sonde (JPEG 320 q55 redressé, mêmes helpers
   `uprightScaled`/`bitmapToJpegBase64` pour garder la parité). C'est le
   remplacement du snapshot Compose, inutilisable écran éteint.
2. **LiveProbe en mode lunettes** : source pilotée par la session DAT au lieu de
   CameraX — démarrage/arrêt sur `STARTED`/`STOPPED` (+ `PAUSED` : paire reposée),
   régulation adaptée au lien Bluetooth Classic (15 i/s en entrée, cadence utile
   bien moindre, cf. §5), sortie du périmètre `ForegroundTracker` actuel pour
   cette source **sans** casser la règle « la carte ne travaille que quand
   c'est voulu » (le garde-fou anti-facturation `stop()` + `/release` doit
   suivre le nouveau lifecycle).
3. **Lifecycle sans CameraX, poche/écran éteint** : le guidage doit survivre à
   `onPause`. Implique très probablement un service de premier plan (modèle
   `LockedMicService`, armé depuis l'app visible), dont le **type exact reste à
   trancher** (le flux DAT n'est pas la caméra Android : `camera` ? `microphone`
   ? `connectedDevice` ? `dataSync` ? — voir §5, point ouvert n° 2). Le
   `GlassesController` lié à l'activité devra être découplé ou relayé.
4. **Arbitrage micro partagé via `MicBus`** : ajouter le(s) propriétaire(s)
   lunettes, brancher `GlassesMicLink`/`GlassesAudioTap` sur l'arbitre, définir
   la règle de refus et son annonce vocale (jamais de micro muet, règle de la
   maison — `docs/META-GLASSES.md` §7). Attention : le micro DAT est **porté par
   le flux caméra** (`StreamConfiguration.audioCodec`, `GlassesCamera.kt:91-96`)
   — couper la caméra peut couper le micro, et inversement.
5. **Repli téléphone quand les lunettes décrochent** : écouter la session DAT
   (`STOPPED`, erreurs de flux/session, perte de lien — `GlassesPolicy.kt` a
   déjà les phrases) et basculer : re-armer la caméra du téléphone **si elle
   peut voir** (écran rallumé / app visible), sinon mettre le guidage en pause
   annoncée (« lunettes déconnectées, guidage en pause ») plutôt que d'envoyer
   des frames noires de poche. Hystérésis anti-ping-pong obligatoire.
6. **Mesure d'autonomie** (prérequis, voir §3) puis budget : seuils, dégradation
   (baisse de cadence), arrêt auto.

### Hors périmètre (rappel)

- Le mode Direct sur lunettes (aujourd'hui : « pas encore porté », réponse en
  clair au double appui — `docs/META-GLASSES.md` §7).
- La fusion des deux décodeurs (`GlassesVideoDecoder` / `GlassesLiveDecoder`) —
  chantier à part, quand le premier aura vu du matériel.
- L'export 16:9 et la limite de taille/durée du MP4 (`docs/META-GLASSES.md` §3).
- Les messages d'échec hors Réglages (point ouvert, §12 de la même page).

## 3. Prérequis

1. **P1–P5 verts.** Contenus intégrés sur `origin/master` par le commit
   `4023874` (« iSee dehors : intégration P1–P6 », repris de la branche
   `integration-isee-outdoor-p1p6`, CHANGELOG du 2026-10-29) : arbitre vocal
   (P1), `outdoorMode` persisté (P2), filtre sign (P3), wording virage (P4),
   `docs/RECETTE-RUE.md` + `scripts/verify-street.sh` (P5) — tous vérifiés
   présents sur master le 30/10. Ce ne sont pas des fusions git (aucun commit
   de merge ; les refs `p1`…`p5` restent ouvertes), mais le contenu est là.
   Reste à confirmer avant démarrage : CI verte sur master et recette paire
   réelle OK. Cette note ne décrit pas P1–P5 : elle ne couvre que le cadrage P6.


   Au minimum, le P6 suppose acquise et stable sur paire réelle toute la chaîne
   décrite en §1 (session, flux 15 i/s, pont écran allumé, arbitrage micro
   téléphone).
2. **Mesure d'autonomie.** Avant d'écrire la première ligne : protocole + chiffres
   sur paire réelle — lunettes seules (streaming 15 i/s continu jusqu'à quel
   seuil ?), téléphone seul (décodage H.265 + `/live` + voix, écran éteint),
   avec le même outillage que `docs/MICRO-VERROUILLE.md` §Preuves
   (`adb`, `MQSserver`, `dumpsys batterystats`). Sans ces chiffres, aucun budget
   (cadence, durée max, seuils) ne peut être fixé autrement qu'au hasard.
   Protocole versé : `docs/P6-0-AUTONOMIE.md` (prêt à exécuter, campagne
   non exécutée — en attente du téléphone et de la paire).
3. **Matériel et accès** : paire réelle + téléphone de recette + Developer Mode
   (rappel : pas de canal public, canal « Beta » sur invitation — §13 de
   `docs/META-GLASSES.md`), et une décision produit sur le §5, point n° 1.

## 4. Découpage proposé (étapes)

| Étape | Contenu | Fini quand |
|---|---|---|
| P6-0 | Protocole et campagne de **mesure d'autonomie** (lunettes + téléphone, écran éteint) ; budget chiffré (cadence cible, durée max, seuils) | chiffres versés en doc, budget validé |
| P6-1 | **Pompe headless** : `GlassesFrame` → JPEG 320 q55 sans surface, en JVM-testable autant que possible (décision pure isolée, motif `GlassesPolicy`) ; recette mock (`GlassesMock`, `mock_feed.mp4`) | guidage identique écran allumé avec et sans affichage (parité mesurée, pas affirmée) |
| P6-2 | **Mode lunettes de `LiveProbe`** : pilotage par la session DAT (pas de CameraX), régulation BT, `stop()`/`/release` re-câblés sur le nouveau lifecycle | aller-retour session ouverte/fermée sans fuite (ni WebSocket, ni facturation) |
| P6-3 | **Service de premier plan** : survie à l'écran éteint, armement depuis l'app visible (modèle `LockedMicService`), type FGS tranché et documenté | guidage vocal continu, téléphone en poche, sur la durée du budget P6-0 |
| P6-4 | **`MicBus` partagé** : propriétaires lunettes, règle de refus + annonce, tests JVM (motif `MicBusTest`) | dictée et flux DAT ne se volent plus le micro, dans les deux sens |
| P6-5 | **Repli téléphone** : détection de décrochage, bascule ou pause annoncée, hystérésis, tests JVM des règles + recette sur coupure réelle (paire éteinte / hors portée) | coupure réelle → comportement annoncé en < N s (N fixé au P6-0), reprise sans redémarrage de l'app |
| P6-6 | **Durcissement** : Doze/optimisation batterie, thermique, `PAUSED` (paire reposée), garde-fou `STARTING` infini (point ouvert §12 de `docs/META-GLASSES.md`), journal de bord logcat | recette longue (trajet réel) sans intervention |

Ordre contraignant : P6-0 avant tout ; P6-1 avant P6-2 ; P6-5 testé sur coupure
réelle, jamais seulement sur mock.

Notes d'instruction (branche `p6-prepa-autonomie`, backlog inchangé) :
P6-0 → `docs/P6-0-AUTONOMIE.md` ; P6-3 (type FGS) →
`docs/P6-3-SERVICE-PREMIER-PLAN.md` ; P6-5 (repli poche) →
`docs/P6-5-REPLI-POCHE.md`.

## 5. Risques et points ouverts

1. **Le repli téléphone en poche ne peut pas « voir ».** Une caméra de téléphone
   en poche filme du noir : basculer dessus enverrait des frames noires à SAM
   (facturées, trompeuses). Le repli réaliste en poche est donc une **pause
   annoncée**, pas une bascule caméra. À trancher avec le produit avant P6-5.
   Instruit : `docs/P6-5-REPLI-POCHE.md` (recommandation : pause annoncée
   en poche, bascule téléphone si visible, jamais de caméra en poche ;
   produit tranche phrases, hystérésis, palier D, `PAUSED`).
2. **Type de service de premier plan.** Le flux DAT n'est ni `CameraService` ni
   `AudioRecord` : le type FGS qui couvre légalement « streaming BT + réseau +
   voix » écran éteint n'est pas établi. À trancher par la doc Android + un
   essai sur appareil (refus système = échec net, comme pour `microphone` avant
   `LockedMicService`).
   Instruit : `docs/P6-3-SERVICE-PREMIER-PLAN.md` (recommandation :
   `connectedDevice` seul, `specialUse` en repli ; preuve sur le Redmi
   exigée avant P6-3).
3. **Porte `ForegroundTracker` / coût GPU.** Lever la porte « premier plan »
   pour la source lunettes sans garde-fou, c'est risquer un conteneur Modal
   allumé dans une poche jusqu'à la batterie vide. Le lifecycle P6-2 doit garder
   un équivalent strict (arrêt net + `/release` sur chaque sortie : session
   perdue, budget épuisé, arrêt manuel).
4. **Double décodage.** Aujourd'hui le H.265 est décodé une fois pour l'affichage
   (`GlassesLiveDecoder`). En mode headless avec écran rallumé (utilisateur qui
   regarde la rue **et** est guidé), affichage + sonde décoderaient deux fois :
   coût CPU/batterie à mesurer au P6-0, mutualisation éventuelle à prévoir.
5. **`PAUSED` et micro-coupures.** Paire reposée → `PAUSED` affiché « en cours »
   (`docs/META-GLASSES.md` §3 ter). En guidage poche, chaque `PAUSED`/`STARTING`
   sans fin est un trou de guidage muet : politique d'annonce et de reprise à
   définir (P6-2/P6-5).
6. **SDK instable, Developer Mode.** Toolkit en developer preview, pin 1.0.0
   imposé par la paire, pas de canal public (`docs/META-GLASSES.md` §6, §13).
   Toute la chaîne P6 repose dessus : prévoir une non-régression à chaque mise
   à jour Meta AI / firmware.
7. **Confidentialité.** Streaming continu depuis des lunettes + enregistrement
   MP4 existant : le mode poche doit dire ce qui est enregistré, et le rester
   (pas de collecte silencieuse — le collecteur `GlassesFrameCollector` est
   éteint par défaut pour de bonnes raisons).

## 6. Critères de démarrage (tous exigés)

- [x] P1–P5 : contenus intégrés sur master (`4023874`, vérifié le 30/10).
- [ ] P1–P5 : CI verte sur master + recette paire réelle OK (à confirmer).
- [ ] P6-0 : protocole versé (`docs/P6-0-AUTONOMIE.md`) ; campagne NON
      exécutée (chiffres + budget validé : en attente du téléphone).

- [ ] Point ouvert n° 1 instruit (`docs/P6-5-REPLI-POCHE.md`) : produit
      tranche (phrases, hystérésis, palier D, `PAUSED`).
- [ ] Point ouvert n° 2 instruit (`docs/P6-3-SERVICE-PREMIER-PLAN.md`) :
      type `connectedDevice` à démontrer sur le Redmi.

- [ ] Paire réelle + téléphone de recette disponibles pour la durée du chantier
      (les étapes P6-3/P6-5 ne sont pas valables sur mock seul).
- [ ] Aucun code P6 écrit avant : docs seules (cette note + `P6-0-AUTONOMIE`,
      `P6-3-SERVICE-PREMIER-PLAN`, `P6-5-REPLI-POCHE`).

## 7. Sources lues pour cette note

- `app/.../glasses/GlassesVideoLink.kt`, `GlassesCamera.kt`,
  `GlassesController.kt` (partiel), `GlassesMicLink.kt`
- `app/.../isee/LiveProbe.kt`, `LiveProbeStop.kt`, `ProbeWiring.kt`,
  `GlassesProbeWiring.kt`, `IseeViewModel.kt` (`onBackground`/`onForeground`),
  `IseeVisionCard.kt:330-339`, `IseeCameraPane.kt`, `IseeSession.kt`
- `app/ForegroundTracker.kt`, `app/MicBus.kt`, `app/MainActivity.kt`
  (`onCreate`/`onPause`/`onResume`/`onDestroy`)
- `docs/META-GLASSES.md`, `docs/GLASSES-ETAT.md`, `docs/MICRO-VERROUILLE.md`
