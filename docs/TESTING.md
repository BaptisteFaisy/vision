# TESTING — iSee (`:core`, `:app`)

Relu le **27/10/2026** dans cet arbre, recompté le **28/10/2026**, le **29/10/2026**,
le **01/11/2026** puis après le nettoyage code mort du **01/11/2026** (`grep`,
aucun `gradlew` lancé pour ces passes). Les
comptages de tests sont **statiques** (annotations `@Test`).
Les chemins cités sont vérifiés un par un. **Aucun résultat d'exécution sur un appareil
(téléphone ou émulateur) n'existe dans cet arbre** : tout ce qui est décrit comme recette est
une *procédure*, pas une preuve — sauf le §3 (harnais émulateur), exécuté et mesuré le 01/11/2026. La liste de ce qui n'a pas été rejoué est au §4.

## 1. Compter les tests (cet arbre, 01/11)

`rg` n'est pas installé sur cette machine : le comptage se fait avec `grep`, à l'identique.
`grep -rho '@Test' <dir> --include='*.kt' | wc -l` compte les **occurrences** de l'annotation
(une par test, `-o` évite de compter deux fois une ligne qui en porterait deux), pas les fichiers.

```bash
grep -rho '@Test' core/src/test --include='*.kt' | wc -l                    # 2 957
grep -rho '@Test' app/src/test --include='*.kt' | wc -l                     # 1 838
grep -rho '@Test' app/src/test/java/dev/sam3vision/app/glasses/bench --include='*.kt' | wc -l   # 98
grep -rho '@Test' app/src/androidTest --include='*.kt' | wc -l              # 8
grep -rho '@Test' app/src/androidTestDebug --include='*.kt' | wc -l         # 1
```

| | Fichiers `src/test` | Classes `@Test` | Annotations `@Test` |
|---|---|---|---|
| `:core` | 325 (dont 16 aides) | 310 | **2 957** |
| `:app` | 252 (dont 6 aides) | 248 | **1 838** |
| **Total JVM** | **577** | **558** | **4 795** |
| `:app` `androidTest` | 3 | 3 | **8** (instrumentés) |
| `:app` `androidTestDebug` (banc) | 1 | 1 | **1** (instrumenté) |

- **4 795 tests JVM** au total, dont **2 957** dans `:core` et **1 838** dans `:app`. Dans `:app`, le
  banc lunettes pèse **98** (`glasses/bench/`) et le reste du paquet `glasses` **177**. Les
  **9 tests instrumentés** (8 + 1) sont hors de ce total.
- « Classes `@Test` » = classes portant au moins un `@Test` (trois fichiers portent
  deux classes : `PictoMatcherTest.kt`, `SendingGateTest.kt`, `TapZonesTest.kt` — §6).
- Le banc lunettes **n'est plus un module** : ses tests JVM sont dans
  `app/src/test/…/glasses/bench/` et comptent donc dans `:app`, sans ligne à part.
- Les fichiers d'aide (pas des classes de test) : `:core` — `TestHttpServer.kt`,
  `net/NetContractHarness.kt`, `net/TestLiveClients.kt`, `live/FrameBench.kt`,
  `mask/M1Fixtures.kt`, `mask/MaskCodecFixtures.kt`, `mask/MaskFixtures.kt`,
  `mask/MaskGoldenData.kt`, `mask/JvmAllocations.kt`, `keyframe/KeyframeScene.kt`,
  `keyframe/KeyframeSim.kt`, `motion/MotionScenes.kt`, `traverse/TraverseTestFakes.kt`,
  `crossing/CaissonEval.kt`, `crossing/CrossingTestPixels.kt`,
  `predict/ShiftScenes.kt` ; `:app` —
  `isee/ProbeTestClient.kt`, `isee/TestSafetyVoice.kt`,
  `isee/vm/FakeMessageDao.kt`, `isee/vm/IseeAnswersTestBase.kt`,
  `live/LiveAskHarness.kt`, `vm/Fakes.kt`.

### Ce que Gradle exécute réellement

```bash
export JAVA_HOME=<un JDK 17 qui a javac>
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest --offline --rerun-tasks
# rapports : <module>/build/test-results/testDebugUnitTest/*.xml
```

Relevé du 29/10, sur r630 (`:core:testDebugUnitTest :app:testDebugUnitTest`, historique) :
**`:core` 1 804 / 0 échec / 0 ignoré**, **`:app` 1 071 / 0 / 0** — **2 875 exécutés**,
conformes aux comptages statiques de l'époque (le 26/10 après 19 h : `:core` 663, `:app` 305
et le banc lunettes 36 — 1 004 au total, avant la fusion du banc dans `:app`, non comparable).
L'exécution courante est celle de la CI (`ci-core`, `ci-app`), à chaque PR.

## 2. Commandes exactes

