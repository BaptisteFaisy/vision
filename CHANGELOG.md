# CHANGELOG — vision (iSee)

Le journal part du 2026-10-26 après 19 h : le commit de départ, puis une entrée par fait
livré et ses preuves (tâche, fichiers, chiffres) dans l'arbre.

## 2026-10-29 — suivi caregiver : le relais par défaut est le Worker, plus l'hôte tombé

- **Panne constatée** : `DEFAULT_PARENT_SITE_URL` pointait sur
  `site-1607fe95…freebuff.page`, qui rend **502 « Site unavailable »** sur `/`,
  `/health` et `/api/state`, alors que le Worker `isee-suivi.duello.workers.dev`
  répond (`/health` `{"ok":true}`, `/api/state` 200, page et `live.mjs` servis).
  Conséquence : la page caregiver restait en NO STREAM et **la caméra du proche ne
  s'affichait jamais** — le téléphone publiait dans le vide.
- `DEFAULT_PARENT_SITE_URL` → `https://isee-suivi.duello.workers.dev/` : l'adresse du
  Worker déployé, pas un domaine intermédiaire qui peut tomber sans que le relais
  bouge d'un octet.
- `isLegacyRelayUrl` reconnaît désormais un **ensemble** d'hôtes
  (`suivi-parent.vercel.app`) plus les **suffixes** d'hébergement gratuits
  (`.freebuff.page`, dont l'identifiant de site varie) : un lien ou un réglage d'avant
  repointera sur le relais sans être ressaisi.
- Preuves : `:core` 1 854 tests, 0 échec (`CaregiverPageTest` +2 : hôte hérité repris,
  défaut = Worker) ; `:app` sous-ensemble partage/caregiver 31/31
  (`CaregiverShareLinkTest` 6, `ParentShareTest` 5, `SettingsSeedTest` 20) ;
  `check-kotlin-limits` OK.
- Fichiers : `Settings.kt`, `CaregiverPage.kt`, `CaregiverPageTest.kt`,
  `suivi-relais/README.md`.

## 2026-10-29 — iSee : arbitrage voix « obstacle → TwinLite, sinon SAM » (ordre user)

- **Demande** : si obstacle → voix TwinLite (« obstacle, stop » + côté de
  sortie), sinon → mots SAM (cap/trottoir/décalage) — un seul parle à la
  fois, jamais de doublon, doctrine <100ms/clips gardée, guidage doux
  (seul le stop coupe).
- `TwinLiteWord` (core pur, restauré minimal du chemin voix-obstacle viré
  par `d13ac26a`/PR #306 : textes `TwinLiteText`, clips `tl_*` de
  `TwinLiteVoice.idFor`, lus dans `7d133f3f`) — sans segmentation
  (ni LiteRT, ni masque : bouché/libre reste 100 % SAM). `GuidanceAnnouncer`
  arbitre (une seule porte, clés `tl|*` ≠ `w|*`) ; `ProbeWiring` route les
  phrases TwinLite vers `speakWord` (clips `vp_tl_*` restaurés, catalogue +
  garde OK) ; `isStopPhrase` couvre les deux stops (TwinLite interne +
  « Stop. » SAM externe), `LiveProbe` intact (499 lignes).
- Preuves : `:core` 1 825 + `:app` 1 057 tests, 0 échec, sur r630
  (`TwinLiteWordTest` 9/9, `GuidanceAnnouncerTest` 18/18 dont l'arbitrage),
  `check-kotlin-limits` + `check-voicepack` OK. Docs `GUIDAGE-TOUT-DROIT.md`,
  `VOIX-DANGER.md` § 12, `URGENCE-EXTERNE.md`.
- Fichiers : `TwinLiteWord.kt`, `GuidanceAnnouncer.kt`, `GuidanceWord.kt`
  (`isStopPhrase`), `ProbeWiring.kt`, `IseeViewModel.kt` (commentaire),
  8 tests, 3 docs (+8 MP3, +12 tests).
## 2026-10-29 — ménage (2) : l'app Expo sort de l'arbre, la table de permissions morte aussi

- **Demande** : « vire les 2 » — l'app Expo archivée et la table de décision des permissions
  qui ne vivait plus que par ses tests.
- `server/legacy-expo-app/` (55 fichiers, ~0,8 Mo) **retirée** : remplacer l'app Kotlin depuis
  le 29/10, elle n'était plus lue par la CI, ni par un script, ni par l'APK. Elle reste dans
  l'historique git (`git log -- server/legacy-expo-app`).
- Serviteurs de cette app retirés avec elle, faute d'appelant : `server/scripts/build-apk.sh`,
  `dev_qr.sh` (tunnel Expo Go), `install-android-sdk.sh` (seul appelant : `build-apk.sh`), les
  helpers `find_jdk` / `sdk_env` de `lib-env.sh`, la ligne d'aide de `deploy_backend.sh`, et
  les entrées `legacy-expo-app/*` de `server/.gitignore`.
- `server/README.md` **réécrit** : il décrivait l'app Expo (son en-tête l'admettait à moitié).
  Il dit maintenant ce qui est déployé — 4 apps Modal (`sam3-eu-a100`, `valhalla-idf-cpu`,
  `sam3-vision`, tracker) plus le portage Baseten, routes vérifiées ligne à ligne —, la règle
  « une seule GPU », les commandes de déploiement et de test, et les limites connues.
  `server/scripts/README.md` perd sa section « App Android / Expo » (l'APK se construit à la
  racine, `./gradlew :app:assembleDebug`). Les docs `server/docs/*` datées gardent leurs
  références historiques, encadrées par un bandeau « retirée de l'arbre le 29/10 ».
- `perms/PermDecision.kt` **100 → 24 lignes** : `PermKind`, `PermStep`, `nextStep`, `fgGranted`,
  `appSettingsUri`, `PermCopy`, `copyFor` et les six libellés inutilisés n'étaient lus que par
  `PermDecisionTest`. Restent `canAskBackground` (lu par `MainActivity`) et
  `PermTexts.MIC_DENIED` (lu par `DictationFeature` et `LiveVoice`). Le test suit : 10 → 2.
- Chiffres re-mesurés (`tools/count-tree.py`) : 913 `.kt`, 98 370 lignes, 54 684 lignes de
  prod, 2 884 `@Test` JVM (`:core` 1 835 + `:app` 1 049, 0 échec), 9 instrumentés, 47 docs.
  README, PERF, TESTING (§1 et §6 re-générés depuis les rapports JUnit), ARCHITECTURE, NOTES,
  AGENTS et DOC-ETAT alignés.
- Fichiers : `README.md`, `CHANGELOG.md`, `NOTES.md`, `AGENTS.md`, `docs/{ARCHITECTURE,DOC-ETAT,
  PERF,TESTING}.md`, `app/.../perms/PermDecision.kt` (+ son test), `server/README.md`,
  `server/.gitignore`, `server/scripts/{README,NOTES}.md`, `server/scripts/{deploy_backend.sh,
  lib-env.sh}`, `server/docs/{SPEC,ARCHITECTURE,AUDIT,KOTLIN-SPEC,MODAL-CLOSED}.md`,
  et la suppression de `server/legacy-expo-app/` + 3 scripts.

## 2026-10-29 — iSee : mur et concepts = obstacles bloquants (preuves JVM)

- **Demande** : le mur et la liste user (barrières, marches, escaliers,
  voitures, vélos, humains) doivent bloquer le couloir de marche ; vérifier
  de bout en bout, combler les vrais trous sans inventer de distances.
- `GuidanceObstaclesTest` (8/8, pur JVM) : chaîne `LiveProbe.publish`
  rejouée — sténopé, profondeur serveur (`with_depth`), `placedLabels`,
  `decide`, mot, phrase. Mur à ~2 m (boîte coupée) → prudence + côté ;
  mur mesuré ~3 m → décalage ; mur 0,5 m (profondeur) et petit objet
  0,5 m (sténopé) → stop ; liste user à 2 m → lus et bloquants ;
  escaliers/marches/trou sans distance → prudence, rien d'inventé.
- Résultat : aucun trou de blocage — le couloir bloque tout label, mesuré
  ou non. « Stop » à 0,5 m exige une mesure (profondeur, coupée par
  défaut) : au sténopé seul, mur/personne à 0,5 m remplit le cadre et le
  mot reste le côté. Suivis notés (`GUIDAGE-TOUT-DROIT.md` § 8) : Canny
  non branché, deux `StairConcepts` en désaccord sur « marche », lecture
  coupée-haut-seul écartée.
- Preuves : `:core:testDebugUnitTest` 1 812 tests, 0 échec, sur r630
  (`GuidanceObstaclesTest` 8/8). Docs `GUIDAGE-TOUT-DROIT.md` § 5 + § 8,
  `TESTING.md` (live 333).
- Fichiers : `+GuidanceObstaclesTest.kt` (213 lignes), `GUIDAGE-TOUT-DROIT.md`,
  `TESTING.md`, `CHANGELOG.md`. Périmètre voix tenu à l'écart
  (`GuidanceAnnouncer.kt`, TwinLite, `IseeViewModel` : lus, non modifiés).
## 2026-10-29 — GPS + obstacles : heures du cadran, une voix, recalcul temps réel (ordre user)

- **Demande** : les infos précisent les heures (cadran des aveugles) ; GPS
  lié aux obstacles, même voix ; meilleur chemin temps réel.
- Heures : `PositionText.withHour` (heure optionnelle, jamais devinée) ;
  « Attention à la barrière, à 2 heures. », « Attention à la marche, à
  midi. », « Escalier montant, à 11 heures. » (signaux porteurs du centre).
- Une voix : manœuvres GPS par Laure (`say`, même politique, urgence
  arbitrage — sortie de trace et recalcul coupent), cap route câblé à la
  sonde (émis dans le vide jusque-là) ; manœuvre perdue = ré-émission.
- Recalcul : détour publié (`LiveProbeState.detour`), `DetourReroute` (8 s
  subies → feu, 1/min max), `observeDetour` → pilote → Valhalla depuis le
  GPS temps réel (« Nouvel itinéraire »). Pas d'`avoid_locations`.
- Preuves : `:core` 1 836 + `:app` 1 075 tests, 0 échec, sur r630
  (`DetourRerouteTest` 5/5, `PositionTextTest` 14/14, `LiveProbeGuidanceTest`
  6/6), `check-kotlin-limits` OK. Docs `VOIX-DANGER.md` § 12,
  `GPS-FUSION.md` (nouveau). (`TESTING.md` : comptes des autres paquets
  déjà dérivés par d'autres agents — mes lignes seules remises à vrai.)
- Fichiers : `PositionText.kt`, `HazardWatcher.kt`, `CurbWatcher.kt`,
  `StairWatcher.kt`, `StairLabelSignal.kt`, `DetourReroute.kt` (nouveau),
  `VoiceNavEngineDriver.kt`, `IseeVoiceNav.kt`, `IseeViewModel.kt`,
  `LiveProbe.kt`, `LiveProbeState.kt`, `ProbeWiring.kt` (+11 tests).

## 2026-10-29 — iSee : guidage doux + « Attention à … », stop seul urgent (ordre user)

- **Demande** : iSee/SAM sert au non-urgent (décrire, guider en douceur) —
  un autre modèle (en cours) gérera l'urgence ; garder le Stop. en attendant.
- Mots doux : gauche/tout droit/droite dits aussitôt (clips < 100 ms) mais
  n'interrompent plus (`spokenUrgent` = `GuidanceWord.isStopPhrase`) ; seul
  « Stop. » coupe, chat compris. Stop interne (contact ≤ 0,6 m) gardé.
- « Attention à … » : trottoir (« Attention à la marche. »), escaliers
  (doux, texte inchangé), mobilier (`HazardConcepts`/`HazardWatcher` :
  barrière, poteau, borne, clôture, portail — FR/EN, 3 m, 2 frames, 10 s),
  3e guetteur `ProbeHazards`, synthèse réseau, jamais de coupure.
- Hook urgence externe : `ProximityVoice.reportEmergency` (clip stop,
  coupe tout, dédupliqué) + contrat `docs/URGENCE-EXTERNE.md` ; suppression
  du stop interne prévue au branchement du modèle externe.
- Preuves : `:core` 1 818 + `:app` 1 074 tests, 0 échec, sur r630
  (`GuidanceWordTest` 6/6, `HazardConceptsTest` 5/5, `HazardWatchTest` 8/8,
  `LiveProbeGuidanceTest` 5/5, `ProximityVoiceWordTest` 4/4),
  `check-kotlin-limits` + `check-voicepack` OK. Docs `VOIX-DANGER.md` § 11,
  `GUIDAGE-TOUT-DROIT.md` § 4, `URGENCE-EXTERNE.md` (nouveau).
- Fichiers : `GuidanceWord.kt`, `CurbWatcher.kt`, `hazard/` (2 nouveaux),
  `LiveProbe.kt`, `LiveProbeState.kt`, `ProbeWiring.kt`, `ProximityVoice.kt`,
  `IseeViewModel.kt` (+17 tests, +2 fichiers).

## 2026-10-29 — ménage : le code sans appelant part, le README se recale sur l'arbre

- **Demande** : analyser le dépôt, retirer le code qui ne sert plus, et que le README dise
  vrai partout.
- Code retiré, chaque fois vérifié par grep (zéro référence hors tests) :
  - `perms/PermGate.kt` **312 → 27 lignes** : les trois demandes de permission par écran
    (`MicPermRequest`, `LocationFgPermRequest`, `LocationBgPermRequest`) et tout le flux qui
    les portait n'avaient plus d'appelant depuis la demande groupée du premier lancement
    (`FirstRunPermissions.kt`). Reste `hasPerm`, lu par `isee/vm/IseeVoiceNav.kt`.
  - `cleanup/MediaStoreGallery.kt` (63 l.) et `CleanupConfig.addToGallery` : aucun appelant,
    donc rien n'écrivait dans `traced_captures`. La table, le store et la purge restent (testés).
  - `IseeOrb` (wrapper Compose sans appelant ; le peintre `rememberOrbPainter` reste),
    `NoOpSegmenter`, `crossingStateOf`, `failureText`, `jsonArrayOf`, `PNG_SIGNATURE_SIZE`.
- **Bug trouvé au passage** : `suivi-parent/live.mjs` importait `./maps.mjs`, supprimé le
  29/10 avec la carte. Un import mort fait échouer **tout** le graphe de modules : la page
  compagnon embarquée dans chaque APK ne se chargeait plus. `getElementById('recenter')`
  visait aussi un élément disparu. Les deux lignes partent ; `destPoint`, `objectsOf`,
  `iconFor` (exports de la carte) partent avec leurs assertions.
- README réécrit sur des chiffres **re-mesurés** (913 `.kt` et non 876, 54 784 lignes de prod
  et non 52 815, 2 875 `@Test` JVM + 9 instrumentés, 35 fichiers Python) : 12 concepts du
  direct par défaut et 16 au plus (et non 6), **LiteRT** et non « TensorFlow Lite », les
  écrans legacy réellement listés dans « Other screens » (Direct, Classic home, segment),
  traversée *proposée* et jamais activée, file de merge **Mergify** et non native, relais
  `suivi-relais/` (Worker + D1) et non le Vercel abandonné, `server/backend/baseten/`,
  `NOTICE` et les 46 docs cités, clé Maps pour la navigation (la carte compagnon n'existe plus).
- `tools/count-tree.py` : le relevé devient rejouable (`--json`) — les chiffres recopiés
  avaient dérivé trois fois en une journée. `docs/ARCHITECTURE.md` §1, `docs/PERF.md`
  §1.1-1.2, `docs/TESTING.md` §1 et §6 (régénéré depuis les rapports JUnit), `NOTES.md`,
  `AGENTS.md` et `docs/DOC-ETAT.md` portent la même mesure ; `docs/NETTOYAGE.md` dit que
  rien n'écrit plus dans `traced_captures`.
- Boule Portrait : empreinte re-gelée (`tools/orb-frozen-baseline.json`, « changement voulu »
  du garde) — seul un wrapper sans appelant est parti, **aucune ligne de rendu** ne bouge.
- Preuves : `:core` 1 804 + `:app` 1 071 tests JVM, 0 échec ; `:app:assembleDebug` OK (poste
  MS-7D18, JDK 21 → bytecode 17) ; `check-kotlin-limits` (1 013 fichiers, 0 dépassement),
  `check-isee-design`, `check-budget-derive`, `check-voice-nav-doc`, `check-apk-keys` OK ;
  `scripts/test_scripts.sh` 101/101 ; `node --test suivi-parent/test/*.test.mjs` 16/16 ;
  `suivi-relais` 10/10.
- Fichiers : `README.md`, `AGENTS.md`, `NOTES.md`, `CHANGELOG.md`, `docs/{ARCHITECTURE,DOC-ETAT,
  NETTOYAGE,PERF,TESTING}.md`, `tools/count-tree.py`, `tools/orb-frozen-baseline.json`,
  `app/.../{cleanup/CleanupConfig.kt,cleanup/MediaStoreGallery.kt,isee/CrossingWiring.kt,
  isee/IseePortraitOrb.kt,isee/ProbeFrame.kt,perms/PermGate.kt}`,
  `core/.../{mask/MaskCore.kt,net/Json.kt,segment/Segmenter.kt}`,
  `suivi-parent/{live.mjs,session.mjs,test/session.test.mjs}`.
## 2026-10-29 — voix : le guidage dit quatre mots en moins de 100 ms (ordre user)

- **Demande** : pas assez d'infos/seconde (5 minimum) — dire juste
  `left`/`straight`/`right`/`stop`, source SAM, mot parlé en <100ms.
- `GuidanceWord` (core pur) : voie libre ⇒ cap, bouché ⇒ côté/passage,
  contact mesuré ≤0,6m ⇒ stop, décalage/trottoir ⇒ côté ; FR/EN.
  Annonceur : mot nouveau dit aussitôt (ni délai ni stabilité), même mot
  muet. `LateralNudgeText`/`SidewalkText`/clés morts supprimés.
- Clips `guide_*` (8 MP3 voix Laure, catalogue + garde OK) joués depuis
  le disque (`VoicePack` + `playFile`, zéro réseau) ; synthèse + cache
  sinon (`ProximityVoice.speakWord`). Tous les mots urgents (coupent tout).
- Preuves : `:core` 1 800 + `:app` 1 071 tests, 0 échec, sur r630
  (`GuidanceWordTest` 5/5, `GuidanceAnnouncerTest` 15/15,
  `ProximityVoiceWordTest` 3/3), `check-kotlin-limits` +
  `check-voicepack` OK. Docs `GUIDAGE-TOUT-DROIT.md`, `VOIX-DANGER.md` § 10.
- Fichiers : `GuidanceWord.kt`, `Guidance.kt`, `GuidanceAnnouncer.kt`,
  `LateralNudge.kt`, `SidewalkKeep.kt`, `ProximityVoice.kt`,
  `ProbeWiring.kt`, `IseeViewModel.kt`, `LiveProbe.kt` (+8 MP3, +10 tests).

## 2026-10-29 — iSee : guidage SAM seul, TwinLiteNet viré (ordre user)

- **Demande** : plus de TwinLiteNet (sol) — SAM seul guide : conseil de
  décalage latéral calculé à chaque frame (« Décalez-vous à gauche. » si des
  personnes arrivent à droite) et dit par SAM, une info par frame reçue.
- `LateralNudge` (pur core, miroir `VehicleApproach` sans boîte/identité) :
  côté le plus proche qui se rapproche 2 frames de suite → phrase urgente,
  branché dans `GuidanceAnnouncer` entre bouché et trottoir (bouché > décalez
  > trottoir > libre). Flèche existante réutilisée (`passage` + `urgent`).
  Voix = Laure ElevenLabs comme le reste (pas de 2ᵉ moteur).
- TwinLite supprimé : paquets `core/segment` + `app/segment` + `app/litert`,
  `IseeTwinLite`, `TwinLite{Layer,Voice,Wiring}`, route `Segment` + bouton
  Réglages, 8 MP3 `vp_tl_*` + entrées catalogue, dép. `litert-edge`,
  `mutesTwinLite` + fenêtre 10 s (`ProbeHazards` = reset commun), helper
  `resizeArgbBilinearRect`. Perte du secours sol offline assumée.
- Non-fait volontaire : `Cadence.Fast.targetMs` reste à 0 — le passer à 250
  espaçait les **envois** (info toutes les 250 + latence au lieu de chaque
  frame) et cassait 4 tests sonde ; revert prouvé vert.
- Preuves : `:core` 1 780 + `:app` 1 042 tests, 0 échec, sur r630
  (`LateralNudgeTest` 12/12, `GuidanceAnnouncerTest` 15/15, `ProbeVoiceTest`
  9/9, `ProbeHazardsTest` 2/2, `ProbeWiringSpokenTest` 3/3),
  `assembleDebug` vert, `check-kotlin-limits` + `check-voicepack` OK.
- Fichiers : `LateralNudge.kt`, `GuidanceAnnouncer.kt`, `Steering.kt`,
  `ProbeVoice.kt`, `LiveProbe.kt`, `ProbeWiring.kt`, `IseeViewModel.kt`,
  `AppNav.kt`, `Settings*.kt`, `UiLogicTest.kt` (+35 fichiers supprimés).
- Recette téléphone (Direct, obstacle réel, logcat sans `twinlite`) après merge.
## 2026-10-29 — Mode navigation : la carte passe sous la caméra (ordre user)

- **Demande** : « la map navigation doit être sous la caméra et disparaître
  quand l'user parle ».
- Le mode carte n'empile plus d'écran : `NavigationMapLayer` est la **première
  couche de la scène iSee** (avant la rue, la boule et les textes), plein
  écran, fond OSM MapLibre + repli schéma (`drawNavigationMap`).
- **Effacement pendant la parole** : `navigationMapHidden(state)` =
  `holding || transcribing` → alpha 0 en 220 ms, retour ensuite ; iSee qui
  parle ne masque pas la carte. La MapView n'est pas détruite (pas de
  rechargement de tuiles à chaque tenue).
- `AppNav` : l'état `Map` ne pousse plus `Route.VoiceNav` ; entrer en mode
  carte depuis n'importe quelle page ramène à l'accueil iSee. `MapModeScreen`
  (écran plein écran avec bouton « Quitter ») supprimé : les sorties sont
  vocales, comme l'entrée.
- Preuves : `:core` 1 801 tests, `:app` 1 068, 0 échec (dont
  `NavigationMapLayerTest` 5 : effacement, cadre de repli),
  `check-kotlin-limits` et `check-voice-nav-doc.py` OK.
- Docs : `NAVIGATION-VOCALE.md` §1 bis et §6, `CARTE-OSM.md` §4 bis,
  `GPS_GUIDE_UTILISATEUR.md` §1 et §2.

## 2026-10-29 — Mode navigation : la carte devient une bande permanente en bas (ordre user)

- **Demande** : « non je veux que la map navigation soit en permanence affichée
  en bas » (remplace le fond plein écran sous la caméra).
- `NavigationMapBand` : bande collée en bas, 26 % de la hauteur bornée
  150–260 dp (`navigationMapBandHeight`, pure et testée), fond OSM MapLibre +
  repli schéma, position GPS 2 s, feux par maille de ~110 m.
- **En permanence** : plus d'effacement pendant la parole
  (`navigationMapHidden` supprimé) — la bande reste visible quand
  l'utilisateur dicte et quand iSee parle.
- `IseeScenePane` : la scène est composée dans un cadre réduit de la hauteur
  de la bande, donc sa géométrie (boule, gros texte) se recalcule au-dessus —
  le texte ne passe jamais sous la carte.
- Preuves : `:core` 1 804 tests, `:app` 1 071, 0 échec (dont
  `NavigationMapBandTest` 5 : hauteur sur trois écrans, cadre de repli),
  `check-kotlin-limits` et `check-voice-nav-doc.py` OK.
- Docs : `NAVIGATION-VOCALE.md` §1 bis et §6, `CARTE-OSM.md` §4 bis,
  `GPS_GUIDE_UTILISATEUR.md` §2.

## 2026-10-29 — Mode navigation : la carte s'ouvre à la voix (ordre user)

- **Demande** : « un mode navigation dans iSee, accessible en lui parlant, avec
  la carte et le GPS si besoin ».
- `:core` `MapModeCommand` (pur) : « navigation », « carte », « plan », « mode
  navigation », « ouvre la carte », « affiche le plan », « où suis-je » entrent ;
  une destination (« navigation vers X », « ouvre la carte de X ») et un ordre de
  sortie (« arrête la navigation », « quitte la carte ») n'entrent pas.
  `MapModeText` : phrases FR/EN d'entrée et de sortie.
- `VoiceNavUiState.Map` + `VoiceNavSession.enterMap()/leaveMap()` : ouverture
  seulement depuis le repos (jamais par-dessus un guidage), sortie muette côté
  session (l'appelant parle, jamais « Guidage arrêté ») ; une destination dictée
  depuis la carte ouvre la confirmation comme depuis l'accueil.
- `MapModeScreen` plein écran (position + cercle de précision, passages piétons
  du `/signals`, micro appui long, bouton « Quitter »), `MapPositionWatcher`
  (GPS + réseau, 2 s, `LocationManager`) et `mapBoundsAround` (cadre de 200 m).
- iSee : `IseeModeOrders` réunit maison, carte et pictos en un seul point
  d'essai (avant la navigation et le LLM) ; `IseeViewModel` repasse sous 500
  lignes (498).
- Preuves locales : `:core` 1 816 tests, `:app` 1 080, 0 échec (dont
  `MapModeCommandTest` 9, `MapModeOrderTest` 6, `VoiceNavSessionMapTest` 6,
  `MapModeScreenTest` 5, `RouteMapProjectionTest` 9), `check-kotlin-limits` OK.
- `MapModeScreen` plein écran : **fond OSM MapLibre** du guidage
  (`GuidanceMapView`, caméra rue sur la position — `docs/CARTE-OSM.md` §4 bis),
  repli schéma si les tuiles échouent, feux du `/signals`, micro appui long,
  bouton « Quitter » ; `MapPositionWatcher` (GPS + réseau, 2 s,
  `LocationManager`) et `mapBoundsAround` (schéma de repli, cadre de 200 m).
- iSee : `IseeModeOrders` réunit maison, carte et pictos en un seul point
  d'essai (avant la navigation et le LLM) ; `IseeViewModel` repasse sous 500
  lignes (498).
- Preuves locales (après rebasage sur le fond OSM de master) : `:core` 1 801
  tests, `:app` 1 068, 0 échec (dont `MapModeCommandTest` 9,
  `MapModeMapStateTest` 4, `MapModeOrderTest` 6, `VoiceNavSessionMapTest` 6,
  `MapModeScreenTest` 5, `RouteMapProjectionTest` 9), `check-kotlin-limits` et
  `check-voice-nav-doc.py` OK.
- Docs : `NAVIGATION-VOCALE.md` §1 bis, `GPS_GUIDE_UTILISATEUR.md` §1 et §2.
- Recette téléphone (carte + vrai GPS) après merge.

## 2026-10-29 — Démarrage : le favicon s'affiche sur fond blanc (ordre user)

- **Demande** : à l'ouverture, l'écran de démarrage (favicon centré) doit être sur
  fond blanc, pas noir.
- `window_background` passe de `#FF0B0D12` à `#FFFFFFFF` : c'est la couleur de
  fenêtre que le splash Android utilise derrière l'icône, avant la première frame
  Compose. L'accueil Portrait iSee étant blanc, l'ouverture ne change plus de
  couleur entre l'icône et l'accueil.
- `AppColors.background` n'est pas touché : les écrans Compose sombres (Chat,
  Direct) gardent leur fond.
- Preuves : `:app:processDebugResources --offline` BUILD SUCCESSFUL (ressources
  et manifeste compilés, 52 s). Rendu sur téléphone non vérifié ici : aucun APK
  de ce commit n'a été construit ni installé.
- Fichiers : `app/src/main/res/values/colors.xml`, `themes.xml`.
## 2026-10-29 — voix : le contournement chiffré remplace le « Stop. » (ordre user)

- **Demande** : devant un obstacle, même au contact, iSee dit où passer
  (« Va à gauche, 1 mètre. ») au lieu de répéter « Stop. » en boucle.
- `Guidance.phrase` : `Continue.` + contournement chiffré (`spokenShort` :
  mètre entier, centimètres sous 1 m, non chiffré sans mesure) ; `STOP_METERS`,
  `STOP_*_FRAC`, `GuidanceDecision.stop`, marqueur `|stop` et `LabelExtent`
  supprimés. Urgence gardée : `LiveProbeState.spokenUrgent` (publié avec la
  phrase) coupe la lecture en cours, chat compris ; clip `STOP_WORD`
  pré-synthétisé supprimé. Traversée « Stop, mur » inchangée.
- Preuves : `:core` 1 794 + `:app` 1 060 tests, 0 échec, sur r630
  (`GuidanceTest` 18/18, `GuidanceAnnouncerTest` 12/12,
  `LiveProbeGuidanceTest` 4/4 dont l'urgence, `ProbeWiringSpokenTest` 3/3),
  `check-kotlin-limits` OK. Docs `GUIDAGE-TOUT-DROIT.md`,
  `VOIX-DANGER.md` § 9. Recette téléphone (obstacle réel) après merge.
- Fichiers : `Guidance.kt`, `PositionText.kt`, `FrameRoster.kt`,
  `ProximityVoice.kt`, `ProbeWiring.kt`, `LiveProbe.kt`, `LiveProbeState.kt`,
  `Settings.kt` (+5 tests).
## 2026-10-29 — Trottoir annoncé à 3 m ou moins (ordre user)

- 1 m = une seconde pour réagir à pied : `CURB_METERS` 1 → 3 m. La bande
  basse suit (0,6 → 0,45) : à 3 m le bord tombe en bande moyenne selon le
  tangage du téléphone — sans ça la distance seule ne servait à rien.
- Inchange : plan sous les pieds ignoré, montée/descente par géométrie,
  labels de bord prioritaires, cooldown 10 s, phrase urgente.
- Preuves : `:core` `testDebugUnitTest` vert sur r630 (`CurbWatcherTest`
  18/18 dont 2 nouveaux : 2 m détecté, bord à 3 m en bande moyenne ;
  rouge-avant vérifié), `:core` + `:app` verts, `assembleDebug` vert,
  `check-kotlin-limits` OK. Doc `VOIX-DANGER.md` § 2 à jour.
- Fichiers : `CurbWatcher.kt` (+ test), `VOIX-DANGER.md`.

## 2026-10-29 — Page du proche : la caméra ne s'affiche que si elle tourne (ordre user)

- **Demande** : l'image ne s'affiche que lorsque le proche a démarré sa caméra.
- **Cause** : `frameOf` rendait la dernière frame stockée par le relais pour tout
  état sauf `offline`. Après une coupure du téléphone, le relais garde le paquet
  jusqu'à 2 min : la page montrait une image (atténuée) alors que la caméra ne
  tournait forcément plus, et une frame de plus de 8 s restait affichée en gris.
- **Correctif** : `frameOf` exige `status === 'live'` **et** une frame de moins de
  `PHOTO_FRESH_MS` (8 s) ; le champ `fresh` et la classe `.stale` de l'image
  disparaissent. Sans image, `#view` perd son cadre sombre et la page affiche
  « L'image apparaîtra quand ton proche ouvrira la caméra. » (lien absent :
  message du lien). Aucun changement côté téléphone : il n'envoie déjà `photo`
  que pendant que ses frames sont récentes.
- Preuves : `node --test suivi-parent/test/*.test.mjs` vert ; page rechargée en
  Chrome headless sur trois états (LIVE + frame récente, LIVE + frame de 20 s,
  SIGNAL LOST + frame) → image seulement dans le premier, cadre sombre absent
  dans les deux autres.
- Fichiers : `suivi-parent/live-view.mjs`, `live.mjs`, `styles.css`, `README.md`
  (+ `test/live-view.test.mjs`).
## 2026-10-29 — Trottoir : marcher dessus n'annonce plus « levez la jambe » (ordre user)

- `sidewalk` segmente le plan entier : marcher SUR le trottoir remplissait la
  bande basse et déclenchait l'alerte ( + 10 s de TwinLite tu à chaque fois).
- Affinage plan vs bord (`CurbSignal`) : labels de plan (`isGround`) — montée
  = bas décollé des pieds (< 0,95), descente = bas aux pieds mais haut borné
  (≥ 0,5), sinon sol ignoré ; labels de bord (`curb`…) : règle inchangée.
  Seuils priors à valider sur captures téléphone ; en cas de doute ça parle.
- Preuves : `:core` `testDebugUnitTest` vert sur r630 (`CurbWatcherTest` 16/16
  dont 5 nouveaux : plan sous les pieds, montée, descente, bordure au pied,
  seuils), `:core` + `:app` verts, `assembleDebug` vert, `check-kotlin-limits`
  OK. Rouge-avant-vérifié (2 échecs voulus).
- Fichiers : `CurbWatcher.kt` (+ test).
## 2026-10-29 — iSee : irisation bleu et jaune, très légère, sur le haut du verre

- **Cause** : le verre lisait une seule teinte de la palette (`pal(0.45)`) puis la
  multipliait par `film()`, trois longueurs d'onde fixes (650/540/450 nm) : c'est
  ce qui produisait des **reflets rose et vert**. Au repos, la bulle blind person
  (liquide bleu et ambre) n'avait donc jamais de reflet bleu.
- `IseePortraitOrb` : `film()` devient `sheen()` (gris 0..1, lumière seulement).
  L'irisation ne teinte plus rien : elle ne fait que les bandes claires et sombres.
  La teinte vient de **deux couleurs opposées de la palette active** — `pal(0.04)`,
  le bleu profond du début de palette, et `pal(0.34)`, l'or du milieu — séparées par
  une frontière ondulée par le bruit (`q.x + 0.45 * (grain - 0.48)`).
- Elle ne se pose que sur le **haut** du verre (`top = smoothstep(-0.15, 0.85, q.y)`) :
  les 50 % restants sont blanc laiteux, et le dosage garde l'enveloppe d'origine
  (`(0.03 + 0.62 * fres) * 0.45`). Le verre est donc aussi clair qu'avant, sans
  lavis de couleur.
- `suivi-parent/orb-shader.mjs` régénéré (`tools/export-caregiver-orb.py`) : le port
  WebGL reste identique à la source AGSL ; `tools/orb-frozen-baseline.json` refigé
  pour le changement demandé.
- Preuves (Chrome headless, WebGL, couronne 0,80–1,02 R). Verre du **haut** de la
  bulle, 90 points sondés, chroma ≥ 0,045 : **10 bleus, 16 jaunes, 64 neutres, 0 vert,
  0 magenta** (master : 0 bleu, 52 jaunes — son rose/vert était plus pâle encore que
  ce seuil). Couronne entière : au repos 600 pixels vifs dont **124 bleus** et 476
  chauds (master : 80 bleus / 656 chauds), 0 hors palette ; user qui parle 858 bleus,
  0 chaud ; iSee qui parle 926 chauds, 0 bleu ; page caregiver 436 chauds, 0 bleu,
  saturation moyenne 0,214 contre 0,217 avant.
- Docs alignées : `docs/ISEE-DESIGN-PORTRAIT.md` §1, `suivi-parent/README.md`.

## 2026-10-29 — iSee : trottoir/escaliers prioritaires sur la voix TwinLite (ordre user)

- TwinLiteNet ne voit que le sol ; SAM nomme les dangers (trottoir, escaliers
  montants/descendants). Sans garde, la consigne sol suivante coupait l'alerte
  en pleine phrase (un seul lecteur : dernier gagnant).
- `ProbeHazards.mutesTwinLite` : 10 s après chaque alerte, la voix TwinLite se
  tait (flèche affichée, reprise sans retard) ; fenêtre = cooldowns guetteurs,
  danger persistant = silence continu. Branché `IseeViewModel → IseeTwinLite →
  TwinLiteWiring(muted)` ; `lastAlertMs` sur les deux guetteurs (pur).
- Preuves : `:app` `testDebugUnitTest` vert sur r630 (`ProbeHazardsTest` 5/5,
  `TwinLiteWiringTest` 10/10), `:core` + `:app` verts, `assembleDebug` vert,
  `check-kotlin-limits` OK. Doc `VOIX-DANGER.md` § 8.
- Fichiers : `ProbeWiring.kt`, `TwinLiteWiring.kt`, `IseeTwinLite.kt`,
  `IseeViewModel.kt`, `CurbWatcher.kt`, `StairWatcher.kt` (+2 tests).
## 2026-10-29 — Traversée : la voix annonce le passage piéton reconnu

- La proposition de mode traversée était **visuelle seulement** (pastille ambre) :
  une personne aveugle ne pouvait pas la recevoir. Elle est maintenant dite.
- `CrossingText.offer` (`:core`) : « Passage piéton. Dites traverser. » /
  « Crosswalk. Say cross for crossing mode. » — elle dit **quoi faire**, pas
  seulement ce qu'on voit.
- `isee.observeCrossingOffer` : l'offre est un **état**, `distinctUntilChanged`
  + `filter` en font l'événement de montée → **une phrase par apparition**,
  jamais une par frame.
- Câblé dans `IseeViewModel.init` à côté de `observeSpoken`, sur le même canal
  vocal que « Mode traversée activé » (498 lignes, limite 500).
- Ce qui existait déjà et n'a pas bougé : l'état du feu est parlé aux transitions
  (`CrossingAnnouncer`), le cadre et l'étiquette sont dessinés sur la vidéo
  (§7), et l'état est relu localement toutes les 150 ms.
- **Preuves** : `CrossingTextTest` 4, `CrossingOfferVoiceTest` 3 (une phrase par
  apparition, réannonce après disparition, langue des réglages) ; `:core`
  1 836 tests + `:app` 1 067, 0 échec ; `check-kotlin-limits` OK.

## 2026-10-29 — Feu : le corpus PTL trouvé sur Hugging Face, mesuré, non livré

- Après « aucune donnée joignable », une source réelle : `ngqtrung/ptl-crossing-check`
  (Hugging Face, **MIT**, 5 059 photos 876×657, étiquettes humaines d'origine du PTL,
  point de vue du piéton au bord du trottoir, hébergé hors du quota Drive).
- Chaîne complète exécutée : SAM trouve un feu sur **99 %** de ces photos (468/474),
  contre **0 %** sur les photos de rue Panoramax (feu de 16 px). 1 881 crops
  d'entraînement, 470 de validation, 410 de test, disjoints.
- Arbre refit (profondeur 6, feuille 20) : verts **21 % → 52 %** sur le test PTL,
  faux verts 3,1 % → 3,5 % — **2,5× plus de verts à sûreté égale**.
- **Non livré** : sur les 9 crops **français**, l'arbre actuel fait 9/9 et le refit
  tombe à 6/9 (deux bonshommes verts en `Unknown`) ; à pureté 0,90 un **disque de
  voiture** passe même en `Green`. Un bonhomme shanghaïen n'est pas un bonhomme
  français.
- Le verrou n'est donc plus « trouver des données » mais « trouver des données
  **françaises** » — et la chaîne, éprouvée, sortira un modèle en une heure sur
  200-400 crops téléphone.

## 2026-10-29 — Feu vert : quatre tentatives de rappel, zéro gain (documenté)

- **Demande** : relever le rappel vert de 1/6 vers ≥ 4/6 sans faux vert.
- `docs/CALIBRAGE-FEU.md` (**nouveau**) : la chaîne de mesure, la chasse aux
  données, quatre variantes mesurées, et le chiffre qui condamne le réglage.
- Quatre voies testées sur les mêmes 16 crops : boîte brute **1/6**, crop sur la
  tache verte 2/6 **+1 faux vert**, crop sur la lentille 1/6 **+1 faux vert**,
  boîte élargie +25 % 1/7 **+1 faux vert**. Aucune n'est retenue.
- Cause démontrée : `ELONGATION_MIN = 1.5` suppose une tache allongée. Vrais
  verts rejetés : 1,00 · 1,07 · 1,11 · 0,15 ; disques voiture à rejeter : 1,30 ·
  1,47. **Ordre inversé**, aucun seuil ne sépare.
- Données : **Ampel-Pilot est mort** (`NoSuchBucket`, la source citée dans
  `TrainedLightColor`), ImVisible bloqué par le quota Drive (4 images lisibles
  sur 1 363 dans le zip tronqué), LytNet chargé mais inutilisable (prétraitement
  0-255 non documenté, `AvgPool2d(12,9)` figé, domaine plein cadre US).
- Piège écarté : l'élargissement +25 % fait passer l'acceptation du vert de
  26 % à 40 % sur 280 crops jittés — mais ces crops viennent de **6 feux**, et
  sur les photos indépendantes il crée un faux vert. Mémoire, pas généralisation.
- **Recommandation écrite** : ne pas toucher la porte ; il faut des données
  labellisées dans le domaine SAM (235 lampes Panoramax, ou 200-400 crops
  téléphone jour+nuit).

## 2026-10-29 — Traversée : un passage piéton reconnu propose le mode

- **Demande** : si l'app reconnaît un passage piéton, qu'elle propose de passer
  en mode Traversée.
- Constat : `crosswalk` est **déjà** l'un des douze prompts par défaut
  (`LivePrompts.default`) — SAM le détecte depuis le 29/10 et personne ne
  l'affichait. Aucun concept à ajouter, aucun coût serveur.
- `core/crossing/CrossingOffer.kt` (**nouveau**, pur) : étiquettes canonicalisées
  (`crosswalk`, `zebra crossing`, `passage piéton`…), score ≥ 0,40, et
  `CrossingOfferGate` — 3 frames sur les 5 dernières, silence d'une minute après
  un refus, rien en mode actif. 8 tests JVM.
- `isee/CrossingWiring.CrossingOfferWatch` : mémoire de processus (la garde des
  500 lignes ne laisse pas de champ de plus dans `LiveProbe`, resté à 499).
- `LiveProbeState.crossingOffer` + `LiveProbe` publie la proposition à chaque
  frame ; `crossingButtonLabel` gagne un troisième cas, la pastille passe en
  ambre sur « Passage piéton — traversée ? ». **Un tap active** — même geste
  qu'avant, jamais d'activation automatique.
- Couper le mode vaut refus : `setCrossingActive(false)` pose `declined`, sinon
  la proposition reviendrait à la frame suivante.
- **Preuves** : `CrossingOfferTest` 8, `CrossingWiringTest` 6 ; `:core`
  1 809 tests + `:app` 1 059, 0 échec ; `check-kotlin-limits` OK.
- `crosswalk` **mesuré** : 7 détections sur 5 photos de passages réels (scores
  0,54-0,89), **0** sur 5 scènes sans passage (2 salons, 3 autoroutes vues du
  ciel). Le concept discrimine sur cet échantillon (n = 10, détail et attribution
  dans `docs/TRAVERSEE.md` §8).

## 2026-10-29 — Traversée : la lecture locale du feu partout, et le rappel vert mesuré

- **Demande** : câbler le Direct et les lunettes sur le seau local à 150 ms, puis
  s'attaquer au rappel vert.
- `isee/CrossingLightPump` (objet, `CrossingLocalLight.kt`) : un seau **par écran**.
  Nécessaire parce que la rue des lunettes alimente la sonde depuis
  `GlassesProbeWiring`, pas depuis la carte caméra : deux `remember` auraient donné
  un seau qui ne reçoit jamais de frame.
- `traverse/TraverseWiring` : possède le seau du Direct (il possède déjà la boîte),
  créé à l'activation, effacé à la coupure. `local` porte la dernière lecture.
- `LiveViewModel.onFrame` : nourrit le seau et republie l'état à la cadence caméra
  (une seule ligne : la garde des 500 lignes est à 498). `LiveScreen` dessine déjà
  `crossing` / `crossingBox`, rien à y changer.
- `GlassesProbeWiring` : même seau que le téléphone, nourri par le pont des lunettes.
- `docs/MESURES-SAM3-FEUX.md` §6 : trois tentatives de rappel vert mesurées sur les
  mêmes 16 crops — crop sur la tache verte et crop sur la lentille allumée
  **n'améliorent pas** (1/6 et 2/6) et **introduisent un faux vert** (un bras de
  bonhomme debout, élongation 6,50). Recommandation : ne pas toucher
  `ELONGATION_MIN`, collecter d'abord.
- **Preuves** : `:app` 1 059 tests, 0 échec ; `check-kotlin-limits` OK.

## 2026-10-29 — Traversée : la couleur du feu se relit localement, ≤ 150 ms

- **Demande** : que la reconnaissance du feu ne dépende pas de la cadence du
  direct (350/500/900 ms) ni de l'aller-retour.
- `core/live/LightClock.kt` (**nouveau**, pur) : seau de 150 ms, l'instant est
  toujours passé en argument — testé en JVM (`LightClockTest`, 6 tests).
- `isee/CrossingLocalLight.kt` (**nouveau**) : sur chaque frame caméra, si une
  boîte serveur est connue et si le créneau est libre, crop + `PedLightGate`
  sur `Dispatchers.Default` et publication d'un `StateFlow`. La lecture est
  injectable : les tests JVM n'ont pas besoin d'Android (7 tests).
- `isee/IseeVisionCard.kt` : la couleur vient du seau local quand il a parlé,
  la boîte du dernier passage serveur (`CrossingOnVideo`). Le seau est créé
  une fois par carte (`rememberCrossingPump`) et alimenté dans le callback
  caméra, à côté de `twinlite.offerFrame`.
- **Pourquoi deux cadences** : la couleur se périme (150 ms), la position non
  (un feu ne se déplace pas). L'état affiché cesse d'avoir l'âge de l'A/R.
- Lunettes : pas encore de seau local (frames du pont), l'affichage y garde
  l'état du serveur.
- **Preuves** : `LightClockTest` 6, `CrossingLocalLightTest` 7 ; `:core`
  1 796 tests + `:app` 1 042, 0 échec ; `check-kotlin-limits` OK.
- **Limite dite tout haut** : plus frais n'est pas plus juste. La mesure du
  jour donne 1 vert accepté sur 6 (`docs/MESURES-SAM3-FEUX.md` §6) — c'est ce
  chiffre-là qu'il faut bouger ensuite, pas la cadence.
## 2026-10-29 — proche : la navigation publie position + trajet au relais

- **Cause (ordre user)** : le trajet ne partait que depuis l'accueil iSee —
  dès que l'écran navigation (confirmation, guidage) s'affichait, l'accueil
  était dépilé et le proche ne recevait plus rien (ni position ni barre).
- `VoiceNavRoute` publie via `ShareRelay` (position + `trip`, pas de photo
  ni scène) tant que l'écran est affiché ; `sharedTripOf` déménage de
  `IseeShare.kt` vers `voicenav/VoiceNavTrip.kt` (une seule implémentation
  pour les deux écrans, tests inchangés).
- Preuves : `:app` `testDebugUnitTest` verts sur r630
  (`IseeShareTest` 5/5 dont le trajet), `check-kotlin-limits` OK. Recette
  relais (barre du proche en guidage) après merge, avec une vraie session.

## 2026-10-29 — Traversée : l'état du feu s'affiche sur la vidéo, à côté des masques
## 2026-10-29 — lunettes : le fichier garde le cadre des lunettes (plus de quart de tour)

- **Objectif** : la vidéo de démonstration ne doit pas montrer une scène couchée.
- **Correction** : `KEY_ROTATION` n'est plus écrit et `videoRotationFor` disparaît. Le capteur
  des lunettes est **portrait natif** et son contenu est **déjà droit** dans ce cadre
  (`VideoQuality.HIGH` = 720×1280) : le quart de tour de la veille couchait la scène. Le fichier
  sort en **9:16**, identique à ce que l'app affiche.
- **Le 16:9** ne peut venir ni du capteur (`StreamConfiguration` n'expose ni format ni
  orientation), ni d'un recadrage sans perte : une bande 16:9 dans du 9:16 ne garde que **32 %**
  de la hauteur d'image (504×896 → 504×283). Il se fait donc **au montage**, sur la source
  intacte ; l'app ne produit aucun export 16:9.
- Preuves : `GlassesVideoRecorderTest` ne teste plus que le nom et l'unicité ;
  `:app:testDebugUnitTest` et `tools/check-kotlin-limits.py` verts.
- **Manque** : pas d'export 16:9 dans l'app ; pas de limite de durée ni de taille.

## 2026-10-29 — lunettes : le son entre dans la vidéo enregistrée

- **Objectif** : la vidéo de démonstration doit porter le son des lunettes, pas seulement
  l'image, et se retrouver dans la galerie après la session.
- `GlassesAudioTap` (`app/glasses/`) : **une seule** collecte du micro pour toute la session,
  répartie entre l'enregistreur et la dictée. Deux abonnés au même flux SDK se volaient des
  trames ; le relais supprime ce risque.
- `GlassesAudioEncoder` : PCM 16 bits → **AAC-LC 64 kbit/s** (`MediaCodec`), rappels de format
  (`csd-0`) et d'échantillon sur le fil appelant, horodatages du flux conservés.
- `GlassesVideoRecorder` : deux pistes dans le **même** MP4 (H.265 recopié + AAC), déclarées
  avant `start()`, origine temporelle commune (le plus tôt des deux premiers horodatages) ;
  attente du format AAC bornée à **4 s** ou 150 images, puis écriture **sans son** plutôt que
  rien ; le message de fin dit « avec le son » / « sans son ».
- **16:9** : rien ne change côté fichier, et c'est une contrainte du SDK — `StreamConfiguration`
  (AAR 1.0.0) n'expose ni format ni orientation ; le capteur livre un tampon portrait dont le
  contenu est déjà droit (rotation `KEY_ROTATION` retirée le même jour, entrée ci-dessus).
- Preuves : `:app:testDebugUnitTest` vert (955 tests, dont le message de fin dans
  `GlassesVideoRecorderTest`) ; `tools/check-kotlin-limits.py` OK.
- **Manque** : pas de limite de durée ni de taille. **Non mesuré** : la sortie réelle d'un MP4
  H.265 + AAC sur le téléphone, faute de matériel ici.

## 2026-10-29 — lunettes : la vidéo filmée est enregistrée dans la galerie

- **Objectif** : avec une session iSee ouverte, ce que filment les lunettes doit exister en
  fichier (vidéo de démonstration), en 16:9.
- `GlassesVideoRecorder` (`app/glasses/`) : les trames **H.265 déjà compressées** sont écrites
  telles quelles dans un `MediaMuxer` (aucun ré-encodage), à partir de la trame de
  configuration (`csd-0`) ; fichier publié dans la galerie
  `Movies/iSee/isee-lunettes-AAAAMMJJ-HHMMSS.mp4`.
- **16:9 sans perte** : `videoRotationFor` écrit `KEY_ROTATION = 90` pour un flux 9:16
  (504×896 → 896×504, exactement 16:9) ; aucun recadrage, aucune image en moins.
  *(Retiré le même jour : le contenu du flux est droit dans son cadre portrait, un quart de tour
  couchait la scène — voir l'entrée « plus de quart de tour ».)*
- Branché au **seul point de passage** des trames (`GlassesCamera.pushCopy`, `stop`), donc une
  vidéo par session, sans bouton à oublier ; ligne d'état « Vidéo : … » dans
  Réglages → Lunettes (`GlassesUiState.recordingName`, `GlassesStatus.videoLine`).
- Pannes dites et nettoyées : pas d'en-tête H.265, aucune image, muxage refusé ; un fichier
  incomplet est supprimé de la galerie (`recordingEmpty`, `recordingFailed`).
- Preuves : `:app:testDebugUnitTest` vert (954 tests, dont `GlassesVideoRecorderTest` 4 et la
  ligne vidéo dans `GlassesUiStateTest`) ; `tools/check-kotlin-limits.py` OK (969 fichiers).
- **Manque** : pas de piste audio (PCM non encodé en AAC), pas de limite de durée ni de taille.
- **Non mesuré** : le MP4 réel n'a pas été produit ici (pas de matériel) ; la rotation dépend
  de `MediaMuxer`, à vérifier au premier enregistrement sur la paire.

## 2026-10-29 — lunettes : les autorisations se demandent, les erreurs se traduisent

- **Cause du blocage** : iSee n'a jamais demandé caméra ni micro. Le SDK 1.0.0 n'expose pas
  `requestPermission` : il fournit `Wearables.RequestPermissionContract` (contrat d'activité →
  `fb-viewapp://device/permissions/request`), resté inutilisé. Le seul chemin documenté
  jusque-là — la fiche de l'app dans Meta AI — n'affiche que « Déconnecter » en Developer
  Mode. D'où `CAPABILITY_DENIED` à chaque session et aucune photo possible.
- `MainActivity` : lanceur `Wearables.RequestPermissionContract()` et mémorisation de la
  permission en cours, passés au contrôleur (`permissionRequester`).
- `GlassesSection` : deux boutons « Autoriser la caméra (Meta AI) » et « Autoriser le micro
  (Meta AI) », actifs tant que la permission manque ; accordée, la caméra s'attache aussitôt à
  la session en cours, sans la refaire.
- Nouveaux fichiers : `glasses/GlassesPermission.kt` (verdict → état + message,
  `GlassesPermissionGate`, `reportSessionError`) et `glasses/GlassesDevices.kt` (suivi de la
  paire extrait du contrôleur, 482 → 469 lignes ; garde `check-kotlin-limits` OK).
- **Erreur par erreur**, plus aucune description anglaise nue à l'écran : les 18
  `DeviceSessionError`, les 8 `StreamError`, les 4 `CaptureError`, les 7 `PermissionError` et
  les 5 `RegistrationRequestError`. `DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED` et
  `INSUFFICIENT_SDK_VERSION` ouvrent la page de mise à jour (`openDATGlassesAppUpdate`).
- `handleIntent` : les deux `onFailure` du retour d'enregistrement sont dits (avant : silence
  complet quand Meta AI revenait avec une demande invalide).
- Preuves : `:app:testDebugUnitTest` vert (949 tests, dont `GlassesPermissionTest` 6) ;
  `tools/check-kotlin-limits.py` OK (967 fichiers).
- **Non mesuré** : la page de permission de Meta AI n'a pas pu être ouverte depuis ce poste ;
  que le contrat ouvre la question et accorde la caméra reste à constater sur la paire.

## 2026-10-29 — lunettes : la voix de la production sort dans la paire

- **Demande** : la reconnaissance du feu piéton ne vivait que dans une pastille
  en bas de carte et dans la voix ; elle devait se voir **sur la même vidéo que
  les masques SAM**, à l'endroit du feu.
- `isee/CrossingOverlayLayout.kt` (**nouveau**, pur) : `lightMarker` place la
  boîte normalisée du feu à l'écran avec la **même** transformation que les
  pastilles de distance (`cropTransform`, `ContentScale.Crop`) ; `tagRect` pose
  l'étiquette au-dessus, ou en dessous quand le feu touche le haut ; couleurs et
  libellés (`VERT` / `ROUGE` / `DOUTE`) ont une seule source, `crossingAccent` /
  `crossingTag` — la pastille de carte les réutilise au lieu de recopier les
  teintes.
- `isee/CrossingOverlayLayer.kt` (**nouveau**, Compose) : un cadre de la couleur
  de l'état + une étiquette courte, dessinés dans un `Canvas`. Cadre et non
  rectangle plein : même règle que les distances, l'image reste lisible.
- `isee/CrossingWiring.kt` : `crossingLightOf` lit **une** fois la frame et
  rend la boîte **et** l'état ; `crossingPatchOf` publie les deux. L'étiquette ne
  peut donc pas désigner un autre feu que celui qui a été classifié, et le
  décodage du crop n'est pas fait deux fois par frame.
- `isee/LiveProbeState.kt` : `crossingBox` (boîte normalisée de la dernière
  frame, `null` = aucun feu dans le champ). `LiveProbe.kt` la publie ; le
  fichier reste à 499 lignes (limite 500).
- Affiché sur **trois** surfaces : l'accueil iSee (caméra du téléphone via
  `CameraLive`, et lunettes) et l'écran **Direct** (`LiveScreen.LiveCameraStage`),
  qui est le vrai flux temps réel. `TraverseWiring.light` garde la boîte avec
  l'état (elle était jetée : seul `lightGreen` sortait), `LiveViewModel.withCrossing`
  (`app/LiveCrossingPublish.kt`, nouveau — `LiveViewModel` reste à 498 lignes)
  publie le couple dans `LiveUiState`, et `LiveScreen` pose la même couche.
- Temps réel : rafraîchi à la cadence du direct (1 fois par frame analysée), avec
  l'A/R complet de retard — même compromis que les masques et les distances
  (`docs/LATENCE-BUDGET.md`). Décodage JPEG sur `Dispatchers.Default` : pas
  d'à-coup UI.
- **Preuves** : `CrossingOverlayLayoutTest` 11 tests (couleurs, vocabulaire
  identique à la pastille pour les états tranchés, cadrage panoramique recadré,
  boîte débordante ramenée, étiquette déplacée sous le cadre puis bornée) ;
  `:core` 1 789 tests + `:app` 995 tests, 0 échec ; `check-kotlin-limits` OK.

## 2026-10-29 — iSee : la voix dit les obstacles proches, même non mesurés
## 2026-10-29 — iSee : guidage TwinLiteNet sur la caméra (flèche + voix) ; la voix dit les obstacles proches, même non mesurés ; suivi : le lien se lit dans les Réglages, un lien mort se dit ; voix : fin du guidage surprise, confirmation obligatoire ; direct : portes et barrières reconnues en extérieur (ordre user)

- **Cause** : le user marche avec l'app, elle ne dit plus les obstacles même en
  approchant. Deux causes lues dans le code : (1) `observeSpoken` en
  `collectLatest` brut annulait la synthèse en cours à chaque frame publiée,
  même quand la phrase n'avait pas changé — synthèse lente + frames continues
  = silence total ; (2) la matière au contact (boîte coupée ⇒ non mesurable)
  disait un mot puis se taisait (clé `?` constante, nouveauté muette), sans
  jamais passer en « Stop. ».