```bash
export JAVA_HOME=<un JDK 17 qui a javac> ANDROID_HOME=$HOME/android-sdk
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest --offline   # tests JVM
./gradlew :app:assembleDebug --offline          # l'APK, banc lunettes inclus (plus de module dédié)
bash scripts/build.sh                            # assembleDebug --offline (GRADLE_TIMEOUT, 600 s par défaut)
python3 tools/check-kotlin-limits.py             # fichier < 500 l, fonction < 50 l
bash scripts/test_scripts.sh                     # 177 tests
python3 tools/check-voicepack.py                 # garde du pack voix (catalogue <-> code <-> MP3)
python3 -m unittest tools/test_check_kotlin_limits.py tools/test_check_budget_derive.py tools/test_check_voice_nav_doc.py    # 58 tests
python3 -m unittest tools/test_check_apk_keys.py tools/test_export_caregiver_orb.py    # 14 tests
python3 .github/fireworks/test_conflict_agent.py  # 31 tests (agent de conflits)
python3 .github/fireworks/test_test_agent.py      # 23 tests (agent de test)
python3 .github/fireworks/test_scope_agent.py     # 22 tests (agent de périmètre)
python3 .github/fireworks/test_union_journal.py   # 6 tests (union des journaux)
python3 .github/fireworks/test_quota.py           # 2 tests (quota de saisines)
python3 .github/couche0/test_check_couche0.py     # 24 tests (garde de périmètre)
python3 .github/ci-budget/test_check_timeouts.py  # 11 tests (garde budget)
python3 .github/ci-budget/check_timeouts.py      # 7 workflows
python3 scripts/gen_mask_golden.py --check       # fixtures dorées <-> générateur
python3 scripts/budget_latence.py --check-doc    # chiffres du budget <-> doc
python3 tools/export-caregiver-orb.py --check    # shader suivi <-> .mjs versionné
python3 server/scripts/test_units.py             # 639 tests backend (stubs stdlib, sans GPU)
```

Le dépôt **n'épingle aucun JDK** : `gradle.properties` ne porte que
`org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8` (`gradle.properties:17`) et un commentaire qui
renvoie à `JAVA_HOME`. Ce qui est obligatoire, c'est un JDK **qui fournit `javac`** — un JRE
seul ne suffit pas. Bytecode et `-jvm-target` sont fixés à **17** (`app/build.gradle.kts`,
`core/build.gradle.kts`).

## 3. Rejouer une balade enregistrée sur l'émulateur (`isee-sim.sh`)

**Exécuté le 01/11/2026** sur cette machine (Debian, KVM, émulateur 37.2.12) : la balade
filmée au téléphone devient le flux de la caméra arrière, à sa vitesse réelle, et l'écran
est filmé. Aucun téléphone n'est nécessaire.

```bash
bash scripts/isee-sim.sh --video ~/Vidéos/balade.mp4 --route ~/Vidéos/balade.csv --seconds 30
```

Le harnais enchaîne, et s'arrête au premier échec en nommant le coupable :

1. **transcodage** de la vidéo en profil caméra stable (H.264 `yuv420p` 640x480, 30 fps,
   rotation appliquée, audio retiré), **mis en cache** par empreinte du fichier source
   (`$XDG_CACHE_HOME/isee-sim`) : une même balade n'est transcodée qu'une fois ;
2. **AVD** `isee-sim` créée si absente (image `android-35 google_apis x86_64`, 4 Go de RAM) ;
3. **boot** headless avec `-camera-back videofile:<vidéo>` — c'est toute l'astuce : la
   caméra de l'émulateur *est* le fichier, il n'y a rien à modifier dans l'app ;
4. `adb install -r` de l'APK debug, puis `pm grant` (`CAMERA`, `RECORD_AUDIO`, position) et
   `appops … mock_location allow` ;
5. `am start --es route isee` : l'écran iSee s'ouvre sans qu'on touche à l'écran ;
6. **rejeu du trajet** (`--route`, CSV `lat,lon` ou GPX) à 1 Hz, en tâche de fond ;
7. **screenrecord** de `--seconds` secondes, puis rapatriement ;
8. `logcat` filtré (`sam3vision`, `iSeeLive`, `iSeeMotion`, `Traverse`) + tampon `crash`,
   puis extinction de l'émulateur **que le script a démarré** (jamais celui d'un autre).

Options : `--video` (obligatoire), `--route`, `--seconds`, `--record`, `--avd`, `--no-build`,
`--keep`, `--help`. Variables : `APK`, `ADB`, `EMULATOR`, `FFMPEG`, `ISEESIM_SIZE`
(`640x480` par défaut ou `1280x720`), `ISEESIM_CACHE`, `BOOT_TIMEOUT`.

### Ce qui a été mesuré sur la caméra vidéo