- **Voix** : `ProbeWiring.observeSpoken` : `distinctUntilChanged` sur `spoken`
  (seule une phrase plus fraîche annule), « Stop. » urgent (clip pré-synthétisé,
  coupe le chat) ; guetteurs trottoir/escaliers en `collect` (une alerte
  confirmée ne s'annule pas). `Guidance` : matière non mesurée qui remplit le
  champ (`STOP_HEIGHT_FRAC = 0,5`, `STOP_WIDTH_FRAC = 0,05`, emprise
  `LabelExtent` portée par `PlacedLabel`) ⇒ « Stop. » immédiat, sauf peinture
  au sol (`crosswalk`) ; `ProximityVoice` : moteur de synthèse injectable
  (faux en test).
- **Guidage TwinLiteNet sur la caméra** : flèche + voix ; fin du guidage
  surprise, confirmation obligatoire avant d'appliquer la consigne.
- **Suivi** : le lien se lit dans les Réglages ; un lien mort se dit (annoncé
  à voix haute).
- **Direct** : portes et barrières reconnues en extérieur (ordre user).
- Preuves : `:core` + `:app` `testDebugUnitTest` verts sur r630 (cœur 1785/1785,
  app 962/962, 0 échec — `GuidanceTest` 22/22, `GuidanceAnnouncerTest` 12/12,
  `ProbeWiringSpokenTest` 3/3 dont rouge-sans-fix vérifié par revert),
  `check-kotlin-limits` OK.
- Recette téléphone : main devant l'objectif → « Stop. » ; approche mesurée →
  consignes par seau puis « Stop. » — dus sur master après merge.

## 2026-10-29 — iSee : voix unique ElevenLabs (pack MP3 + lecteur partagé)

- `VoicePack` (`app/.../speech/`) : clips MP3 rejoués sans réseau (dossier
  par voix, TTL 30 j, miroir des annonces tap) ; `TwinLiteVoice` réécrite :
  clip → `SpeechPlayer` PARTAGÉ de l'écran (une seule voix, chaque entrée
  coupe la précédente), sinon synthèse ElevenLabs bornée 500 ms + cache,
  sinon silence + remplissage fond. Second moteur TTS système supprimé.