| Mesure | Valeur | Comment |
|---|---|---|
| cadence livrée | **30 images/s** (temps réel) | vidéo de test avec compteur incrusté : 2 captures d'écran à 6,33 s d'écart montrent 3,990 s → 10,330 s de vidéo (192 images) |
| bouclage | **oui, indéfini** | après la fin des 60 s, le compteur reprend à 0 sans intervention |
| résolution | 640x480 par défaut (`1280x720` possible via `ISEESIM_SIZE`) | `listQemuCameras` dans le logcat de l'émulateur |
| boot à froid | ~25 s | jusqu'à `sys.boot_completed=1` |

Conséquence : une modif qui touche la caméra, la détection, l'UI ou le guidage se teste
**sans téléphone**, et l'enregistrement d'écran est la preuve à joindre.

### Position simulée : pourquoi pas `adb emu geo fix`

`geo fix` livre une position **sans précision**, et iSee jette en silence tout fix dont
`accM` dépasse `NavTuning.maxAccM` (25 m) — `NavFix.isUsable()` exige même `accM > 0`
(`core/.../nav/NavRoute.kt`). Un trajet rejoué par `geo fix` donne donc un guidage muet.

Le build **debug** embarque pour cela un receiver de simulation
(`app/src/debug/.../sim/SimLocationDebugReceiver.kt`, actions
`dev.sam3vision.app.sim.LOCATION` et `…LOCATION_CLEAR`) : il installe un test provider
`gps` **et** `network` avec une précision de 8 m. `route-replay.py` le détecte tout seul et
l'utilise ; sans lui il retombe sur `geo fix` en le disant.

```bash
# à la main, un point :
adb -s emulator-5556 shell am broadcast -a dev.sam3vision.app.sim.LOCATION -p dev.sam3vision.app \
  --ef lat 48.8566 --ef lon 2.3522 --ef acc 8 --ef bearing 90 --ef speed 1.4
```

### Ce que le harnais ne prouve pas

- **Ni latence ni performances réelles** : le rendu est logiciel (SwiftShader), les chiffres
  de `docs/LATENCE-BUDGET.md` restent mesurés sur téléphone.