- Catalogue 20 concepts / 37 MP3 (`tools/voicepack-catalog.json` : TwinLite 8,
  traversée 10, nav statique 19) ; `tools/gen-voicepack.py` (clé par env,
  jamais stockée, voix Laure + params app) ; `tools/check-voicepack.py`
  (catalogue = code = fichiers) ; `docs/VOICEPACK.md`.
- MP3 générés voix Laure (`ELEVENLABS_API_KEY` par env, jamais stockée) :
  37 clips FR/EN versionnés dans `app/src/main/res/raw/` + `vp_voice.txt`.
- Preuves : `:app` `testDebugUnitTest` vert sur r630 (`VoicePackTest` 10/10,
  `TwinLiteVoiceTest` 5/5, `TwinLiteWiringTest` 8/8), `assembleDebug` vert,
  `check-voicepack.py` OK, `check-kotlin-limits` OK.

## 2026-10-29 — page caregiver : la carte disparaît, l'image du proche et la barre de trajet la remplacent (ordre user)

- `suivi-parent/index.html` : plus de carte (ni MapLibre, ni clé Google, ni appel
  tiers) ; à sa place la **dernière frame de la caméra iSee** et, pendant un
  guidage, une **barre de progression liquide** du départ à l'arrivée (teintes de
  la boule iSee, liquide + bille qui avance).
- Contrat du relais étendu, sans casser les anciens clients : `POST /api/share`
  accepte `photo:{jpeg,atMs}` (JPEG base64 ≤ 120 000 caractères) et
  `trip:{destination,totalM,remainingM,remainingS,progress}` ;
  `GET /api/state` les rend. Une écriture sans image (service de suivi écran
  éteint) conserve la dernière photo, dont l'âge est rendu à la page.
- App : `ShareBroadcaster.publish` et `ShareRelay` portent le trajet et la photo ;
  `IseeShare` compose `trip` depuis `VoiceNavUiState.Guiding` (`progressOf`) et
  `SharedCameraFrame` publie la frame déjà convertie pour SAM, seulement si elle a
  moins de 6 s (sinon la page l'efface au lieu de la faire passer pour du direct).
- La clé de fond de carte ne traverse plus l'URL de la page (`caregiverPageUrl`,
  `CaregiverScreen`), et le champ Réglages Google Maps reste pour les liens Maps.
- Fichiers : `suivi-relais/src/{contrat,worker}.js`, `suivi-parent/api/{share,state}.js`,
  `suivi-parent/{index.html,styles.css,live.mjs,live-view.mjs,README.md}`
  (`maps.mjs` supprimé), `core/share/{SharePayload,CaregiverPage}.kt`,
  `app/{ShareBroadcaster,ShareRelay,CaregiverScreen,AppNav}.kt`,
  `app/isee/{IseeShare,SharedCameraFrame,IseeVisionCard}.kt`.
- Preuves caregiver, relancées après rebase sur `origin/master` 71d4444a
  (`:core` relevé sur 61e91cb8) : `node --test suivi-parent/test/*.test.mjs
  suivi-relais/test/*.test.mjs` 26/26 (relais 10/10 dont photo + trajet, page 5/5,
  API héritée 4/4, session 7/7) ; `:app:testDebugUnitTest` vert ; `:core` 1773
  tests, seuls les 5 `PrivateFileSettingsStoreTest` (permissions POSIX sous
  Windows) échouent, les mêmes 5 sur `origin/master` seul (rejoué) ;
  `:app:assembleDebug` vert, APK 174 Mo avec `assets/caregiver/live-view.mjs`,
  sans `maps.mjs` ; `check-kotlin-limits.py` OK (`check-isee-design.py` signale
  une boule Portrait hors baseline, déjà le cas sur `master`).
- Limite : le relais déployé doit être republié (`suivi-relais/deploy.json`) pour
  que `photo` et `trip` traversent la production ; d'ici là la page affiche
  l'image d'attente et aucune barre, le reste étant inchangé.

## 2026-10-29 — voix : appui long sur les écrans navigation (tout à la voix) + iSee : flèche TwinLite remontée + log voix

- **Cause (ordre user)** : sur l'écran de confirmation de destination, « Dicter »
  renvoyait à l'accueil iSee — pas de micro sur place ; tout doit être
  faisable à la voix.
- `VoiceNavHold` + `VoiceNavHoldButton` (`app/.../voicenav/`) : appui long
  (micro dès l'appui, bouton dédié), même moteur que l'accueil
  (`IseeHoldMic`, Meta puis Scribe, plafond 75 s partagé), textes français ;
  le transcript part à la session existante (stop, oui/non, destination).
- `DestinationConfirmScreen` (« oui »/« non »/destination) et `GuidanceScreen`
  (destination/ordre stop) : le bouton remplace le renvoi vers l'accueil
  (boutons tactiles inchangés) ; `VoiceNavRoute` partage un micro, réglages
  depuis `AppNav` (snapshot, `IseeViewModel` déjà à 498 lignes).
- `TwinLiteLayer` : 28 → 150 dp — la flèche et son libellé disparaissaient
  sous le panneau statut SAM et le triangle du guidage (même zone bas-centrée) ;
  empilée au-dessus des deux désormais.
- `TwinLiteVoice.say` : `twinlite: dit « … » (urgent=…) : ok` — chaque consigne
  parlée est traçable au logcat (recette voix + observabilité).
- **Suivi (ordre user)** : le lien se lit dans les Réglages ; un lien mort se
  dit (annonce explicite à la voix plutôt qu'un silence).
- **Voix (ordre user)** : fin du guidage surprise — confirmation obligatoire
  avant de lancer le guidage.
- **Direct (ordre user)** : portes et barrières reconnues en extérieur.
- Preuves voix/iSee : `VoiceNavHoldTest` 11/11 + `:app` `testDebugUnitTest` verts
  sur r630 (8/8 `TwinLiteWiringTest` inchangés — voix `null` en test),
  `assembleDebug` vert, `check-kotlin-limits` OK. Recette voix sur téléphone
  après merge (voix du user requise) ; recette flèche + `dit` dues sur master
  après merge.
- Preuves : `:app` `testDebugUnitTest` vert sur r630 (8/8 `TwinLiteWiringTest`
  inchangés — voix `null` en test), `assembleDebug` vert, `check-kotlin-limits`
  OK ; recette flèche + `dit` dues sur master après merge.
## 2026-10-29 — caregiver : bulle Cacao & caramel

- Palette de la bulle caregiver : `BLUE_GREEN` est remplacé par `CACAO_CARAMEL`
  (`#48342F`, `#7C5748`, `#B88760`, `#E1BE8B`, `#B29C84`), la proposition 32 de
  la planche de couleurs, dans `suivi-parent/orb.mjs`. Une seule définition
  alimente les uniformes WebGL (`uIdle`, `uPalYou`, `uPalSee`) et les variables
  `--orb-color-*` du repli sans WebGL.
- `suivi-parent/styles.css` : le dégradé de secours lit ces variables au lieu des
  bleus et verts codés en dur. Lumière, rayon et animation inchangés.
- Reflets : le verre n'a plus de couleur propre. Dans `IseePortraitOrb`, `film()`
  ne garde que la modulation d'éclat (une seule longueur d'onde au lieu de
  650/540/450 nm) et `pal()` — la teinte médiane de la palette active — alimente
  le bord, le liseré et la boîte à lumière. Les deux bleus codés en dur
  (`0.55,0.62,0.8` et `0.04,0.37,1.0`) disparaissent, le fond du verre se teinte
  à 5 % de la palette. `suivi-parent/orb-shader.mjs` est régénéré depuis cette
  source AGSL par `python tools/export-caregiver-orb.py`.
- Les **deux** bulles sont concernées : la source AGSL est partagée par l'écran
  iSee (blind person) et la page caregiver ; `docs/ISEE-DESIGN-PORTRAIT.md` est
  aligné (« le verre n'a pas de couleur propre »).
- Garde-fou `tools/check-isee-design.py` : l'empreinte de la boule normalise les
  fins de ligne (CRLF → LF). Une baseline figée sous Windows ne valait plus en
  CI Linux, où le même code est en LF : la CI du run `a476a78` est tombée sur
  « boule Portrait modifiée » alors que le fichier était identique. Vérifié en
  repassant le garde sur un arbre extrait en LF (`git archive`), et la baseline
  est refigée sur l'empreinte normalisée.
- Preuves : `./gradlew :app:testDebugUnitTest --tests "*OrbShaderTest*"` vert
  (verrou des 15 subscripts constants), `node --check` sur le shader généré, et
  mesure des pixels de couronne dans Chromium (390 px, WebGL, états repos / user /
  iSee) : cacao 506 → **0** pixel hors palette brun-caramel (8-55°), Portrait
  316 → **0** pixel vert ou magenta. Ancien shader mesuré à l'identique pour
  comparaison.
## 2026-10-29 — lunettes : la voix de la production sort dans la paire

- **Cause** : le banc savait router la voix vers les lunettes (A2DP constaté sur
  la paire le 27/10), la production non — tout partait sur le haut-parleur du
  téléphone.
- `GlassesVoiceRoute` (`app/glasses/`) : retient l'état de session (`follow`,
  `forget`) et rend, **à chaque phrase**, la sortie Bluetooth de la paire
  (nom du SDK d'abord, puits de son seulement) ; hors session ou appareil
  refusé, `null` = sortie par défaut, aucun échec silencieux (ligne
  `GlassesVoice` au changement de décision).
- `SpeechPlayer` gagne `output: () -> AudioDeviceInfo?` (défaut `null`), posé
  sur `MediaPlayer.preferredDevice` avant chaque lecture, donc **un seul point
  de passage** pour le chat, l'analyse, l'intro, les bandeaux, le tap et
  Traverse ; branché dans `AppViewModel`, `IseeViewModel`, `VoiceAnnouncer`,
  `TapCaptureScreen`, `TraverseWiring`.
- Décision **partagée** avec le banc : `AudioOutput`, `pickBluetoothOutput`,
  `rankOf`, `isBluetoothOutput`, `bluetoothOutputs` déplacés de
  `glasses/bench/GlassesAudioRoute.kt` vers `glasses/GlassesAudioRoute.kt`
  (`git mv`, imports à jour) — une seule copie de la règle.
- Message `framesOnSpeaker` → `framesVoiceRoute` : « la voix des descriptions
  suit les lunettes, haut-parleur en repli ».
- Preuves : `:app:compileDebugKotlin` vert ; `:app:testDebugUnitTest` vert sur
  `dev.sam3vision.app.glasses.*` et `dev.sam3vision.app.isee.*`, dont
  `GlassesVoiceRouteTest` (5) et `GlassesAudioRouteTest` déplacé.
- **Non mesuré** : la voix réellement entendue dans la paire en production
  (à vérifier avec `adb logcat -s GlassesVoice`) ; la voix système (TwinLite,
  navigation) ne passe pas par ce routage.

## 2026-10-29 — iSee : guidage TwinLiteNet sur la caméra (flèche + voix)

- `TwinLiteGuide` (`core/.../segment/`) : tiers de la moitié basse du masque
  praticable → ←/↑/→/■, seuil 0,25, STOP sous le seuil partout ; `TwinLiteText`
  (FR/EN) ; `TwinLiteAnnouncer` sur `AnnouncementGate` (stabilité 2, délai 4 s,
  STOP urgent).
- `LiveSegmentMeter` (`app/.../segment/`, miroir `LiveDepthMeter`) : inférence
  fond ~3/s, masque frais < 1 s, modèle auto-téléchargé (release
  `segment-models-v1`, comme la profondeur) ; `TwinLiteWiring` + `TwinLiteLayer`
  (flèche bas-centrée verte/ambre/rouge) branchés sur la caméra iSee
  (téléphone + lunettes) ; voix système hors ligne (`TwinLiteVoice`,
  urgent = flush) ; `IseeTwinLite` tient le ViewModel sous 500 lignes.
- Suivi : le lien se lit dans les Réglages (lecture explicite du lien affiché),
  et un lien mort se dit (annonce du lien inactif au lieu du silence).
- Voix : fin du guidage surprise — plus d'annonce non sollicitée ; toute
  confirmation devient obligatoire avant de parler.
- Direct : portes et barrières reconnues aussi en extérieur, dans l'ordre
  défini par l'utilisateur.
- Preuves : `:core` + `:app` `testDebugUnitTest` verts sur r630
  (`TwinLiteGuideTest` 13/13, `TwinLiteAnnouncerTest` 12/12,
  `TwinLiteWiringTest` 8/8), `assembleDebug` vert, `check-kotlin-limits` OK.
- Recette téléphone : flèche + annonce dues sur master après merge.

## 2026-10-29 — profondeur : repli MiDaS supprimé, DA2 seul (ordre user)

- **Cause** : deux moteurs embarqués (DA2 ONNX 174 ms, MiDaS LiteRT 275 ms
  sur Redmi) ; MiDaS, hors seuil < 200 ms, ne servait que de repli —
  le user garde DA2, plus précis et ~1,6x plus rapide.
- `DepthBackend` : `MIDAS` et `AUTO` supprimés (reste `DA2`, `OFF`) ;
  `fromName` migre les réglages persistés pré-29/10 (`AUTO`, `MIDAS`) vers
  `DA2`, inconnu/absent ⇒ `OFF`. Supprimés : `LiteRtDepthEstimator.kt`,
  `DepthPreprocess.normalizeHWC` (+ son test), le banc MiDaS instrumenté.
- LiteRT reste une dépendance (segmentation TwinLiteNet) ; le `.tflite`
  reste sur la release `depth-models-v1`, rien ne le télécharge plus.
- Fichiers : `core/depth/DepthEstimator.kt`, `app/depth/DepthEstimators.kt`,
  `ModelDownloader.kt`, `DepthPreprocess.kt`, `OnnxDepthEstimator.kt`,
  `DepthSettings.kt`, `Settings.kt` (défaut DA2), `AppViewModel.kt`,
  `DepthMap.kt`, `PointCloud.kt`, `app/build.gradle.kts` (commentaire),
  `README.md`, `docs/DEPTH.md`, `docs/TESTING.md`,
  `docs/TWINLITE-SAM31.md`, `NOTICE-depth.txt`.
- Preuves : `:core:test` + `:app:testDebugUnitTest` verts sur r630 ;
  `DepthEstimatorTest` verrouille la migration `fromName`.

### Voix — fin du guidage surprise, confirmation obligatoire

- **Cause** : `IseeVoiceNav` passait `autoStartOnVoice = true` — toute dictée
  ressemblant à une destination (y compris une question « comment aller
  à… ? », motif `aller à X`) démarrait le guidage Valhalla sans écran ni
  bouton, puis la voix répétait les consignes à chaque fix GPS.
- La dictée n'ouvre plus que `DestinationConfirmScreen` (« Destination : X »
  + résumé + « Démarrer » / « Annuler ») : aucun guidage sans objectif
  explicite. `autoStartOnVoice = true` subsiste pour les tests de session.
- Fichiers : `IseeVoiceNav.kt` (retrait du forçage), `docs/NAVIGATION.md`,
  `docs/GPS_GUIDE_UTILISATEUR.md`, `docs/RECETTE-NAVIGATION-VOCALE.md`.
- Preuves : `VoiceNavSessionTest` + `VoiceNavSessionWiringTest` (chemin
  confirmation déjà verrouillé) verts en CI ; aucun test modifié.

### Direct — portes et barrières reconnues en extérieur (ordre user)

- **Cause mesurée** : le lien de suivi n'existait que dans le message WhatsApp.
  Impossible de vérifier quel flux un téléphone publie, donc un lien devenu faux
  (app réinstallée → identifiant de flux réattribué) ressemblait trait pour trait
  à un proche qui n'a pas encore filmé. Côté page, le relais répond
  `{ok:true, live:false}` **sans `serverAt`** pour un identifiant jamais publié —
  exactement la même chose qu'un flux qui s'est tu ; la page attendait donc
  « En attente du téléphone de ton proche » indéfiniment, sur une carte au niveau
  monde sans repère, sans jamais nommer le problème.
- Réglages → « Parent tracking » : le lien exact envoyé par WhatsApp est **affiché**
  (sélectionnable) avec « Copy link » (presse-papier, confirmation 2 s), plus un
  avertissement quand « Send position… » est coupé (le lien existe alors mais
  aucun envoi ne part). Section extraite dans `app/ParentShareSettings.kt` :
  `SettingsScreen.kt` repasse sous le plafond de 500 lignes.
- Page caregiver embarquée : état `NO STREAM` (`neverPublished` dans
  `session.mjs`, pastille grise, recentrage désactivé) et message actionnable
  « Aucun suivi pour ce lien : il a peut-être changé (app réinstallée)… ».
  Aucun « lien mort » annoncé avant la première réponse du relais, et un flux
  simplement muet garde son état OFFLINE.
- `LivePrompts.default` : 10 → 12 concepts (`door`, `barrier` en plus, en fin
  de liste) ; `MAX = 16` et `PROMPT_LIMIT = 16` inchangés (12 < 16, soit
  2 inférences/frame assumées de plus sur le chemin tracker).
- `KnownSizes` calibre `barrier`/`barrière` à 1,00 m (barrière de chantier
  type Vauban ; `door`/`porte` déjà à 2,05 m) : la distance sténopé s'affiche
  et le guidage obstacle annonce portes et barrières comme tout obstacle.
- Côté serveur, rien à changer : `porte→door` et `barrière→barrier` déjà dans
  `server/backend/concepts.py`, plafond déjà à 16 (`tracking.py:53`).
- Preuves : `node --test suivi-parent/test/session.test.mjs
  suivi-parent/test/relay.test.mjs` → 11/11 verts (dont le nouveau cas
  `neverPublished`) ; `:app:testDebugUnitTest` (`CaregiverShareLinkTest`,
  `ParentShareTest`) et `:core:testDebugUnitTest` (`CaregiverPageTest`) verts ;
  `:app:assembleDebug` vert ; `tools/check-kotlin-limits.py` OK ; asset
  `assets/caregiver/live.mjs` de l'APK contient bien l'état `NO STREAM`.
- Preuves (direct) : `:core` `LivePromptsTest` (dont « portes et barrières suivies en
  extérieur »), `LivePromptsNavigationTest`, `StairConceptsTest`,
  `KnownSizesTest` (dont « portes et barrières calibrées ») ;
  `check-kotlin-limits.py` OK.

## 2026-10-29 — iSee : la vidéo d'environnement s'écrit enfin au premier tap

- **Cause mesurée sur l'appareil** (SM-G780G, build `1.0.426-4c06e4c`) : au premier
  tap de iSee, `MediaCacheStore.newVideoFile()` rendait un chemin dans
  `cacheDir/isee` **sans créer le dossier** (il ne naissait qu'au premier MP3).
  CameraX mesure l'espace libre du dossier parent avant d'écrire et journalisait
  `IllegalArgumentException: Invalid path: /data/user/0/dev.sam3vision.app/cache/isee` ;
  le MP4 n'était pas écrit, et le second tap affichait « enregistrement perdu ».
  Vérifié sur le téléphone : `cache/isee` absent après le tap, aucune vidéo.
- `newVideoFile()` / `newPhotoFile()` créent désormais le dossier (`pendingFile`),
  donc la vidéo **et** la photo de l'appui long ont un chemin réel dès la première
  installation.
- Panneau de debug du direct : en mode `dense` il ignore l'inset de barre d'état et
  était posé en haut de la rue, sous l'horloge et derrière le wordmark « iSee ».
  Il passe en bas à gauche, après le fondu (`IseePortraitCamera.kt`) ; le contenu de
  la rue est sorti dans `StreetFeed` pour rester sous le plafond de 50 lignes.
- Preuves : `MediaCacheStoreTest` (nouveau cas « le dossier est cree avant le premier
  enregistrement ») et `IseeCameraCoordinatorTest` verts ; `:app:assembleDebug` vert ;
  `check-kotlin-limits` OK.

## 2026-10-28 — suivi : plus de « Léo », le lien porte le flux du téléphone

- **Cause du lien « leo »** : le message WhatsApp était écrit en dur « Suivi de Léo
  en direct » et `shareLink` retombait sur l'ancien défaut commun (`shareId` « leo »)
  dès que l'identifiant manquait. Deux téléphones publiaient donc le même flux, et
  il fallait renommer à la main le champ « Stream id » avant chaque partage.
- Message neutre : « Suivi en direct : … », aucun prénom dans le partage.
- `shareLink` ne devine plus aucun flux : identifiant vide → lien du relais sans
  `?share=`, jamais l'ancien « leo » qui ferait suivre le téléphone d'un autre.
- `Settings.shareId` vide par défaut ; `DEFAULT_SHARE_ID` devient `LEGACY_SHARE_ID`,
  seulement reconnu par `seedShareRelay`, qui en attribue un aussi à un champ vide :
  une installation neuve n'attend plus une visite des Réglages pour avoir son lien.
- Réglages : le champ « Stream id » disparaît, il n'y a plus rien à renommer.
- Preuves : `SettingsSeedTest` (dont « installation neuve » et « ancien défaut
  commun »), `ParentShareTest`, `SettingsCodecTest` verts ; `:app`
  `testDebugUnitTest` et `assembleDebug` verts ; `check-kotlin-limits` OK.
  (`PrivateFileSettingsStoreTest` échoue sous Windows, permissions POSIX : même
  échec sur `master`, hors de ce changement.)

## 2026-10-28 — suivi : la carte caregiver de l'app reçoit enfin le proche

- **Cause mesurée** : le relais du site (`suivi-parent.vercel.app`, déploiement figé
  au 27/10 21 h 03) répondait `403 clé invalide` à chaque `POST /api/share` d'une
  installation neuve (`shareWriteKey` vide par défaut) : aucun état stocké, donc
  carte vide, sans message ni côté proche ni côté caregiver.
- `suivi-relais/` : relais dédié à l'app (Worker + D1), **un état par identifiant
  de flux**, contrat inchangé (`POST /api/share`, `GET /api/state`) pour la page
  embarquée. La clé d'écriture est liée au flux au premier envoi (403 pour une
  autre clé) ; `SHARE_WRITE_KEY` reste un verrou global optionnel.
- `Settings.DEFAULT_PARENT_SITE_URL` pointe sur ce relais ; `seedShareRelay`
  migre l'ancienne URL Vercel et génère à l'installation un **identifiant de flux
  unique** (sinon deux proches partageaient « leo ») et la clé d'écriture liée à
  ce flux — plus rien à saisir. `loadedSettings` les **enregistre** au premier
  chargement (`seededFromBuild` seul en fabriquait de nouveaux à chaque lancement,
  donc le relais aurait refusé tout envoi après le premier). Conséquence : l'ancien
  lien « leo » ne suit plus rien, le proche doit renvoyer son lien une fois.
- Lien caregiver : l'identifiant d'un ancien lien (`?share=`) est reconstruit sur
  le relais (`shareIdOf` / `relayShareLink`) ; une URL sans identifiant ne
  sélectionne jamais le flux local.
- `ShareReporter` rend un résultat (`Sent` / `Skipped` / `Refused(code)` /
  `Unreachable`) au lieu d'un booléen avalé : Réglages affiche le refus, le relais
  injoignable ou la localisation coupée (`ShareHealth`).
- Preuves : `node --test suivi-relais/test/relais.test.mjs` 8/8 ; en production
  (`site-1607fe95049a481989380f019f9067f3.freebuff.page`) deux flux isolés, écriture
  sans clé 200, clé étrangère 403, flux inconnu `live:false`.
- Limite connue : le relais ne sert pas la page HTML, donc un lien ouvert dans un
  navigateur ne montre pas la carte (l'app, elle, l'affiche).
## 2026-10-28 — direct : escaliers câblés à la ligne d'état + façade sol (ordre user)

- `StairsFeed` (`core/.../stairs/`, pur JVM) : luma 256×103 → `StairDetector` +
  `StairWatcher` → phrase tenue 3 s ; branché dans `LiveLocalOverlayWiring.tick`
  (0 ligne dans `LiveViewModel`, déjà à 498/500), publié dans
  `LiveUiState.stairsLine` → ligne d'état (changement seul, pas de recompo 30 ips).
- `GroundPipeline` (`core/.../ground/`) : façade d'une frame (nuage → RANSAC →
  labels → régions → risque → rapport) + `PointClouds.gathered` (prod, le helper
  de test délègue) ; non branchée au direct (pas de profondeur dans la boucle
  live — TODO cadence `LiveDepthMeter`).
- Voix escaliers : TODO (ni scope ni annonceur dans le câblage overlay).
- Preuves : `:core` + `:app` `testDebugUnitTest` verts sur r630
  (`StairsFeedTest` 5/5, `GroundPipelineTest` 3/3, `LiveStateTest` +2),
  `check-kotlin-limits` OK. Seuils MIT à recalibrer sur captures téléphone.

## 2026-10-28 — GPS : piéton partout, démarrage vocal, plus d'écran fantôme

- `VoiceNavSession` démarre le guidage **dès la dictée** (`autoStartOnVoice`,
  activé par `IseeVoiceNav` ; défaut `false` = API de confirmation
  historique) : plus de bouton « Démarrer » dans le chemin vocal, et une
  seule annonce (le moteur, ou le repli Maps) au lieu de deux voix
  superposées. La confirmation reste l'API historique (`false`), utilisée
  par les tests.
- Repli Google Maps toujours **piéton** : `TravelMode.WALK` par défaut et
  explicite dans `NavigationHook` et `VoiceNavSession` ; `mode=w` verrouillé
  par les tests.
- Plus d'écran de guidage fantôme : quand le moteur ne démarre pas et que Maps
  prend le relais, la session revient à `Idle` au lieu d'afficher `Guiding`
  sans moteur. Le pilote exige aussi une écoute GPS réellement démarrée
  (`VoiceNavTracker.start` rend `false` sans fournisseur) avant de dire oui.
- Preuves : `:app:testDebugUnitTest` ciblé **46/46** (`NavigationLauncherTest`
  12, `NavigationHookTest` 6, `VoiceNavSessionTest` 20,
  `VoiceNavSessionWiringTest` 8), `:core:testDebugUnitTest`
  `VoiceNavScriptTest` **6/6**, `check-kotlin-limits.py` OK. Non mesuré sur
  appareil (aucun APK installé ici).

## 2026-10-28 — direct : 10 dangers extérieurs par défaut, plafond 16 (ordre user)

- `LivePrompts.default` : `person, car, bicycle, electric scooter, pole, sidewalk,
  crosswalk, wall, window, stairs` (remplace `person, door, bicycle, car, truck,
  electric scooter` ; `door`/`truck` restent saisissables dans « Suivre »).
- `LivePrompts.MAX` 6 → 16 + `tracking.py PROMPT_LIMIT` 6 → 16 ensemble (contrat
  client/serveur). Backend FR→EN déjà complet (213 alias : poteau, trottoir,
  passage piéton, mur, vitre, escalier…) — aucun alias ajouté.
- `KnownSizes.DEPTH_ONLY` : `stairs`/`sidewalk`/`crosswalk`/`pole` sans hauteur
  sténopé (plans au sol ou hauteur indéfendable) — distance par profondeur/plan
  de sol ; `StairConcepts` désormais câblé dans les défauts.
- Effet traversée : `VehicleConcepts.promptsFor` (5 véhicules + base) tient sous
  le plafond 16 au lieu de tronquer à 6.
- Preuves : `:core:test` + `:app:testDebugUnitTest` verts sur r630,
  `test_units.py` 247/247 (dont `test_tracking`), `check-kotlin-limits` OK.
  Test téléphone + déploiement tracker dus (PROMPT_LIMIT actif au prochain
  `deploy_backend.sh`).

## 2026-10-28 — suppression NanoDet (test escaliers SAM uniquement, ordre user)

- Supprimés : écran démo `app/.../detect/` (5 fichiers : écran, hôte caméra,
  préprocesseur, détecteur ORT, spec modèle), `core/.../detect/` (interface +
  décodage, 80 labels COCO), 3 tests (2 JVM + 1 instrumenté),
  `docs/NANODET-SAM31.md`, route `Route.NanoDet` + bouton Réglages + callbacks
  (`AppNav`, `SettingsScreen`, `SettingsClassicScreens`, `UiLogicTest` adaptés).
- Motif : carrés rouges NanoDet confondus avec SAM au test téléphone ; COCO
  sans classe escaliers → le test escaliers se fait en Direct/SAM uniquement.
  Release `detect-models-v1` conservée côté GitHub (restaurable par revert).
- Preuves : `grep -ri nanodet` = 0 occurrence, `:core` + `:app`
  `testDebugUnitTest` verts sur r630, `check-kotlin-limits` OK.

## 2026-10-28 — traversée : veto TTC sur boîtes SAM suivies

- `VehicleApproach` (`core/.../traverse/`) : association IoU inter-frames,
  `MotionModel` réutilisé, tau = largeur / vitesse < 3 s → veto, en OU avec
  le veto instantané (surface/distance) ; `TraverseDecision` porte le TTC.
- Preuves : `:core` + `:app` `testDebugUnitTest` verts sur r630
  (`VehicleApproachTest` 8/8, `TraverseVetoTest` 14/14 inchangés + 1),
  UBS 0 finding, `check-kotlin-limits` OK.
- Seuils (3 s, IoU 0,3, planchers) = priors à recaler sur téléphone ;
  test live derrière `sam3-eu-a100` encore dû.

## 2026-10-28 — traversée : porte anti-confusion sans données

- `PedLightGate` (`core/.../crossing/`) : le vert classifié ne passe que
  s'il est en bas du cadre (≥ 70 %), élancé (hauteur ≥ 1,15 × largeur),
  sans rouge franc (≤ 2 %) — feu de voiture rond, reflet et crop serré
  retombent en doute ; `crossingStateOf` l'applique (sonde + direct).
- Méthode sans données : priors structurels + 12 tests synthétiques JVM
  (`PedLightGateTest`) prouvant la sûreté (jamais de vert inventé), pas la
  justesse terrain — calibration FR toujours due.
- Preuves : `:core` + `:app` `testDebugUnitTest` verts sur r630, UBS 0
  finding nouveau, `check-kotlin-limits` OK.

## 2026-10-28 — traversée : le direct décide (PR #163-167, #174)

- Paquet `traverse/` : hystérésis (`TraverseState`), veto trafic
  (`TraverseVeto`, machine ATTENDRE/PRÊT/TRAVERSER/ALERTE), voix sûre
  (`TraverseVoice`, préemption urgente + clips hors-ligne), pipeline et
  câblage `LiveViewModel` ; le vert est lu par le classifieur embarqué
  (`TrainedLightColor`), seules les boîtes viennent de SAM via `/live`.
- Preuves : `:core` + `:app` `testDebugUnitTest` verts sur r630 (2 264
  tests), UBS 0 finding nouveau, `check-kotlin-limits` OK.
- Doc : `docs/TRAVERSEE.md` (bilan, dépendances, 4 restes bloquants avant
  usage réel : calibration FR, terrain, anti-confusion, rappel SAM).

## 2026-10-28 — haptique : la nappe de l'appui long est supprimée

- Décision user : aucune vibration pendant que l'utilisateur parle. L'appui long
  garde sa salve franche au seuil (`HapticPattern.holdTick`, deux temps forts,
  jouée une seule fois) puis silence pendant toute la dictée ; le relâchement
  garde sa queue de 200 ms, la traversée ses motifs ponctuels (inchangés).
- `core/.../haptics/Waveform.kt` : `holdLoop()` (bouclé) remplacé par `holdTick()`
  (non bouclé) ; consts `LOOP_MS`/`LOOP_OFF_MS`/`LIGHT_AMPLITUDE` et
  `FIRST_LOOP_INDEX` supprimées. `app/.../HapticLoop.kt` (`startHold`) et
  `app/.../IseeHaptics.kt` (KDoc) suivent ; `scripts/verify-longpress-port.sh`
  raconte le nouveau comportement (salve, silence, queue).
- Preuves : `WaveformTest` (`:core`, JVM) — salve historique intacte,
  `looped == false`, `repeatIndex == NO_REPEAT` ; `:core` + `:app`
  `testDebugUnitTest` verts sur r630.

## 2026-10-28 — concepts : murs, escaliers et vocabulaire extérieur FR→EN

- `server/backend/concepts.py` (nouveau, 269 lignes, copié à l'identique dans
  `live_fast/`) : table FR→EN passée de 48 à 213 entrées — murs, escaliers,
  barrières, rambardes, poteaux, trottoirs, passages piétons, feux, mobilier
  urbain, bâtiments, nature ; `segmentation.py` (les 2 copies) importe au lieu
  de porter la table (326 → 262 lignes).
- Recherche insensible aux accents : « barriere », « fenetre », « passage
  pieton » matchent ; l'inconnu est remis désaccentué au CLIP anglais.
- Taper « mur, escalier » dans le champ « Suivre » du direct fonctionne sans
  changer les défauts (`person`, `door`, décision 2 concepts max inchangée) ;
  `KnownSizes` couvrait déjà mur/wall (2,50 m) — la distance s'affiche.
- Preuves : `python3 scripts/test_units.py` → 243 OK, 0 KO (+8 : 6 alias, 2 sync
  racine/déployé) ; `cmp` des 2 copies OK ; aucun Kotlin touché.

## 2026-10-28 — docs : clean + README réécrit

- `README.md` réécrit : problème, solution, 4 blocs (`:core`, `:app`, `server/`,
  `suivi-parent/`), stack complète (Android, Modal, page proche, voix/LLM, CI) ; chiffres
  remesurés le 28/10 (733 `.kt`, 43 457 lignes de prod, 2 202 `@Test` JVM + 10 instrumentés).
- Recomptages 28/10 : `docs/PERF.md` (§1.1, §2, §4), `NOTES.md` (versions, taille, direct),
  `docs/ARCHITECTURE.md` (§1, §8), `docs/TESTING.md` (§1, §2, §5, §6), `AGENTS.md` (§1, §3, §4),
  `docs/DOC-ETAT.md` (+ `server/*`, `suivi-parent`, `scripts`, `DEPS`, `fireworks`).
- Backend présent : fausses affirmations corrigées (« pas de backend ici », double tap =
  photo, « Autres écrans », `PROMPT_LIMIT = 2`, `work_edge` 640, `CHANGELOG.md:269`).
- Datation (§0) : 14 fichiers purgés des dates < 26/10 (forme « relevé antérieur, hors de
  cet arbre ») ; specs historiques signalées (`KOTLIN-SPEC`, `LIVE-SPEC` serveur).
- Gardes rejouées : kotlin-limits, isee-design, budget-derive, voice-nav-doc, ci-budget
  (8 workflows), budget_latence (6 + 14/14), test_scripts (117), goldens (11/11), node
  suivi (10/10), unittest outils (58) — tout vert, aucun `gradlew` lancé.
## 2026-10-28 — gpu : l'app détecte quand la carte est branchée

- `GpuMonitor` (`core/net/GpuMonitor.kt`, testé) : présence observée (`Unknown` /
  `Checking` / `Up` / `Down`) alimentée par les vrais appels SAM (zéro requête ajoutée)
  et par une veille bornée (12 sondes `/health` × 10 s) qui ne sonde que sur `Down` et
  s'arrête au premier succès — jamais de sonde sur `Up` (l'extinction est préservée),
  sur `Unknown` (pas de réveil surprise) ni sans endpoint.
- `AnalyzeUseCase` expose l'issue SAM (`onSamOutcome`, `Timeout` si budget dépassé,
  `null` sans client) : la photo affiche « Détecteur SAM injoignable (…) » au lieu de
  « aucun objet » (`AppStatusText.samDownNote`, même motif que le direct).
- Réglages : pastille de présence dans la section GPU (`gpuStatusLine`, testée) ;
  la veille démarre à l'ouverture de la page et s'annule en partant (`setGpuWatching`).
- 22 tests JVM : `GpuMonitorTest` (11), `UseCasesTest` (+4), `AppAnalysisTest` (+3,
  1 attente mise à jour : la note nomme la GPU), `AppDiagnosticsTest` (+3),
  `GpuStatusLineTest` (1).

## 2026-10-28 — direct : 6 concepts suivis (rue + intérieur)

- Décision user : le direct suit aussi les vélos, voitures, camions et trottinettes
  électriques — `LivePrompts.default` passe à 6 concepts (`person`, `door`, `bicycle`,
  `car`, `truck`, `electric scooter`) et `MAX` 2 → 6 (`core/live/LivePrompts.kt:27,34`).
- `PROMPT_LIMIT` 2 → 6 côté tracker (`server/backend/tracking.py:53`) : sans ça le backend
  tronquerait les 6 concepts à 2. Coût ×3 assumé sur le chemin tracker (une inférence par
  concept et par `/frame`) ; sur `/live` les prompts sont batchés (un seul forward).
  Aucun workflow ne redéploie le tracker : effet au prochain déploiement manuel.
- `KnownSizes` calibre les nouveaux libellés (`electric scooter`, `scooter`,
  `trottinette electrique` → 1,00 m) ; la Traversée garde ses 2 prompts (feu + voitures).
- 4 tests JVM : `LivePromptsTest` (défauts + borne à 6), `LivePromptsNavigationTest`
  (pas de concept ajouté), `KnownSizesTest` (véhicules calibrés FR/EN). Docs alignées
  (`README`, `AGENTS.md`, `LIVE-SPEC`, `PERF`, `ARCHITECTURE`, `DISTANCES`, `DOC-ETAT`).

## 2026-10-28 — isee : la caméra active allume la carte, coupée elle s'éteint

- La porte d'envoi vers SAM devient `cameraActive || recordingVideo || crossingActive`
  (`sendingToSam`, `IseeVisionCard.kt`, pure et testée) : dès que la caméra est liée
  (permission + aperçu), les frames partent et la première réveille la carte ; avant,
  seul l'enregistrement (ou la Traversée) envoyait.
- `LiveProbe.stop` (nouveau `LiveProbeStop.kt`, la sonde étant au plafond des 500 lignes)
  ferme les deux sessions WebSocket et efface masques et chiffres : sans socket ouvert,
  le conteneur Modal retombe à zéro. Appelé à la coupure caméra (`setCameraActive(false)`),
  au passage en arrière-plan et à la destruction du ViewModel ; les sessions rouvrent au
  retour (`onForeground`) et à la liaison caméra. `configure` referme les anciennes
  sessions au lieu de les orpheliner ; `LiveSession.close` (révocation + `client.close`).