- **Pas de réseau de services** : sans `local.properties`, `SAM_BASE_URL`/`SAM31_ENDPOINT`/
  `LLM_BASE_URL` sont vides → panneaux d'erreur de la sonde et des questions. Pour une
  recette hors ligne de la chaîne vision, pointer `sam31.endpoint=http://10.0.2.2:8099` sur
  `tools/mock-sam.py` (le manifeste debug autorise le cleartext, 10.0.2.2 = l'hôte).
- **Pas de caméra avant**, pas de photo/enregistrement réels : la caméra de l'émulateur est
  le fichier vidéo.

`scripts/install-emulator.sh` (ci-dessous) reste l'ancienne recette, gardée pour mémoire :
elle installe l'APK sur un émulateur **déjà en ligne**, sans caméra vidéo ni trajet.

### Téléphone réel

Jamais d'`adb` nu : passer par le verrou de la machine.
`~/bin/phone run --task "…" -- adb -s 2c6852ac install -r app/build/outputs/apk/debug/app-debug.apk`
(sortie `2` = occupé). Un téléphone `2c6852ac` était branché au moment de la rédaction, mais
**rien n'a été installé ni lancé** par cette passe.

**Le téléphone suit `master` tout seul** : `bash scripts/install-phone-latest.sh` compare le
`versionName` installé (`1.0.<commits>-<sha>`) au `master` de GitHub, reconstruit dans un miroir
dédié (`~/vision-phone/master`, jamais l'arbre de travail partagé) et installe (`adb install -r -d`,
donc un `versionCode` plus ancien est accepté sans perdre les données de l'app). Sur le poste, un
cron le relance toutes les 10 minutes : `*/10 * * * * ~/bin/vision-phone-latest --quiet`
(`~/bin/vision-phone-latest` exécute la copie du miroir, donc une correction du script arrive par
`master`). Options : `--check` (rien n'est installé, sortie `0` = à jour / `3` = en retard /
`2` = occupé), `--reinstall`, `--wait S`, `--init`, `--lock [motif]` (verrou manuel levé par
`--unlock` ; `--hold` a été supprimé le 27/10). Le script ne prend jamais le verrou d'un autre
agent : téléphone occupé = sortie `2`, le tour suivant réessaie.

## 4. Ce qui n'a jamais été rejoué sur appareil

Aucun des éléments suivants n'a d'exécution attestée dans cet arbre. Ils sont **à rejouer**
avant d'être présentés comme validés :

- les **9 tests instrumentés** (§5) — jamais lancés sur appareil ;
- la **recette manuelle du mode tap** (vidéo → SAM 3.1, écran « Envoyé / Échec ») ;
- la **recette du direct WebSocket** (pastille « WebSocket » / « KO », `X ips`, question
  « qu'est-ce qui était à gauche il y a 5 s ? » avec le journal de pistes) ;
- la **recette voix ElevenLabs** (« 🔊 Tester la voix », lecture automatique des réponses) ;
- la **recette de dictée / appui long** (Meta Muse, repli ElevenLabs Scribe) ;
- la **recette d'intro d'accueil** (rejouée à chaque ouverture froide) ;
- la **vérification de bascule de région EU/US** (« Tester SAM ») ;
- les **recettes lunettes** (`app/…/glasses/` et le banc `app/…/glasses/bench/`, `Route.Bench`).

## 5. Tests instrumentés (8 + 1 banc)

`app/src/androidTest/…/cleanup/CaptureCleanupTest.kt` (6 tests),
`app/src/androidTest/…/depth/DepthBenchDeviceTest.kt` (1 test : DA2 tourne sur
appareil) et
`app/src/androidTest/…/media/MediaDowngradeTest.kt` (1 test : rétrogradation v2
vers v1 recréée vide, sans crash) ;
`app/src/androidTestDebug/…/glasses/bench/MockGlassesTest.kt` (1 test — source set
`androidTestDebug`, compilé pour la variante debug seulement et lancé par la **même** tâche
`:app:connectedDebugAndroidTest`). Commandes (téléphone réel `2c6852ac` sous verrou,
jamais d'émulateur — flotte arrêtée) :

```bash
phone take --task "tests instrumentés"   # depuis le poste (relâche auto après 5 min)
cd builds/<worktree> && ANDROID_SERIAL=2c6852ac \
  ./gradlew :app:connectedDebugAndroidTest --offline   # les 9 tests instrumentés
phone release
# ou, après `:app:assembleDebug :app:assembleDebugAndroidTest`, classe par classe :
adb -s 2c6852ac install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 2c6852ac shell am instrument -w \
  -e class dev.sam3vision.app.cleanup.CaptureCleanupTest \
  dev.sam3vision.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s 2c6852ac shell am instrument -w \
  -e class dev.sam3vision.app.depth.DepthBenchDeviceTest \
  dev.sam3vision.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s 2c6852ac shell am instrument -w \
  -e class dev.sam3vision.app.media.MediaDowngradeTest \
  dev.sam3vision.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s 2c6852ac shell am instrument -w \
  -e class dev.sam3vision.app.glasses.bench.MockGlassesTest \
  dev.sam3vision.app.test/androidx.test.runner.AndroidJUnitRunner
```

(Le runner est `androidx.test.runner.AndroidJUnitRunner`, `app/build.gradle.kts:135`.)

Le banc n'a plus d'écran ni d'`Activity` à lui : c'est `Route.Bench` d'iSee
(Réglages → Lunettes → « Banc d'essai lunettes »), lançable par adb sans toucher l'écran —
`adb shell am start -n dev.sam3vision.app/.MainActivity --ez mock true --ez analyze true`.

Périmètre couvert par ces tests : capture de l'app supprimée · fichier hors périmètre intact ·
trace MediaStore dé-synchronisée oubliée sans erreur · capture en cours d'écriture reportée ·
périmètre limité au stockage privé de l'app · job horaire unique et sans réseau.
**Non exécutés ici** (voir §4).

## 6. Tests JVM par domaine (comptage du 01/11/2026, arbre post-nettoyage)

Une entrée par **classe portant au moins un `@Test`**, avec son nombre d'annotations
(trois fichiers portent deux classes : `PictoMatcherTest.kt`, `SendingGateTest.kt`
et `TapZonesTest.kt`, listées séparément). Table régénérée mécaniquement
(attribution positionnelle des `@Test` aux classes, vérifiée : totaux = `grep`) ;
le script jetable n'est pas versionné.

### `:core` — 2957

- **audio** (15) : PcmPreviewBufferTest 2, SentenceChunkerTest 5, TempWavStoreTest 5, WavTest 3
- **crossing** (278) : CaissonEvalTest 4, CrossingAnnouncerTest 13, CrossingApproachTest 10, CrossingArrivalTest 13, CrossingFeaturesTest 4, CrossingGuidanceTest 27, CrossingLightDueTest 4, CrossingOfferTest 9, CrossingOnPathTest 18, CrossingStateTest 38, CrossingTextTest 8, CrossingTriggerExitDecisionTest 7, CrossingVoiceCommandTest 17, CrossingVoiceHysteresisTest 5, CrosswalkScanTest 21, HeadTraversePassTest 1, NoLightCrossingTest 12, PedLightColorTest 13, PedLightGateFuzzTest 5, PedLightGateLimitsTest 7, PedLightGateTest 35, TrainedLightColorTest 7
- **curb** (87) : CurbAnnounceTest 23, CurbWatcherTest 23, SidewalkKeepTest 14, SidewalkRailTest 16, SidewalkWatcherTest 11
- **cycle** (25) : CycleConceptsTest 5, CycleMotionTest 10, CycleWatcherTest 10
- **depth** (56) : DepthAffineTest 7, DepthConfirmTest 9, DepthEstimatorTest 2, DepthFusionTest 9, DepthMapTest 5, DepthReadingsTest 18, SerialRunGuardTest 6
- **distance** (191) : CalibrationSolverTest 3, DistanceBenchmarkTest 5, DistanceEstimatorTest 9, DistanceFusionTest 14, DistanceKalmanTest 11, DistanceMeterTest 26, DistanceTextEnglishTest 7, DistanceTextTest 8, DistortionTest 5, GroundPlaneTest 18, KnownSizesTest 28, LensSpecTest 17, OpticsTest 30, ReadingQualityTest 10
- **exposure** (7) : ExposureGovernorTest 7
- **gesture** (19) : IseeGestureBrainTest 5, LongPressTriggerTest 10, TapOrHoldTest 4
- **glasses** (8) : CaptureGestureTest 7, DatPinTest 1
- **haptics** (10) : CrossingHapticsTest 2, WaveformTest 8
- **hazard** (76) : FenceOpeningConceptsTest 7, FenceOpeningSignalTest 13, FenceOpeningTextTest 9, FenceOpeningWatcherTest 10, HazardConceptsTest 11, HazardWatchTest 26
- **iseedesign** (33) : DissolveTest 10, IseePortraitTest 11, IseeSceneTest 9, SpokenCaptionTest 3
- **keyframe** (37) : KeyframeSchedulerTest 21, KeyframeSimTest 9, RequestBudgetTest 7
- **live** (661) : AnnouncementGateTest 11, AttentionCadenceTest 18, CaptureStampTest 3, FrameBenchTest 3, FrameGateEraTest 4, FrameGateTest 5, FrameRateTest 4, FrameRosterTest 7, FreeSpaceTest 14, GlassesLatencyTest 10, GuidanceAnnouncerSidewalkTest 8, GuidanceAnnouncerSteeringTest 2, GuidanceAnnouncerTest 45, GuidanceFenceOpeningTest 12, GuidanceHeartbeatTest 6, GuidanceInstructionTest 6, GuidanceObstaclesTest 10, GuidanceRayTest 9, GuidanceStarvationTest 7, GuidanceSteeringTest 18, GuidanceTest 38, GuidanceWordTest 7, HomeModesTest 15, LatencyBenchTest 1, LatencyProfileTest 11, LatencyTraceTest 12, LateralNudgeTest 40, LatestFrameSlotTest 6, LiveCadenceSimTest 1, LiveCrowdCadenceTest 5, LiveFrameBudgetTest 1, LiveMemoryFlowTest 2, LiveNarratorTest 7, LivePipelineTest 7, LivePromptsNavigationTest 2, LivePromptsTest 23, LivePumpTest 7, LiveSessionBytesTest 1, LiveSessionCloseTest 2, LiveSessionStopTest 3, LiveSessionTest 20, LiveStatsTest 9, LiveTimelineTest 10, LiveTimingsTest 15, LiveWireGuardTest 9, MaisonEnterCommandTest 4, PeoplePathRobustnessTest 11, PeoplePathTest 29, PersonLabelsTest 5, PersonTrackTest 16, PositionAnnouncerTest 8, PositionTextEnglishTest 5, PositionTextTest 18, ScreenTimingsTest 4, SignConceptsTest 10, SteeringTest 12, TimelineTextTest 7, TimingTest 13, TimingsTextTest 6, TrackRunnerStartTest 3, TrackRunnerTest 17, TwinLiteWordTest 10, UploadFastDropTest 8, UploadLadderTest 19
- **local** (39) : BlockMotionAdapterTest 3, LocalOverlayEngineTest 24, LocalOverlaySimTest 3, LocalPropagatorBenchTest 1, LocalPropagatorTest 8
- **mask** (115) : MaskBenchTest 3, MaskColorizePerfTest 4, MaskCoreTest 20, MaskDecodeTest 7, MaskDetectionsTest 9, MaskGeometryTest 8, MaskGoldenTest 2, MaskMotionTest 6, MaskPolyTest 7, MaskPropagatorBenchTest 1, MaskPropagatorTest 13, MaskRleTest 8, MaskRobustnessTest 20, MaskUpscaleTest 7
- **model** (10) : GeometryTest 4, ObjectLimitsTest 6
- **motion** (40) : GyroCompensationTest 14, MotionBenchTest 1, MotionSourceTest 9, VehicleBackgroundTest 16
- **nav** (248) : BanGeocoderTest 7, ConfirmAnswerTest 4, CrossingManeuverTest 4, DestinationQueryTest 10, DetourRerouteTest 5, GeocodeCacheTest 6, GeocodeChainFailureTest 7, GeocodeChainTest 13, GpsDestinationTextTest 6, GuidanceMapDataTest 10, GuidanceUpdateTest 8, KnownPlacesTest 4, MapsUriTest 13, NavGeoTest 13, NavRouteTest 6, NavigationFlowTest 4, NavigationIntentSansPrepositionTest 6, NavigationIntentTest 27, PeriodicGuidanceTest 14, RouteMapProjectionTest 6, RouteSteeringFusionTest 7, RouteSummaryTest 11, RouteTripNavTest 6, StopGuidanceTest 14, ValhallaRouteTest 8, ValhallaShapeTest 6, VoiceNavArrivalBoundaryTest 4, VoiceNavCrossingTest 3, VoiceNavEngineTest 6, VoiceNavRerouteRobustnessTest 4, VoiceNavScriptTest 6
- **navcore** (20) : NavMessagesTest 12, NavUiStateTest 8
- **net** (347) : ElevenLabsSttClientTest 5, ElevenLabsTtsClientTest 10, FallbackSpeechClientTest 4, GpuMonitorTest 11, HttpErrorTest 4, HttpLlmClientTest 3, HttpRequestBuilderTest 10, HttpRequestsTest 7, LifecycleContractTest 10, LiveClientFactoryTest 6, LiveClientTest 16, LiveContractTest 15, LiveNarrationClientTest 4, LlmClientTest 14, LlmContractTest 23, LlmInjectionTest 5, LlmPromptsTest 6, LlmRequestLanguageTest 3, MetaAsrClientTest 8, MetaAsrContractTest 8, MetaImageClientTest 5, MetaRealtimeClientTest 9, MetaRealtimeContractTest 16, RouteClientTest 9, SamClientTest 6, SamContractTest 12, SamLifecycleTest 6, TrackClientTest 17, VideoClientTest 12, VideoContractTest 10, VoiceContractTest 17, WsLiveBinaryTest 10, WsLiveBytesTest 6, WsLiveClientPreconnectTest 5, WsLiveClientTest 24, WsLiveContractTest 11
- **picto** (30) : PictoMatcherTest 6, PictoPlaceModeTest 6, PictoPromptTest 2, PictoVoiceOrderTest 16
- **predict** (70) : BlockShiftTest 7, BoxPredictorTest 10, BoxTest 10, EgoCompensatorTest 12, MaskShiftTest 5, MotionModelTest 7, ObjectAssociatorTest 5, SamResiduTest 14
- **settings** (100) : CaregiverInviteTest 7, OutdoorModeTest 4, PrivateFileSettingsStoreTest 17, ResilientSettingsStoreTest 2, SamRegionTest 9, SettingsAnnounceTest 4, SettingsAsrTest 5, SettingsCipherTest 9, SettingsCodecTest 15, SettingsGuidanceTest 4, SettingsIntroTest 6, SettingsSecretsTest 9, SettingsStoreParityTest 4, SettingsTtsTest 5
- **share** (37) : CaregiverPageTest 9, SharePayloadTest 14, ShareSendMessageTest 4, ShareStreamTest 6, SharedSceneTest 4
- **speech** (51) : GlassesSessionOrderTest 5, LockedMicStartTest 15, LockedMicTest 11, SettingsVoiceCommandTest 4, SpeechLanguageTest 5, TtsSpeedCommandTest 11
- **stairs** (109) : HandrailWatcherTest 7, StairClimbTest 11, StairConceptsTest 4, StairCountVoiceTest 7, StairDetectorTest 21, StairLabelSignalTest 7, StairLabelsFallbackTest 4, StairLastStepGateTest 6, StairMaskPlaneTest 5, StairSoftContrastTest 2, StairWatcherTest 17, StairsCannyTest 9, StairsFeedTest 5, StairsFeedVoiceTest 4
- **text** (14) : PromptShieldTest 10, TextSanitizerTest 4
- **traverse** (168) : ApproachLabelsTest 3, EgoVoteWatcherTest 7, TraversePipelineDepthTest 2, TraversePipelineTest 11, TraverseStateTest 12, TraverseVetoDepthTest 3, TraverseVetoPriorityTest 9, TraverseVetoTest 19, TraverseVoiceTest 24, VehicleApproachDepthTest 4, VehicleApproachTest 23, VehicleStateBackgroundTest 5, VehicleStateDepthTest 4, VehicleStateLateralTest 3, VehicleStateRangeTest 6, VehicleStateTest 22, VehicleStateTextTest 4, VerdictFusionTest 7
- **usecase** (33) : DictationUseCaseTest 4, NavigationRouterTest 15, UseCasesTest 14
- **voicenav** (23) : NavClarifyTest 8, VoiceDestinationParserTest 15

### `:app` — 1838

- **(racine)** (206) : BuildInfoTest 2, CaregiverShareLinkTest 6, DistanceLayoutTest 20, FirstRunPermissionsTest 3, GlassesPhotoRoutingTest 3, GoogleMapsSeedTest 3, GpuStatusLineTest 1, GpuWakeTest 8, HomePresetRequestTest 3, LiveCrossingPublishTest 5, LiveFrameDropsTest 1, LiveLocalOverlayTest 13, LiveMaskTransportTest 4, LiveMemoryContextTest 3, LiveStagePublishTest 3, LiveStateTest 21, LiveVoiceRequestTest 3, LiveVoiceTest 4, LocaleIndependenceTest 2, MicBusTest 5, ModelPinTest 7, ParentShareTest 5, PhotoTimingsTest 2, PictoOverlayTest 2, PrewarmTest 2, RouteBaseUrlSeedTest 3, RouteSeedTest 3, RouteStatusTextTest 3, SettingsSeedTest 29, ShareTrackingPolicyTest 3, SpeechClientsTest 6, TtsLogicTest 9, UiLogicTest 19
- **cleanup** (15) : CaptureCleanerTest 6, CaptureScannerTest 5, CleanupDowngradeTest 4
- **depth** (21) : DepthDecodeCacheTest 3, DepthInferenceNeededTest 3, DepthPreprocessTest 8, LiveDepthMeterTest 2, ModelDownloaderTest 5
- **glasses** (177) : GlassesAnnexBTest 15, GlassesAudioPlanTest 7, GlassesAudioRouteTest 11, GlassesAutoListenTest 5, GlassesBackgroundFramesTest 6, GlassesBitmapCropTest 5, GlassesFrameCollectorTest 13, GlassesMessagesTest 8, GlassesPermissionTest 6, GlassesPhaseMappingTest 3, GlassesPolicyTest 13, GlassesSessionLifecycleTest 4, GlassesSessionRetryTest 3, GlassesStreamConfigTest 5, GlassesTalkPolicyTest 8, GlassesTalkTest 14, GlassesToggleTest 4, GlassesUiStateTest 8, GlassesVideoCropTest 5, GlassesVideoDecoderTest 10, GlassesVideoLinkTest 16, GlassesVideoRecorderTest 3, GlassesVoiceRouteTest 5
- **glasses.bench** (98) : ChaineCompleteTest 8, CycleLatchTest 5, GlassesFrameEncoderTest 4, GlassesFramePolicyTest 32, LinkGateTest 3, SessionPolicyTest 18, SpikeLaunchTest 9, SpikeLogTest 6, YuvConverterTest 13
- **guidemap** (8) : GuideMapStyleTest 8
- **isee** (610) : BubbleCountTest 2, CameraHalProcessingTest 5, CrossingChipZoneTest 1, CrossingLateralTest 17, CrossingOverlayLayoutTest 19, CrossingPeopleTest 25, CrossingRecenterTest 7, CrossingSteeringTest 8, CrossingVehicleTest 16, CrossingWiringTest 19, CrosswalkScanDisplayTest 10, CrosswalkScanWiringTest 12, CutSpeakingTest 2, FramePolicyTest 2, IseeCameraLightTest 7, IseeCaptionStateTest 3, IseeGlassesInboxTest 5, IseeGlassesPhotoTest 6, IseeGuidanceMapTest 2, IseePlaybackTest 12, IseeRenderTest 12, IseeSamFrameTest 1, IseeSceneGlassesTest 8, IseeScenePhotoTest 3, IseeSceneTest 17, IseeShareTest 5, IseeStatusLineTest 5, LightCropLoggerTest 4, LiveProbeCadenceTest 7, LiveProbeCalibrationTest 6, LiveProbeCrowdTest 2, LiveProbeDepthOfferTest 2, LiveProbeForegroundTest 2, LiveProbeGuidanceTest 7, LiveProbePublishSplitTest 2, LiveProbeSchedulingTest 2, LiveProbeStateTest 1, LiveProbeSteeringTest 5, LiveProbeStopTest 5, LiveProbeTextTest 24, LiveProbeWakeTest 3, LiveVehicleVoiceTest 6, LiveWifiLockTest 14, NavButtonZonesTest 3, NoLightCrossingTest 14, ObstacleArbiterTest 17, ObstacleCurbVoiceTest 3, OnboardingGesturesTest 6, OrbGravityTest 8, OrbShaderTest 3, PictoGeneratingSpinTest 3, PictoStoreTest 8, PicturingGateTest 4, ProbeBackgroundMotionTest 7, ProbeEnsureConceptTest 7, ProbeFrameTest 2, ProbeHazardsTest 8, ProbeMetersPredictionsTest 1, ProbePulseTest 4, ProbeSlicesTest 6, ProbeSpokenFlowTest 5, ProbeStairsCountVoiceTest 5, ProbeStairsGateTest 2, ProbeStairsPriorityTest 7, ProbeStairsRoiTest 3, ProbeVoiceFenceHoleTest 2, ProbeVoiceSidewalkTest 6, ProbeVoiceTest 12, ProbeWiringSpokenTest 6, ProbeWiringStairsCountTest 5, ProbeWiringTracksTest 6, ProbeWiringWatchersTest 5, ProximityVoiceArrivalTest 6, ProximityVoicePreemptTest 10, ProximityVoiceQueueTest 8, ProximityVoiceRecordedTest 5, ProximityVoiceRepeatTest 8, ProximityVoiceRetryTest 4, ProximityVoiceRouteTest 6, ProximityVoiceTest 20, ProximityVoiceUserTest 7, ProximityVoiceWordTest 7, RevealShiftTest 4, SafetyVoiceTest 5, SendingGateTest 4, SharedCameraFrameTest 3, SoftHeldForUserTest 4, SpeakableTextTest 3, StairCannyCountTest 15
- **isee.vm** (157) : IseeAnswersPreemptTest 2, IseeAnswersTest 22, IseeCameraCoordinatorTest 6, IseeDictationTest 13, IseeGlassesOrderTest 4, IseeLiveDictationTest 3, IseePhotoShotTest 17, IseePictoTest 20, IseePictoVoiceTest 19, IseeStatusTextTest 4, IseeSteeringOrdersTest 11, IseeStreamDictationTest 9, IseeTranscriptTest 5, IseeVoiceGateTest 6, MaisonEnterOrderTest 3, NavigationHookTest 7, PlaceModeOrderTest 6
- **live** (160) : CalibrationSnapshotsTest 3, DistanceTickerTest 6, GlassesMireTest 7, LiveAdaptiveTest 9, LiveAskDestinationTest 5, LiveAskForgetTest 1, LiveAskSessionTest 24, LiveAskVoiceTest 6, LiveFpsMeterTest 6, LiveFrameCadenceTest 9, LiveFrameDispatcherTest 10, LiveLatencyLogTest 9, LiveLensTest 15, LiveMaskDecoderTest 6, LiveNarrationTest 5, LiveNavOrdersTest 5, LivePositionVoiceTest 7, LivePredictionTest 9, LiveTrackFallbackTest 3, LiveWakeGuardTest 7, TrackFrameBridgeTest 2, TrackTrailsTest 6
- **locked** (10) : LockedMicAnswerTest 10
- **media** (20) : MediaCacheStoreTest 4, Nv21JpegEncoderTest 15, PcmToMp3EncoderTest 1
- **navigation** (24) : AndroidGeocoderLabelTest 4, NavigationLauncherTest 12, VoiceNavTrackerTest 8
- **perms** (2) : PermDecisionTest 2
- **pose** (19) : PitchMathTest 19
- **speech** (107) : SpeechArbitrationTest 7, SystemTtsEngineTest 13, VoiceNavAnnouncerTest 5, VoiceNavPhrasesTest 11, VoiceNavPolicyTest 29, VoiceNavSpeakerRepeatTest 5, VoiceNavSpeakerTest 25, VoicePackTest 12
- **tap** (34) : AnnouncementPlayerTest 2, TapConfigTest 4, TapMachineTest 8, TapPhotoFallbackTest 8, TapReleaseTest 2, TapSessionTest 6, TapZonesTest 4
- **traverse** (25) : TraverseClipStoreTest 4, TraverseWiringTest 21
- **vm** (39) : AppAnalysisTest 9, AppChatTest 6, AppDiagnosticsTest 10, AppImageLoaderTest 4, AppScreenStateTest 3, AppVoiceTest 7
- **voicenav** (106) : GpsDestinationMemoryTest 5, GuidanceLifecycleTest 4, GuidanceNotificationTextTest 5, OffRouteBannerTest 2, PeriodicVoiceTest 2, RouteLoadingSpinTest 3, RouteMapSnapshotTest 3, ValhallaRouteLoaderTest 13, VoiceNavEngineDriverTest 4, VoiceNavHoldTest 14, VoiceNavSessionArrivedTest 6, VoiceNavSessionAutoStartTest 9, VoiceNavSessionTest 28, VoiceNavSessionWiringTest 8


## 7. Gardes et limites connues

- `tools/check-kotlin-limits.py` (heuristique accolades, faux positifs possibles) : **vert le
  01/11** sur cet arbre (1 356 fichiers `.kt`/`.kts`/`.py` inspectés, 0 dépassement).
- `bash scripts/test_scripts.sh` : **177 tests, 177 ok**.
- `python3 tools/check-voicepack.py` : **OK** (114 clips EN, 0 FR, 0 surplus).
- Voix ElevenLabs sans clé : `VoiceAnnouncer.say()` rend `InvalidKey`
  (`app/VoiceAnnouncer.kt:32-33`), mais l'annonce des positions
  (`app/AppViewModel.kt:216,225`) et les bandeaux ignorent le résultat —
  **rien ne s'affiche**. Mettre une clé ou lire le log.
- GPU Modal : **une seule carte à la fois**, toutes apps confondues ; sur cette machine,
  `~/bin/sam3-gpu status|busy` avant toute manœuvre, et ne rien redéployer.
- Coordonnées d'écran, seuils de gestes et textes d'affichage ne sont pas assertés par un test
  de bout en bout : une recette manuelle reste le seul contrôle pour eux.