- 9 tests JVM : `LiveSessionCloseTest` (2), `LiveProbeStopTest` (3), `SendingGateTest` (4).
  Coût assumé (demande user) : chaque ouverture de la page caméra avec permission réveille
  la carte (~17-40 s de froid facturées), comme un enregistrement avant.

## 2026-10-28 — caregiver : bleu et vert, proportions et badge Portrait

- Roue dentée noire, état dans une pastille arrondie comme sur la page blind person.
- Carte plus haute et remontée, sans défilement ; boule plus petite au liquide bleu et vert.
- Couleurs du verre préservées dans le port WebGL ; le shader natif blind person reste inchangé.
- Build plafonné à deux workers Gradle sur les runners partagés, après un relevé
  à 80 workers ; processus Gradle distinct par build après des arrêts de démons
  réutilisés. Délais, caches et contrôles de CI conservés.

## 2026-10-28 — intro : voix Laure et fin de la phrase du balayage

## 2026-10-28 — narration Direct : Baseten partout, InferHub retiré

- `intro_isee.mp3` régénéré en voix Laure (`3b8fXc91YHS1i2DYAlBQ`, `eleven_v3`,
  réglages du runtime) : 401 701 octets, 25,08 s (`ffprobe`), voix réelle
  (mean −19,3 dB, max −0,4 dB, `volumedetect`), 135 caractères (creator,
  28 078 → 28 213 / 130 005). Texte `ISEE_INTRO_TEXT` mot pour mot (307 caractères).
- La phrase « To listen to previous messages, swipe down » n'est plus ni dans le
  texte ni dans le MP3 : le balayage entre messages était déjà supprimé du code
  (27/10), seule la voix le disait encore. Jade US reste proposée dans Réglages.
- Commentaires et docs réalignés (`IntroAudio`, `IntroLogic`, `Settings`,
  `SpeechLanguage`, `TtsSettings`, `ELEVENLABS-TTS.md`, `VOIX-ANGLAIS.md`).
- Le cache de l'intro s'invalide quand l'asset change (`introCacheStale`,
  comparaison des tailles) : sans ça, une mise à jour rejouerait l'ancienne
  voix jusqu'au vidage du cache par Android. 1 test JVM (`UiLogicTest`).
  Preuve d'écoute : à confirmer à l'oreille sur le téléphone (pas de sortie
  audio sur le poste de travail).
- La narration du Direct parle au même endpoint et au même modèle Meta que les
  questions (`https://inference.baseten.co/v1`, `meta-models/muse-spark-open-1026`,
  `Settings.DEFAULT_LIVE_LLM_*`) : un seul fournisseur LLM dans l'app.
- Le budget de 200 tokens reste suffisant : le client coupe déjà le raisonnement
  interne (`enable_thinking=false`), la phrase ne paie plus les pensées. Clé de
  narration toujours dédiée (`liveLlmApiKey`, désormais Baseten) ; sans elle, muet.
- `InferHubLlmClient` renommé `HttpLlmClient` (plus aucun chemin d'exécution ne
  touche InferHub) ; `LlmFactory.inferHub` devient `httpLlm`. La migration des
  réglages d'avant (`migrateLegacyLlm`, InferHub + qwen → build) est conservée.
- Preuves : `ModelPinTest` (lignes questions + direct citées séparément),
  `LlmClientTest`, `HttpLlmClientTest`, CI verte exigée au merge.

## 2026-10-28 — lunettes : l'enregistrement du banc ne bloque plus le main thread

- ANR du 27/10 (`executing service …ACDCRegistrationService`) : `requestRegistration`
  appelait `Wearables.startRegistration` en ligne dans le collecteur `registrationState`,
  sur le main, pendant que le SDK tenait son verrou d'enregistrement — interblocage
  AB-BA avec un thread binder (traces `data_app_anr` du 27/10 11:22).
- L'étape `REQUEST_REGISTRATION` quitte désormais le thread de l'émetteur
  (`runsOffEmitter`, `GlassesSessionPolicy`, verrouillé par 3 tests JVM) et l'appel
  bloquant part sur IO, comme tous les autres appels DAT du cycle.

## 2026-10-28 — micro : le démarrage verrouillé ne tue plus le processus

- Le FATAL `ForegroundServiceDidNotStartInTime` (2× au relevé du jour, démarrage à froid,
  livraison d'`onStartCommand` hors délai) est structurellement impossible : l'armement
  passe par `startService`, et le service prend lui-même le premier plan en millisecondes
  (ou s'arrête, arrêt parlé).
- La décision de démarrage vit dans `:core` (`LockedMicStartRunner`, pur, chaque effet
  gardé) : premier plan ou arrêt, sans jamais lever — y compris si la notification, la
  publication d'état, la voix ou le premier plan (`SecurityException` micro, Android 14+)
  lèvent. 15 tests JVM verrouillent les chemins, dont l'injection d'effets qui lèvent.
- Comportement produit inchangé : mêmes états parlés, mêmes notifications, `START_STICKY`
  quand armé, service toujours debout après retrait des récentes (HyperOS).

## 2026-10-28 — caregiver : écran réellement livré dans l'APK

- WebView explicitement en MATCH_PARENT : évite un viewport CSS de hauteur nulle
  et l'écran blanc constaté sur le Samsung, notamment après rotation.
- Suppression de Retour, du titre Caregiver et d'Actualiser ; état du flux en
  haut à droite, paramètres par la roue dentée.
- Interface embarquée dans l'APK : fond blanc, même boule Portrait en gris,
  carte réduite en bas sans défilement, aucun bloc hors ligne ni légende dessous.
- Clé Google Maps injectée au build, préremplie et active depuis l'installation,
  champ déplacé dans les paramètres, sans clé en clair versionnée.

## 2026-10-28 — GPU : la carte ne démarre qu'en filmant

- **Réveil à l'ouverture supprimé** : `MainActivity.onResume` n'appelle plus
  `warmGpu()` (3 `GET /health` silencieux qui démarraient la carte sans filmer) ;
  `AppViewModel.warmGpu()`, `AppDiagnostics.warmGpu()`, `warmGeneration`,
  `WARM_ATTEMPTS` / `WARM_RETRY_DELAY_MS` et leurs 6 tests supprimés.
- **Pré-chauffe du Direct supprimée** : `LiveViewModel.start()` n'appelle plus
  `prewarmSam()` à l'entrée de l'écran ; `SamPrewarm.kt` et son test supprimés.
  La première frame réveille la carte (`LiveWakeGuard`, « Réveil du GPU… », 60 s max).
- **Extinction inchangée** : `onPause`, split-screen, fermeture → `POST /release`.
- Preuve : `grep warmGpu|prewarmSam app/src core/src` → vide ; suites JVM `:core`
  + `:app` vertes (porte CI de la PR) ; `tools/check-kotlin-limits.py` vert.
- Docs : `docs/MODE-US.md` (fin du réveil à l'ouverture).

## 2026-10-27 — caregiver : Portrait gris et session distante

- Fond blanc, boule Portrait grise, phrase courante puis carte en bas, sur le
  site et dans la WebView Caregiver. Le shader Android gelé reste inchangé.
- Import du lien reçu depuis un autre téléphone ; aucune session choisie à
  partir de l'identifiant local du caregiver.
- iSee et Direct partagent le même relais pour les objets et la phrase. Le
  déplacement GPS est décrit seulement au-delà de son incertitude ; les
  repères se figent à la perte du signal et disparaissent après deux minutes.

## 2026-10-27 (15) — réglages en anglais : fond blanc, chevron, verre, THE USER / META GLASSES / TECHNICAL SETTINGS

- **Dire « settings » ouvre les Réglages** : `SettingsVoiceCommand` (pur, `:core`)
  reconnaît « settings », « open settings », « réglages », « paramètres » avant
  l'envoi au LLM ; `IseeViewModel` lève `IseeUiState.openSettings`, consommé par
  `IseeHome` qui pousse `Route.Settings`.
- **Page blanche, texte noir, tout en anglais** : palette locale `SettingsLight`,
  en-tête « Settings » + **chevron** dessiné au trait (plus de bouton « Retour »),
  boutons **verre** (`GlassButton` : fond translucide, bordure claire, ombre légère).
- **Trois sections** : THE USER (rôle Blind person / Caregiver + **lien ou code
  d'invitation** que l'aveugle envoie, bouton « Save & open caregiver map » désactivé
  tant que l'invitation est vide) ; META GLASSES (bloc lunettes sur carte sombre,
  ses textes testés en français inchangés) ; TECHNICAL SETTINGS (tout le reste,
  traduit). L'ancien titre « Réglages du backend » disparaît.
- **L'invitation ouvre le bon suivi** : `Settings.caregiverInvite` persisté dans les
  deux stores (parité `SettingsStoreParityTest` OK) ; `parseCaregiverInvite` accepte
  le code nu, « ?share= » ou le lien complet ; `caregiverShareLink` gagne sur les
  champs historiques pour la page Caregiver.
- **Vérifié** : `:app:testDebugUnitTest` vert (562 tests, dont `CaregiverShareLinkTest`)
  ; `:core:testDebugUnitTest` 1137 tests avec les 5 échecs connus
  `PrivateFileSettingsStoreTest` (préexistants, POSIX sur Windows), plus
  `MotionBenchTest` (budget µs/frame, machine lente — même flaque à froid déjà vu
  sans ce changement) et `LivePipelineTest` (passé au re-run isolé) ;
  `tools/check-kotlin-limits.py` OK ; `assembleDebug` vert.

## 2026-10-27 (14) — zéro mémoire des anciens messages + balayage supprimé

- **L'IA n'a plus aucune mémoire** : `IhearTranscript` perd `questionTurns()` /
  `turnsOf()` / `CHAT_HISTORY` ; `IhearAnswers` joint `emptyList()` à chaque question
  (`app/ihear/vm/IhearAnswers.kt`) — aucun tour n'est lu de la base, même en pleine
  session. `MediaCacheStore.history()` (« historique complet en mémoire ») supprimé.
  Le plafond `AskUseCase.MAX_HISTORY_TURNS` (20 tours) ne vaut plus que pour le Direct.
- **Les messages passés ne sont plus mémorisés en cache** : rien d'une session
  antérieure n'est restauré — table Room et `cacheDir/ihear` vidés à l'ouverture
  (`IhearViewModel.init`) et à la fermeture (`IhearSession.onClosed`), donc rien ne
  revient, ne s'affiche et ne repart vers le modèle.
- **Balayage entre messages supprimé** : machine `swipeStep`/`SwipeStep`,
  `firstSwipeDownDone`, `hasBubbleChoice`, `selectedEntryIndex` (`IhearState.kt`) ;
  callbacks `onVerticalDragStart/Move/End` (`IhearViewModel`, `IhearRootLayout`,
  `MainActivity`) ; rail vertical de `IhearGestureBrain` (le glissement verrouille
  toujours le geste — il ne devient jamais un tap — mais n'émet plus rien) ; haptique
  dédiée (`IhearHaptics.verticalSwipe*`, `HapticPattern.swipeCrest`) ; lecture ciblée
  (`IhearPlayback.playSelected`, `IhearIntro.replay`). Le message affiché est toujours
  le dernier (`IhearTranscript.refresh`).
- **UI** : pill d'instructions réduit à « Tap see / Hold ask » (`IseeStatusLine.kt`) ;
  intro sans la phrase de balayage (`IntroLogic.kt`, 351 → 307 caractères).
  ⚠️ `intro_ihear.mp3` annonce encore le swipe : à régénérer (procédure
  `docs/ELEVENLABS-TTS.md`).
- **Docs** : README (liste des gestes), `docs/ARCHITECTURE.md` (table des gestes +
  « historique borné à 20 tours » → historique vide), `docs/ELEVENLABS-TTS.md`,
  `docs/TESTING.md` (comptes de tests).
- **Tests** : `SwipeStepTest` (9) renommé `BubbleCountTest` (2) ; `IhearTranscriptTest`
  : les 2 tests d'historique deviennent « la sélection suit toujours la dernière
  bulle » ; `IhearAnswersTest` : « aucun historique — la question part seule, même si
  la base garde des messages » ; `UiLogicTest` : plus de « swipe down » dans l'intro ;
  `WaveformTest` −2 ; `IhearGestureBrainTest` sans callbacks verticaux.
- **Vérifié** (après rebase sur le master de 49 commits) :
  `:app:testDebugUnitTest` vert ; `:core:testDebugUnitTest` avec les 5 échecs
  `PrivateFileSettingsStoreTest` **retrouvés à l'identique sur un `origin/master`
  vierge** (permissions POSIX non supportées sur Windows) ;
  `tools/check-kotlin-limits.py` vert ; `assembleDebug` vert.

## 2026-10-27 (15) — film + parole : la photo est jointe, le double tap n'existe plus

- **Course capture/lecture réparée** (`IseePhotoShot`) : la lecture attend le rappel
  CameraX (borné, `CAPTURE_TIMEOUT_MS = 4 s`) et retente une capture si rien n'avait
  démarré (caméra froide refusant au début de la tenue). Avant : une tenue courte
  lisait un contexte encore vide et partait en texte seul — le modèle répondait
  « aucune image transmise » (vu dans `isee-media.db` sur le téléphone : une
  question avec image, la suivante sans).
- **Double tap supprimé** (décision produit) : détection retirée du cerveau des
  gestes (le tap simple part dès le relâchement, plus de fenêtre 450 ms),
  `onDoubleTap`, photo gelée/évaporation/pastilles, `photoPath`/`distanceLayer`
  isee et leurs branches d'affichage (carte + Portrait). L'appui long reste la
  seule prise de photo, silencieuse, jointe à la question. Onboarding et aide
  Réglages réduits aux deux gestes.
- **Preuves** : `IseePhotoShotTest` (10, dont attente du rappel et retentative),
  `IseeGestureBrainTest` (5), `IseeAnswersTest`, `OnboardingGesturesTest`,
  `IseeSceneTest` adaptés ; `:core:testDebugUnitTest` 1126/1126,
  `:app:testDebugUnitTest` 529/529 sur r630 (après rebase sur le master
  renommé `isee`) ; `check-kotlin-limits.py` OK. Recette téléphone (photo
  jointe à la question en filmant) après merge.

## 2026-10-27 (13) — cap vocal : le guidage marche vers la direction demandée

- **Ordre vocal de cap** (`SteeringCommand`, avant Maps et le LLM) : « va à gauche »,
  « tourne à droite », « tout droit » (+ anglais) ; une question (« qu'y a-t-il à
  gauche ? ») et une phrase ambiguë ne déclenchent pas. Confirmation parlée
  (`SteeringText`), qui dit aussi quand le guidage est coupé dans les Réglages.
- **Chemin calculé vers le cap** (`Guidance`, couloir = moitié visée, passage = plus
  libre des deux autres secteurs, centre en premier) : « Va à gauche. »,
  « Personne à 2 mètres à 10 heures, passe au centre. » L'obstacle se dit au cadran
  comme en cap droit ; cap droit inchangé au caractère près.
- **Flèche sur la vidéo** (`SteeringLayer`, verte voie libre, ambre + passage si
  bouché) ; chemin recalculé à chaque frame, voix débouncée comme avant ; cap de
  session (retombe à tout droit), coupé en mode Traversée.
- **Preuves** : `SteeringTest` (12), `GuidanceSteeringTest` (13),
  `GuidanceAnnouncerSteeringTest` (2), `LiveProbeSteeringTest` (5) ;
  `:core:testDebugUnitTest` 1125/1125, `:app:testDebugUnitTest` 556/556,
  `check-kotlin-limits.py` OK. Non vérifié sur téléphone (ni à l'oreille, ni la
  flèche à l'écran).

## 2026-10-27 (12) — premier plan = carte active : réveil à 3 essais, stop franc si backend arrêté

- **Activation fiable à l'ouverture** : `warmGpu()` fait jusqu'à 3 `GET /health`
  silencieux espacés de 2 s (`AppDiagnostics.kt`, `WARM_ATTEMPTS`,
  `WARM_RETRY_DELAY_MS`) — une ouverture dans un trou de réseau ne laisse plus la
  carte éteinte. Arrêt au premier succès, silence UI et sans `Log` inchangés.
- **Arrêt immédiat si backend stoppé** : sur `ApiError.BackendStopped` (404 Modal
  « invalid function call », app plus déployée), aucune nouvelle tentative —
  réessayer est inutile, seul un redéploiement (`deploy-live-fast.sh`) rallume.
- **Pas de rallumage derrière l'extinction** : `releaseGpu` périme la génération du
  réveil (`warmGeneration`) — passer en arrière-plan en pleine série d'essais
  arrête les essais suivants. Un nouveau réveil périme le précédent (pas de séries
  empilées après deux `onResume` rapprochés).
- **Rappel d'architecture** : le téléphone ne peut pas `modal deploy` ; l'état stable
  est « app déployée + conteneur à zéro » (0 $ au repos), le premier plan réveille,
  l'arrière-plan éteint (`POST /release` + `scaledown=300` en garde). Un `app stop`
  manuel casse le réveil jusqu'au prochain déploiement.
- **Preuves** : `AppDiagnosticsTest` +3 (succès au 2e essai, `BackendStopped` sans
  retentative, nouveau réveil annule le précédent), test d'échec passé à 3 essais ;
  `FakeSam.onHealthSeq` scripté. Sur téléphone : fond → `released:true` (logcat),
  aucun appelant SAM hors couche UI (suivi = positions seules).

## 2026-10-27 (11) — positions et consignes au cadran horaire (9 h … midi … 3 h)

- **Vocabulaire unique au cadran** (`PositionText.clockHour`/`clockLabel`, sept bandes
  égales) : « Personne à 9 heures, à 1,4 mètre. », « Personne à 2 mètres à midi, va
  à gauche. » (en : « at 9 o'clock » … « at 12 o'clock »). « à midi » pour 12 h,
  « à 1 heure » au singulier — la convention des déplacements sans voir.
- **Remplace les secteurs** « à gauche / au centre / à droite » et le repère vertical
  partout où l'app met des positions en mots : annonces (Direct, iSee, photo),
  journal caméra (`TimelineText`, mêmes mots dans les deux langues), consignes du
  couloir (heure de l'obstacle dans la phrase et dans la clé `b|label|côté|mètre|heure`).
- `horizontal()`, `vertical()`, `positionLabel()` supprimés (remplacés, sans autre
  usage) ; `SIDE_EDGE` gardé pour le seul couloir du guidage. Aide des Réglages et
  fiches `VOIX-POSITIONS`, `VOIX-ANGLAIS`, `GUIDAGE-TOUT-DROIT`, `ELEVENLABS-TTS`,
  `DISTANCES`, `MASQUES-DEBUG` alignées.
- **Preuves** : `PositionTextTest` (cadran, libellés), `PositionTextEnglishTest` (5),
  `PositionAnnouncerTest`, `GuidanceTest` (+1 : heure dans la clé), `GuidanceAnnouncerTest`,
  `TimelineTextTest`, `LiveProbeGuidanceTest` ; `:core:testDebugUnitTest` 1098/1098,
  `:app:testDebugUnitTest` 551/551, `check-kotlin-limits.py` OK, `:app:assembleDebug` OK.
  Non mesuré sur téléphone (phrases à l'oreille non vérifiées ici).

## 2026-10-27 (10) — voix Laure par défaut, vitesse ×1,9 réglable à la voix

- **Voix par défaut : Laure** (`3b8fXc91YHS1i2DYAlBQ`) au lieu de Sarah :
  `Settings.DEFAULT_TTS_VOICE_ID`, migration silencieuse des réglages restés sur
  Sarah (`migrateLegacyVoice`, comme le LLM : seul l'ancien défaut migre, un choix
  manuel n'est jamais touché). Les clips du mode tap sont régénérés (dossier par voix).
- **Vitesse `ttsSpeed`, ×1,9 par défaut (×0,5..×2)**, persistée dans les deux stores :
  boutons −/+ des Réglages **ou ordre vocal** sur iSee (« vitesse 1,5 », « parle plus
  vite », « ralentis », « faster », « vitesse normale »), avant l'envoi au LLM comme
  la coupe d'intro — « décris vite » ne déclenche pas. Confirmation dite à la
  nouvelle vitesse (ElevenLabs, affichée aussi en statut).
- **Vitesse appliquée en lecture locale** (`MediaPlayer.playbackParams`) :
  `eleven_v4_turbo` ignore le `speed` cloud (un non-défaut peut être rejeté), donc
  le client n'en envoie aucun — frais ou en cache, tout parle à la vitesse choisie,
  même hors ligne.
- **Preuves** : `TtsSpeedCommandTest` 11/11, `ElevenLabsTtsClientTest` +1 (absence de
  `speed` envoyée), `SettingsStoreParityTest` (vitesse non par défaut), `SettingsSeedTest`
  +3 (migration Sarah→voix du build/Laure, choix manuel intact), `TtsLogicTest` +3
  (Laure par défaut, crans, affichage) ; `:core:testDebugUnitTest` 1033/1033,
  `:app:testDebugUnitTest` 541/541, `check-kotlin-limits.py` OK, `assembleDebug` OK.
  Non mesuré sur téléphone (rendu ×1,9 à l'oreille non vérifié ici).

## 2026-10-27 (9) — retour de mission : la base médias se recrée au lieu de crasher

- **Crash au lancement sur le téléphone** (`IllegalStateException: A migration from
  2 to 1 was required but not found`, vu sur `2c6852ac`) : l'appareil portait une
  base `ihear-media.db` v2 (build mission) et master est en v1 sans stratégie de
  migration. `MediaDatabase.build()` déclare désormais
  `fallbackToDestructiveMigrationOnDowngrade()` : en downgrade la base est recréée
  vide, l'app démarre. Perte acceptée : le ViewModel purge cette table à chaque
  démarrage (`IhearViewModel.kt:141`) ; les montées de version sans migration
  restent des crashs volontaires.
- **Non-régression instrumentée** (`MediaDowngradeTest`, exige l'appareil, la CI ne
  l'exécute pas) : base v1 tamponnée en v2 + une ligne, ouverture via `build()`,
  table vide sans crash. Ne touche jamais la vraie base (fichier de test effacé).
- **Preuves** : suites JVM `:core` + `:app` vertes, `assembleDebug` vert,
  `check-kotlin-limits.py` vert, test instrumenté vert sur `2c6852ac`, lancement
  `master` sans crash vérifié sur l'appareil.

## 2026-10-27 (9 bis) — le Direct `/live` passe en cadence continue (pompe)

- **`LivePump` branchée sur le Direct** (u01, jusque-là écrite mais non appelée) :
  nouveau `app/.../live/LiveFrameDispatcher.kt` — session `/live` en cadence continue
  (moitié de la latence, 33-900 ms) avec une frame de réserve renvoyée sans attendre
  à la réponse ; tracker inchangé (cadence adaptative, pas de réserve). Le tracker
  restant le transport par défaut, le gain ne s'applique qu'au chemin `/live` (bandeau).
- **`LiveViewModel` : 493 lignes** (plafond 500 ; 494 sur `master`) : le cycle
  offre/envoi/drain est extrait (`onFrame` délègue, l'envoi déménage dans le
  dispatcher, `handleResult` garde l'époque + la publication, zéro duplication).
- **Contrat serveur inchangé** : toujours une seule frame en vol (drain refusé si le
  créneau est pris), époque vérifiée avant publication comme avant.
- **Preuves** : `LiveFrameDispatcherTest` 7/7 (réserve drainée sans attendre, tick
  continu, tracker adaptatif, séance close = pas de drain, reset) ;
  `:core` + `:app` 1678/1678, `check-kotlin-limits.py` OK. (Le `send` WS hors
  verrou, d'abord porté par cette branche, a été mergé par #79 : cette branche
  n'en garde aucune ligne.)
- **A/B émulateur** (GPU A100, 60 s chacun, 0 FATAL) : témoin 399a512 = 2,19 ips
  (logcat) / 177-188 ms ; branche 7cd1a4b = 5,53 ips / 158-166 ms — débit ×2,5,
  latence en baisse (~-12 %).
- **Téléphone Redmi 2c6852ac** (build 05389ff, 2×60 s, GPU A100) : 6,1-6,8 ips
  écran, 153-166 ms, srv 91 ms, 0 FATAL — le créneau unique est saturé
  (6,5 × 155 ms ≈ 1,0) : le client ne peut plus attendre moins, seuls un RTT
  plus court (serveur/réseau) ou N vols (serveur) monteraient au-delà de 7.

## 2026-10-27 (8) — la GPU se réveille à l'ouverture, s'éteint hors plein écran

- **Ouverture → réveil** : `MainActivity.onResume` appelle `AppViewModel.warmGpu()` →
  `AppDiagnostics.warmGpu()` = un `GET /health` **silencieux** (best-effort, aucun
  verdict, aucune écriture UI) sur l'endpoint actif (région EU/US). Carte endormie :
  réveil via le snapshot Modal ; carte chaude : requête quasi gratuite.
- **Hors plein écran → extinction** (`POST /release`, existant, étendu) : `onPause`
  (déjà là : home, changement d'app, écran éteint) + nouveau
  `onMultiWindowModeChanged` (split-screen) + `onDestroy` (fermeture). Au retour en
  plein écran, `onResume` (ou la première frame) réveille.
- **Limite connue** : le réveil ne **déploie** rien — si l'app Modal n'est pas
  déployée, `/health` répond `BackendStopped` et il faut un `sam3-gpu on` manuel,
  une fois (`docs/MODE-US.md` § « Déploiement manuel, réveil auto »).
- **Preuves** : `AppDiagnosticsTest` +3 (`warmGpu` : appel unique, silence UI,
  sans URL = aucun appel, échec = silence) ; `:core` 955 + `:app` 516, 0 échec ;
  `assembleDebug` et `check-kotlin-limits.py` verts.

## 2026-10-27 (7) — la question porte sur ce qu'on filme, et l'échec se voit

- **Cause trouvée (10 agents, rapports `isee-diag-2026-10-27/`)** : le « gel » d'une photo du
  double tap n'avait **aucune fin de vie**. Il n'était levé que par `IhearAnswers.releaseFreeze()`
  sur le chemin nominal — jamais à l'évaporation, jamais au démarrage d'une vidéo, jamais sur une
  sortie précoce (clé LLM absente, texte vide, question déjà en vol). Conséquence : dès qu'une
  photo avait été prise, `captureForHold` devenait un no-op **à vie**, et toute question partait
  avec cette photo morte, y compris pendant un enregistrement vidéo.
- **Fin de vie explicite** : `IhearPhotoContext.release()` (gel + fichier à zéro) et
  `IhearPhotoShot.releaseContext()`, appelés au démarrage de la vidéo, à l'évaporation de la photo
  (sauf tenue/transcription/réflexion en cours), sur chaque sortie précoce du ViewModel et dans le
  `finally` de `IhearAnswers.answer`. Un gel dont le fichier a été purgé est reconnu mort :
  `captureForHold` le relâche et reprend la frame filmée.
- **Image inutilisable** : `IhearPhotoShot.imageBase64()` devient `suspend` et rend `null` (jamais
  `""`), sur `Dispatchers.IO` (injectable : les tests restent sur le dispatcheur virtuel). Une
  image vide partait en `data:image/jpeg;base64,` → 400 du backend → **aucune réponse, aucun
  message**. `InferHubLlmClient` envoie désormais la question **en texte seul** quand il n'y a pas
  d'image (une seule fabrique `userMessage` pour les trois appels).
- **Réponse jamais muette** : `IhearAnswers` prend une seule question à la fois (`AtomicBoolean`
  posé avant toute suspension — deux tenues rapprochées donnaient 409 sur la seconde), enveloppe
  `ask` **et** `spoken` dans un `Result`, et relâche le contexte dans un `finally`.
- **L'échec s'affiche** : `IhearUiState.status` était écrit de partout et **lu par aucun
  composable**. Nouveau `IseeStatusLine.kt` (`statusLineOf` pure + `SceneBottomBar`) : la ligne de
  statut est posée au-dessus de la pill d'instructions, dans toutes les scènes, et affiche
  « Thinking… » pendant la réflexion.
- **On voit ce qu'on filme** : `iseeSceneLayers` ne dépend plus de la scène mais de
  `captureLive` — la carte caméra reste à l'écran pendant la tenue du micro. La réflexion passe
  devant l'enregistrement dans `iseeSceneOf` (la pill passe en THINKING sans cacher la carte), et
  la dissolution ne part plus tant qu'une capture est vivante.
- **Tests** : 1 451 JVM verts (`:core` + `:app`), 0 échec ; `check-kotlin-limits.py` OK. Nouveaux
  cas : gel → vidéo → frame filmée ; gel au fichier purgé ; évaporation pendant une tenue ; gel
  relâché sur succès/échec/clé absente ; deux `answer()` concurrents → un seul appel ; corps HTTP
  sans `image_url` quand l'image est vide ; ligne de statut et carte visible sous tenue.

## 2026-10-27 (6) — la caméra iSee guide tout droit, sans LLM

- **La page caméra iSee dit la consigne de marche** (« Continue tout droit. », « Personne
  à 2 mètres devant, va à gauche. ») au lieu de la position des objets : couloir central
  0,34–0,66, portée 4 m, côté de sortie le moins encombré, distance inconnue = prudence
  (`core/.../live/Guidance.kt`, pur, aucun import réseau — le guidage ne peut pas appeler
  le LLM). Le déclencheur (`GuidanceAnnouncer.kt`) garde stabilité 2 frames et non-répétition,
  mais un obstacle qui entre dans le couloir parle aussitôt, sans attendre les 4 s.
- **Branchement** : `LiveProbe.phraseOf` préfère la consigne à l'annonce quand le réglage
  est actif (`ProbeWiring.configureFrom`, `Settings.guideStraight`), la voix reste
  `ProximityVoice` → ElevenLabs. Le Direct et l'analyse photo gardent les positions.
  Réglage `ttsGuidanceStraight` (défaut `true`, persisté par les deux stores),
  bascule « Guider tout droit (caméra iSee) » dans `TtsSettings.kt`.
- **Preuves** : 24 tests JVM nouveaux (`GuidanceTest` 10, `GuidanceAnnouncerTest` 7,
  `SettingsGuidanceTest` 4, `LiveProbeGuidanceTest` 3) ; suites complètes `:core` 943 et
  `:app` 479, 0 échec le 27/10 ; `tools/check-kotlin-limits.py` vert (extraction
  `LiveProbe.openSession`, qui supprime aussi la duplication des deux constructions de
  session) ; APK debug construit. Contrat et recette : `docs/GUIDAGE-TOUT-DROIT.md`.
- **Non mesuré** : sur appareil (exige téléphone + GPU SAM + clé ElevenLabs).

## 2026-10-27 (5) — une seule app : le banc lunettes entre dans iSee

- **Le module `:glasses-spike` est supprimé et son banc d'essai lunettes vit désormais dans
  `:app`**, paquet `dev.sam3vision.app.glasses.bench`, ouvert depuis l'écran Lunettes
  (`Route.Bench`, `AppNav.kt:45`, `:95`) : 30 `.kt` / 3 488 lignes copiés depuis
  `glasses-spike/src/{main,debug,release,test,androidTestDebug}` vers les source sets
  correspondants de `:app`, paquet renommé, assets compris (`mock_feed.mp4`, 54 964 o).
  Raison : un seul paquet déclaré dans la console Meta Wearables (`dev.sam3vision.app`), donc
  une seule app installée et plus de confusion entre « l'app iSee » et « l'app du banc » — le
  banc reste un banc, il n'est pas une seconde voie de production. Décision, tableau de
  migration vérifié et conséquences : `docs/GLASSES-DECISION.md`.
- **CI** : plus de job `spike` — `.github/workflows/ci.yml` ne contient plus aucune occurrence
  de `glasses-spike` et garde **quatre** jobs de 5 min (`ci`, `apk`, `apk-release`, `lint`) ; le
  banc est compilé et testé avec `:app`. Le module coûtait **50 s** de job, mesurées le 26/10/2026 (relevé conservé dans
  `docs/glasses-spike-ci.md` §1).
- **Docs** : `docs/GLASSES-DECISION.md` réécrit (la décision du 26/10 « `:glasses-spike` reste
  un module séparé » est remplacée), `docs/glasses-dat-spike.md` et `docs/glasses-spike-ci.md`
  réécrits pour décrire le paquet `bench/` (noms de fichiers conservés pour les liens),
  `docs/META-GLASSES.md` §10 et §5 alignés, `README.md` et `NOTES.md` recomptés le 27/10.
  Les entrées **antérieures** de ce journal gardent `:glasses-spike` : c'était le nom du module
  aux dates où elles ont été écrites.
- **Non mesuré** : aucun `gradlew` lancé ici (l'intégration est faite par l'orchestrateur) ; le
  banc n'a pas été exécuté sur des lunettes, ; `ChaineCompleteTest` embarque son JPEG 64×64 en base64, faute d'asset dans `app/src/test/resources/`.

## 2026-10-27 (3) — le build du téléphone rattrape un cache KSP corrompu

- **Constat** : vers 11 h, le cron `vision-phone-latest` échouait **en boucle** sur
  `[ksp] Storage corrupted …/app/build/kspCaches/debug/symbolLookups/id-to-file.tab_i`, puis sur
  `Storage … file-to-id.tab is already registered`. Le téléphone est resté ~15 min sur un `master`
  périmé, **sans alerte** — la panne la plus invisible possible pour « le banc suit master ».
- **Correctif** : `scripts/install-phone-latest.sh` fait un **second essai** quand le log du build
  contient l'un de ces deux messages, via `./gradlew --no-daemon clean assembleDebug --offline` :
  `clean` est exécuté par le démon lui-même (son registre de caches reste cohérent) et
  `--no-daemon` évite de tuer celui des autres agents qui compilent en parallèle. Un seul essai
  supplémentaire, jamais de boucle ; le JDK est retrouvé comme dans `scripts/build.sh`.
- **Preuves** : `bash scripts/test_scripts.sh` → **80 assertions, 80 ok** (dont les gardes
  statiques : aucun `rm -rf` brut, `set -euo pipefail`, zéro chemin `/home/` en dur) ; `bash -n` OK ;
  `python3 tools/check-kotlin-limits.py` OK.
- **Second correctif, trouvé au test** : l'essai de secours appelait Gradle en direct, donc **sans**
  `ANDROID_HOME` — `scripts/build.sh` le pose pour lui-même, pas pour son appelant. En cron (aucune
  variable d'environnement) le secours serait mort sur « SDK location not found ». Le défaut
  `$HOME/Android/Sdk` est maintenant reposé dans l'appel de secours ; mesuré ensuite :
  `./gradlew --no-daemon clean assembleDebug --offline` → **BUILD SUCCESSFUL in 37 s**, APK 76,7 Mo.

## 2026-10-27 (4) — le micro répond écran verrouillé

- **La panne** : `MainActivity.onPause()` annule la tenue du doigt et Android 11+ coupe de toute
  façon le micro d'une app en arrière-plan ; l'app n'avait **aucun** service de premier plan.
  Écran verrouillé, « activer le micro » ne pouvait donc rien donner.
- **Le service** (`app/locked/LockedMicService.kt`) : premier plan type `microphone`, armé par
  l'activité **visible** (`onResume` — seul démarrage autorisé depuis Android 12), piloté ensuite
  par l'action de la notification (visibilité publique) ou la tuile des réglages rapides. Réveil
  partiel pendant la capture seulement ; plafond de 20 s, faute de relâchement possible.
- **La réponse** : `LockedMicAnswer` enchaîne PCM → transcription (Meta, repli Scribe) → question
  **sans image** → voix ElevenLabs. `LlmClient.askText` + `LlmPrompts.askVoiceSystem` disent au
  modèle qu'il n'a aucune image : il l'annonce au lieu de décrire une photo absente.
- **Les règles** : `core/speech/LockedMic.kt` (6 phases, plafond, textes affichés et prononcés),
  11 tests ; `LockedMicAnswerTest` 5 ; `LlmContractTest` +2 (aucune image dans le corps, échec
  explicite d'un client qui ne sait pas répondre sans photo).
- **Permissions** : `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`,
  `WAKE_LOCK` ; `POST_NOTIFICATIONS` entre dans la demande groupée du premier lancement.
- **Doc** : `docs/MICRO-VERROUILLE.md` (séquence légale, recette téléphone, limites assumées).

## 2026-10-27 (2) — le téléphone suit `master` tout seul

- **`scripts/install-phone-latest.sh`** : le téléphone de test ne peut plus rester sur une build
  périmée. Le script lit le `versionName` installé (`1.0.<commits>-<sha>`), le compare au `master`
  de GitHub, reconstruit dans un **miroir dédié** (`~/vision-phone/master`, `git worktree` — jamais
  l'arbre de travail partagé où les agents écrivent) et installe sous le verrou `~/bin/phone`.
  `adb install -r -d` : un `versionCode` plus ancien (build de mission) est accepté, les données de
  l'app restent. Il **ne prend jamais** le verrou d'un autre agent : occupé = sortie `2`.
- **Options** : `--check` (0 = à jour / 3 = en retard / 2 = occupé), `--reinstall`, `--wait S`,
  `--init` (crée le miroir), `--hold 30m` (fige le téléphone pour tester une branche de mission).
- **Cron du poste** : `*/10 * * * * ~/bin/vision-phone-latest --quiet` ; ce wrapper exécute la
  copie du **miroir**, donc une correction du script arrive par `master` sans toucher au poste.
- **Tests** : `scripts/test_scripts.sh` passe de 53 à **75** assertions (le nouveau script est dans
  `SCRIPTS` : syntaxe, `--help`, argument inconnu, `< 500` lignes, zéro `/home/` en dur) et gagne
  quatre cas dédiés (miroir absent, `--init` conseillé, `--hold` qui écrit la pause, pause
  respectée).
- **Preuves** : téléphone `2c6852ac` réinstallé `1.0.36-e2f6984` puis `1.0.39-8f95191` par le script
  lui-même, `state.env` à jour, `bash scripts/test_scripts.sh` 75/75.

## 2026-10-27 — carte caméra : le voile de pixels s'efface

- **La carte caméra (scène VISION) n'est plus un damier permanent.** La mosaïque restait opaque
  pendant tout l'enregistrement — 26 × 16 = 416 cellules d'environ 39 × 32 px — si bien que la
  carte n'était que de gros blocs et que l'aperçu CameraX comme les masques SAM, dessinés dans la
  même surface native, étaient cachés dessous. Le paramètre `fill` (0,42 au repos / 1 en
  enregistrement) ne pilotait aucune opacité : il ne servait que de drapeau « dessine ou pas ».
- **Le voile est désormais une entrée** : `PixelMosaic` prend une opacité (`alpha`) appliquée à
  chaque cellule ; `VisionCard` la pose à 1 puis l'efface en `PIXEL_VEIL_MS` = **1 100 ms**
  (`rememberPixelVeil`). Sur une photo du double tap, voile à 0 comme avant. La dissolution vers la
  bulle (`DissolveOverlay`, plan 26 × 16) est **inchangée**.
- **Preuves** : PR **#33** (`app/…/ihear/IseeVisionCard.kt`, `IseeScenePixels.kt`),
  `tools/check-kotlin-limits.py` OK, `:app:compileDebugKotlin` et `:app:testDebugUnitTest`
  (`--offline`) OK, checks `ci` + `ci-budget` + `couche0` verts, APK debug installé sur `2c6852ac`.
- **Non mesuré** : le rendu à l'œil pendant l'enregistrement — l'objectif du téléphone de test ne
  renvoyait que du noir au moment de la vérification (aperçu et photo noirs, masques absents).

## 2026-10-26 — `master` protégé, file de merge native GitHub

- **Ruleset GitHub sur `master`** : PR obligatoire, checks requis `ci` + `ci-budget` + `couche0`
  (base à jour), historique linéaire, force-push et suppression interdits.
- **Merge queue native** : l'auto-merge d'une PR l'embarque dans la file GitHub ; le groupe de merge
  (`gh-readonly-queue/master/pr-<n>-<sha>`) rejoue les checks requis, `couche0` compris.
- **Plus de Mergify** : `.mergify.yml` supprimé, le step « brouillon de file » retiré de `couche0`,
  les docs (`AGENTS.md`, `docs/CI-QUEUE.md`, `docs/DOC-ETAT.md`, `docs/glasses-spike-ci.md`) décrivent
  le flot GitHub natif. Aucun outil de file externe sur ce dépôt.

## 2026-10-26 (26) — un keyframe SAM complet n'est redemandé que si les masques ont décroché

- **Le scheduler manquant de la lane L13** : `core/.../keyframe/` — `KeyframeTypes` (signal
  mesuré, décision, 6 `Trigger`, 5 `HoldReason`, `isUrgent`), `KeyframeConfig` (plancher 200 ms,
  plafond 500 ms étirable à 1 000 ms, erreur 0,12 sur 2 frames, EMA 0,40, scène 0,35, 3 req/s
  rafale 2, repli 0,5→8 s, timeout 12 s, validation au montage), `RequestBudget` (seau à jetons
  + **jeton d'urgence** à découvert, remboursé par la reconstitution), `KeyframeScheduler` (la
  machine à états, `FrameGate` de `:core/live` réutilisé pour **un seul** keyframe en vol,
  `onKeyframeResult` idempotent) et `KeyframeStats` (compteurs + p95 via `LiveStats`, fenêtre
  distincte de celle de la session).
- **37 tests** : 21 sur le scheduler (démarrage, antiblocage 2 frames, coupure de scène
  prioritaire, masque perdu, plafond, veille statique off par défaut, budget, jeton d'urgence
  unique, repli, timeout 12 s, réponses fantômes, `reset`, estimateur `NaN`), 7 sur le seau,
  9 sur le banc `8 scénarios × 4 politiques` — **0 violation partout** (budget d'une seconde, un
  seul keyframe en vol, plafond d'âge dû). Les politiques « cadence fixe » sont le même
  scheduler, aveuglé (seuils inatteignables, signal neutre) : aucune politique dupliquée.
- **Chiffres calculés, pas mesurés** : horloge virtuelle 33 ms, A/R 150 ms / 1 200 ms, modèle
  d'erreur synthétique ; tableau dans `core/build/keyframe-sim-u12.txt` (répertoire de build,
  non versionné). Aucun téléphone, aucun GPU, aucun appel réseau.
- **Contrat** : `docs/KEYFRAME-SCHEDULER.md` (entrées u10/u11, décision, garde-fous, câblage u13,
  ce qui reste à recaler avec l'erreur réelle d'u11).
- `:app` non touché : le scheduler est une brique de décision, u13 l'intégrera.

## 2026-10-26 (25) — lunettes : le mode réel dit ce qui manque

- **`glasses-spike`, démarrage sur de vraies lunettes** : le journal du mode « Lunettes » énumère les
  gestes à faire (Developer Mode, enregistrement, paire appairée, caméra *Always allow*) et décrit
  chaque paire que le SDK voit (type, lien, batterie, portée, charnière, compatibilité). Décision
  pure `realStartAdvice` (`GlassesSessionPolicy.kt`), lecture du SDK `GlassesRealCheck.kt`, bouton
  **Diag** pour rejouer le diagnostic sans ouvrir de session (`MainActivity.kt`). Détail :
  `docs/GLASSES-ETAT.md`, dernière section.
- **Une paire simulée ne peut plus passer pour une vraie** : `MockGlassesHarness.dropSimulatedPairs`
  retire les appareils simulés laissés par un run précédent (le SDK s'en souvient après un process
  tué, et `AutoDeviceSelector` les aurait pris) ; le mode réel l'appelle avant d'ouvrir la session.
- **`mockdevice` hors de l'APK public** : `debugImplementation` + `MockControl` (`src/debug` /
  `src/release`). Mesuré : `MockDeviceKit` → **29** occurrences dans l'APK debug, **0** dans le release.
- **La sortie audio se lit à l'écran** : `GlassesVoice.routeToGlasses()` rend la ligne du journal
  (« voix : Bluetooth « <nom de la sortie> » (type …) » ou « voix : haut-parleur — aucune sortie Bluetooth connectée »).
- **Vérifs** : `:glasses-spike:testDebugUnitTest` **62** tests / **0** échec (6 neufs), `assembleDebug`,
  `assembleRelease`, `assembleDebugAndroidTest`, `tools/check-kotlin-limits.py` OK.
- **Non mesuré** : tout le matériel (appairage, firmware, latence et débit Bluetooth, optique, routage
  audio réellement entendu) — aucune paire de Ray-Ban Meta n'est branchée sur le téléphone de test.

## 2026-10-26 (24) — banc : les six cas instrumentés de la purge

- **`CaptureCleanupTest` : 3 → 6 `@Test`.** Les trois cas manquants du contrat de la lane L14
  sont écrits, sur de vrais fichiers du stockage privé (`CapturePaths.privateRoots`) :
  capture de l'app supprimée · fichier déposé hors périmètre (`cacheDir`) intact · capture
  en cours d'écriture (mtime < 60 s) reportée. Les trois déjà présents (trace MediaStore
  dé-synchronisée, périmètre privé seulement, job unique sans réseau) sont inchangés.
- Ce que les documents annonçaient déjà est donc vrai : `docs/NETTOYAGE.md` et
  `docs/TESTING.md` comptaient **6 méthodes `@Test`** instrumentées avant le code.
  **Exécution non mesurée ici** : ces tests demandent un appareil
  (`./gradlew :app:connectedDebugAndroidTest`), aucun émulateur n'a été construit.

## 2026-10-26 (23) — docs : ce qui est vrai aujourd'hui

- **README corrigé** contre cet arbre. Trois affirmations fausses retirées : le dossier
  `backend/` (il n'existe pas ici — le backend SAM 3.1 est dans un dépôt séparé), `:core`
  présenté comme « Kotlin/JVM pur » (c'est une **bibliothèque Android** : 4 de ses 71 fichiers
  touchent `android.*`), et `minSdk 26` appliqué à toute l'app (**29** pour `:app` et
  `:glasses-spike`, 26 seulement pour `:core`). La CI n'est plus décrite comme « sans aucun
  secret » : le job `ci` lit des secrets **facultatifs** (`LLM_API_KEY`, …) pour embarquer des
  clés dans l'APK qu'il publie ; sans eux, l'APK sort sans clé.
- **`docs/ARCHITECTURE.md`** (197 lignes) : les trois modules, le flux capture → segmentation →
  LLM → voix, le **contrat HTTP réel** lu dans le code (`/health`, `/segment`, `/live`,
  `/live/ws`, `/release`, `/video`, `/chat/completions`, ElevenLabs, Meta ASR — **pas de
  `/ask`**, qui n'existe nulle part ici), les **chemins** des clés (aucune valeur), et la carte
  des fichiers d'entrée.
- **`docs/PERF.md`** : trois catégories séparées — mesuré dans cet arbre (282 fichiers `.kt`,
  29 803 lignes, 627 `@Test` JVM + 10 spike), constantes lues dans le code (délais, cadences,
  plafonds), relevés sur appareil **datés et sourcés**. Les latences du backend restent hors
  dépôt ; les chiffres de vision serveur ne sont pas repris faute de source ici.
- **`docs/DOC-ETAT.md`** : une ligne par document — à jour, périmée ou inexacte. Corrigés dans
  la même passe : `docs/TESTING.md` (comptages de tests **359/109 → 468/159**, JDK non épinglé,
  `/health` n'est plus une sonde, budget iSee **150 ms et un créneau**), `docs/MASQUES-DEBUG.md`
  (même cadence, frame **320 px** et non 448, ligne `gpu :` plus alimentée), `docs/CI-QUEUE.md`
  (cinq jobs, runners `ubuntu-latest`, budget 5 min par job),
  `docs/DISTANCES.md` (« les huit concepts par défaut » → **deux**), `NOTES.md` (comptages,
  `DistanceMeter` n'est pas un fichier).
- **Constat, pas corrigé (hors périmètre docs)** : `LivePrompts.kt` annonce « huit » concepts en
  commentaire alors que `Default` en contient **deux** et que `MAX = 2` ; `LiveProbeState.gpu`
  n'est plus renseigné depuis la suppression des sondes `/health` ; `SECURITY.md` et `AGENTS.md`
  portent encore des phrases de l'ancien dépôt (détaillé dans `docs/DOC-ETAT.md`).

## 2026-10-26 (22) — la carte caméra se dissout et son monde reste sur la bulle

- **Demande user** : « l'animation des pixels qui remplissent la bulle tu dois énormément la
  travailler » — feindre la vidéo de design : la carte caméra se dissout pendant que ses
  pixels plongent dans la grande bulle qui se reconstitue en monde.
- **Plan pur testé** `core/.../Dissolve.kt` + `DissolveTest.kt` : morph de la bulle (grandit
  de son anneau jusqu'au centre), damier de désagrégation de la carte, cubique bézier du vol
  de chaque pixel, spots du « monde » posés sur le disque (la carte se remappe sur la bulle).
- **App** : `IseeDissolve.kt` (carte gelée, overlay damier + gerbe, `WorldMemory` persistant
  lié à la réponse), `IseeVisionCard.kt` (carte caméra extraite de la fenêtre), satellites
  et ticks réavailés, découpages pour la garde de tailles.

## 2026-10-26 (21) — plus aucune sonde `/health` : la première frame réveille la carte

- **Demande user** : « ok supprime les /health » (après le constat : `/health` toutes les ~20 s
  tenait une A100 allumée en continu, 2,415 $/h, sans une seule analyse).
- **Supprimé côté client** (il ne reste que le bouton « Tester SAM » des Réglages) :
  - `AppViewModel.prewarmGpu()` — le réveil **préventif** à l'ouverture de l'app et à l'affichage
    de l'écran caméra (`MainActivity.kt:63,127`, `AppNav.kt:116`) : c'était la source principale,
    une sonde toutes les 120 s tant que l'app était au premier plan ;
  - `LiveViewModel.wakeGpu()` — une sonde au démarrage du direct ;
  - `ihear/LiveProbe.wake()` — une sonde à la saisie de l'endpoint et au début d'enregistrement ;
  - `core/live/PrewarmGate.kt` (`shouldPrewarm`) et son test, devenus inutiles.
- **Le réveil passe par la première frame** : Modal met la requête en file pendant le démarrage
  (mesuré 8-35 s), donc aucune sonde n'est nécessaire. `LiveViewModel` n'attend plus un « warm »
  pour envoyer les frames ; l'état `starting` (« Réveil du GPU A100 40 Go sur Modal… ») se lève à
  la **première frame publiée**, et une frame en échec reste une ligne « réveil » pendant 60 s
  (`WAKE_TIMEOUT_MS`) avant de devenir une erreur. `LiveProbe` garde son état `warming` (même
  message dans la pastille) mais il est posé localement, sans requête.
- **Conséquence d'affichage** : le nom du GPU venait de `/health` → `formatLiveStatus` n'affiche
  plus « GPU ? » quand il est inconnu (`LiveState.kt`, test mis à jour) ; `shortGpu()` retiré.
- `releaseGpu()` (POST `/release` à la fermeture) est **conservé** : c'est l'inverse d'une sonde,
  il éteint la carte.

## 2026-10-26 (20) — distances sur la vidéo : la pastille devient du verre, reliée à sa boîte

- **Demande user** : « je trouve que c'est assez moche la manière dont les mètres distances sont
  affichées sur la vidéo sur la caméra, et je me demandais s'il serait possible de les afficher
  autrement ? », puis « teste ce que tu proposes ».
- **Plus de rectangle plein sur l'image** (`app/DistanceOverlay.kt`, `app/DistanceLayout.kt`) : la
  pastille devient du **verre sombre** — le texte blanc reste lisible sur n'importe quelle image — avec
  une **puce** et un **liseré** de la couleur du masque. Elle se pose au-dessus du **coin haut-gauche**
  de sa boîte, et un **fil de rappel** de la même couleur la relie à un point posé sur ce coin. La
  couleur ne remplit plus une vignette : elle **désigne** l'objet. C'est le fil qui lève l'ambiguïté que
  les pastilles centrées laissaient quand deux objets se touchent — et qui garde attachée à sa boîte une
  pastille que l'empilement a poussée vers le bas.
- **Placement réécrit, pur et testé** : `placePills` remplace le couple « centré + `spreadLabels` ».
  Alignement sur le coin de la boîte, bornage dans le cadre, jamais sous le bandeau du haut (inset
  230 dp), empilement vers le bas par pas de `hauteur + PILL_STACK_GAP` quand deux pastilles se
  recouvrent ; `PillPlacement.leadY()` choisit le bord que le fil touche (le bas quand la pastille est
  au-dessus, le haut quand elle est dessous). Le texte est mesuré (`TextMeasurer`) avant le placement :
  la pastille a exactement la largeur de son contenu, et tout part dans un seul `Canvas` — fils dessous,
  pastilles dessus. `pillTranslationX`, `pillTranslationY` et `spreadLabels` disparaissent avec leurs
  tests.
- **Ce qui ne change pas** : la mesure elle-même (sténopé, voir `docs/DISTANCES.md`), les couleurs de
  masque, la ligne d'état, les pastilles de concepts. Seule la mise en forme de l'étiquette change.
- **Vérifié** (poste Windows, JDK 17, `--offline`) : `:app:testDebugUnitTest` (**0 échec**, dont
  `DistanceLayoutTest` — 15 tests) et `assembleDebug` OK, `check-kotlin-limits.py` OK. **Non vérifié** :
  le rendu sur un téléphone (aucun appareil sollicité) ; l'aspect a été contrôlé sur une maquette canvas
  qui rejoue les mêmes formules (`tmp/distances-etiquettes.html`, capture `shots/distances-etiquettes.png`,
  tous deux hors dépôt).
- **Précision de la mesure** : toujours **non mesurée** (voir `docs/DISTANCES.md`) — ce changement ne
  touche que l'affichage.
## 2026-10-26 (22) — les clés sont dans l'APK, personne n'a rien à saisir

- **Demande user** : « mets de base les clés dans l'app, qu'une autre personne n'ait pas
  besoin de rentrer ses clés si elle télécharge l'app ».
- **Ce qui manquait vraiment** : le build local embarquait déjà le LLM et ElevenLabs
  (`local.properties` → `BuildConfig` → `seededFromBuild`), mais l'APK publié par la CI
  sortait **sans aucune clé** : le runner fait un checkout neuf, sans `local.properties`.
  Deux personnes sur la même version avaient donc l'une une app prête, l'autre une app qui
  réclamait des clés.
- **Clé de dictée Meta ajoutée au build** : `asr.api.key` → `BuildConfig.ASR_API_KEY`,
  remplie par `seedAsr` quand le champ est vide. Sans elle, la dictée retombait sur
  ElevenLabs Scribe.
- **CI** : nouvelle étape « Clés du build » qui écrit `local.properties` depuis les
  secrets du dépôt (`LLM_API_KEY`, `LLM_BASE_URL`, `LLM_MODEL`, `TTS_API_KEY`,
  `TTS_VOICE_ID`, `ASR_API_KEY`) ; sans secret, elle ne fait rien et le comportement
  d'avant est conservé. Aucune valeur n'est affichée.
- **Testable** : `BuildDefaults` isole les valeurs du build, `seeded()` devient une
  fonction pure ; `SettingsSeedTest` (4) prouve qu'une installation neuve hérite de tout,
  qu'une clé saisie gagne, et qu'un build sans clé laisse les champs vides.
- **Réglages** : le bloc « build » annonce aussi la dictée et rappelle que les clés du
  build remplissent tout champ vide.
- Docs : `docs/CLES-DU-BUILD.md` (origine, priorité, secrets à créer, avertissement : une
  clé dans un APK est extractible, donc distribution restreinte).

## 2026-10-26 (21) — iSee : la réponse arrive, l'intro reste relisible

- **Réponse IA invisible et muette — cause trouvée** : `MediaCacheStore` insérait la ligne Room
  mais **rendait l'objet d'id 0** (`dao.insert(row)` sans récupérer la clé auto-générée).
  `setAnswerText` et `saveReplyAudio` faisaient donc un `UPDATE … WHERE id = 0` : zéro ligne
  touchée, texte jamais écrit, aucun MP3 attaché, donc rien à afficher ni à prononcer. Les trois
  insertions rendent maintenant `row.copy(id = dao.insert(row))`.
- **Plus de « … » qui reste** : `ask()` remet `thinking = false` dans un `finally`, garanti même
  si une étape inattendue échoue. Une seule question à la fois : une tenue pendant qu'une réponse
  est en cours est refusée avec un motif au lieu d'envoyer un second appel concurrent.
- **409 « limite atteinte »** : le proxy LLM a refusé la requête (quota ou requête concurrente —
  le corps brut ne dit pas laquelle). `httpError` mappe 409 sur `RateLimited` avec « limite
  atteinte côté IA (409) : réessaie dans un instant » au lieu du « erreur HTTP 409 : … » brut, et
  l'app n'envoie plus deux questions simultanées.
- **Fermeture complète = plus rien** : en plus de la purge d'`onDestroy`, le ViewModel purge au
  démarrage — un force-stop ou un balayage des apps récentes ne passe pas par `onDestroy` et
  laissait les messages sur le téléphone. À la réouverture, seule la bulle d'intro existe.
- **L'intro est sélectionnable avec les autres bulles** : la sélection est un index sur **toutes**
  les bulles affichées (`0` = intro), plus sur les seuls échanges. On peut remonter jusqu'à elle
  pour la relire (`swipeStep`, `selectedEntryIndex`, `IhearPlayback`).
- **Animation d'évaporation supprimée** : `PixelFade`/`PixelDissolve` disparaissent ; le bandeau
  « iSee » est un aplat noir opaque sous lequel le fil passe et disparaît d'un coup.
- **Icônes de message** : hauteur d'une bulle d'une ligne (20 sp + 24 dp, fontScale compris) au
  lieu de 28 dp fixes, avec glyphes ❚❚/▶ en 14 sp ; teinte tirée de l'identifiant parmi huit
  bulles de savon métal/verre (`BubblePalette.kt`), stable d'un rendu à l'autre.
- **Bulles plus étroites** : 230 dp au lieu de 250 dp ; les icônes ajoutent leur largeur.
- **Bulle sélectionnée toujours entière** : position calculée par `offset − viewportStartOffset`
  et complément borné (`revealShift`), et le fil ne se raccroche plus à la fin quand l'utilisateur
  relit une bulle ancienne.
- **Tests** : `MediaCacheStoreTest` (3, l'id rendu et la réponse rattachée sur un faux DAO),
  `SwipeStepTest` (9, intro comprise), `RevealShiftTest` (4), `BubblePaletteTest` (3),
  `HttpErrorTest` (4, dont 409) ; `check-kotlin-limits` OK.
