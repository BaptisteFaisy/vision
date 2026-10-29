# TESTING — iSee (`:core`, `:app`)

Relu le **27/10/2026** dans cet arbre, recompté le **28/10/2026** puis le **29/10/2026**
(`grep`, aucun `gradlew` lancé pour ces passes). Les comptages de tests sont
**statiques** (annotations `@Test`).
Les chemins cités sont vérifiés un par un. **Aucun résultat d'exécution sur un appareil
(téléphone ou émulateur) n'existe dans cet arbre** : tout ce qui est décrit comme recette est
une *procédure*, pas une preuve. La liste de ce qui n'a pas été rejoué est au §4.

## 1. Compter les tests (cet arbre, 29/10)

`rg` n'est pas installé sur cette machine : le comptage se fait avec `grep`, à l'identique.
`grep -rho '@Test' <dir> --include='*.kt' | wc -l` compte les **occurrences** de l'annotation
(une par test, `-o` évite de compter deux fois une ligne qui en porterait deux), pas les fichiers.

```bash
grep -rho '@Test' core/src/test --include='*.kt' | wc -l                    # 1765
grep -rho '@Test' app/src/test --include='*.kt' | wc -l                     # 944
grep -rho '@Test' app/src/test/java/dev/sam3vision/app/glasses/bench --include='*.kt' | wc -l   # 95
grep -rho '@Test' app/src/androidTest --include='*.kt' | wc -l              # 9
grep -rho '@Test' app/src/androidTestDebug --include='*.kt' | wc -l         # 1
```

| | Fichiers `src/test` | Classes `@Test` | Annotations `@Test` |
|---|---|---|---|
| `:core` | 239 (dont 14 aides) | 226 | **1 765** |
| `:app` | 142 (dont 3 aides) | 140 | **944** |
| **Total JVM** | **381** | **366** | **2 709** |
| `:app` `androidTest` | 4 | 4 | **9** (instrumentés) |
| `:app` `androidTestDebug` (banc) | 1 | 1 | **1** (instrumenté) |

- **2 709 tests JVM** au total, dont **1 765** dans `:core` et **944** dans `:app`. Dans `:app`, le
  banc lunettes pèse **95** (`glasses/bench/`) et le reste du paquet `glasses` **71**. Les
  **10 tests instrumentés** (9 + 1) sont hors de ce total.
- « Classes `@Test` » = déclarations `class …Test` dans les fichiers portant `@Test`
  (deux fichiers portent deux classes : `PictoMatcherTest.kt`, `TapZonesTest.kt` — §6).
- Le banc lunettes **n'est plus un module** : ses tests JVM sont dans
  `app/src/test/…/glasses/bench/` et comptent donc dans `:app`, sans ligne à part.
- Les fichiers d'aide (pas des classes de test) : `:core` — `TestHttpServer.kt`,
  `net/NetContractHarness.kt`, `net/TestLiveClients.kt`, `live/FrameBench.kt`,
  `mask/M1Fixtures.kt`, `mask/MaskCodecFixtures.kt`, `mask/MaskFixtures.kt`,
  `mask/MaskGoldenData.kt`, `mask/JvmAllocations.kt`, `keyframe/KeyframeScene.kt`,
  `keyframe/KeyframeSim.kt`, `motion/MotionScenes.kt`, `traverse/TraverseTestFakes.kt`,
  `ground/SyntheticScene.kt` ; `:app` — `isee/ProbeTestClient.kt`,
  `isee/vm/FakeMessageDao.kt`, `vm/Fakes.kt`.

### Ce que Gradle exécute réellement

```bash
export JAVA_HOME=<un JDK 17 qui a javac>
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest --offline --rerun-tasks
# rapports : <module>/build/test-results/testDebugUnitTest/*.xml
```

Relevé du 29/10, sur r630 (`:core:testDebugUnitTest :app:testDebugUnitTest`) : **`:core`
1 765 / 0 échec / 0 ignoré**, **`:app` 944 / 0 / 0** — **2 709 exécutés** au total, conformes aux
comptages statiques du §1. (Historique : le 26/10 après 19 h, `:core` 663, `:app` 305 et le banc
lunettes 36 — 1 004 au total, avant la fusion du banc dans `:app`, non comparable.)

## 2. Commandes exactes

```bash
export JAVA_HOME=<un JDK 17 qui a javac> ANDROID_HOME=$HOME/android-sdk
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest --offline   # tests JVM
./gradlew :app:assembleDebug --offline          # l'APK, banc lunettes inclus (plus de module dédié)
bash scripts/build.sh                            # assembleDebug --offline (GRADLE_TIMEOUT, 600 s par défaut)
python3 tools/check-kotlin-limits.py             # fichier < 500 l, fonction < 50 l
bash scripts/test_scripts.sh                     # 101 assertions
python3 -m unittest tools/test_check_kotlin_limits.py tools/test_check_budget_derive.py tools/test_check_voice_nav_doc.py    # 58 tests
python3 -m unittest tools/test_check_apk_keys.py tools/test_export_caregiver_orb.py    # 14 tests
python3 .github/fireworks/test_conflict_agent.py  # 16 tests (agent de conflits)
python3 .github/couche0/test_check_couche0.py     # 19 tests (garde de périmètre)
python3 .github/ci-budget/test_check_timeouts.py  # 11 tests (garde budget)
python3 .github/ci-budget/check_timeouts.py      # 8 workflows
python3 scripts/gen_mask_golden.py --check       # fixtures dorées <-> générateur
python3 scripts/budget_latence.py --check-doc    # chiffres du budget <-> doc
python3 tools/export-caregiver-orb.py --check    # shader suivi <-> .mjs versionné
python3 server/scripts/test_units.py             # 254 tests backend (numpy, Pillow, fastapi)
```

Le dépôt **n'épingle aucun JDK** : `gradle.properties` ne porte que
`org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8` (`gradle.properties:15`) et un commentaire qui
renvoie à `JAVA_HOME`. Ce qui est obligatoire, c'est un JDK **qui fournit `javac`** — un JRE
seul ne suffit pas. Bytecode et `-jvm-target` sont fixés à **17** (`app/build.gradle.kts`,
`core/build.gradle.kts`).

## 3. Recette émulateur (flotte arrêtée — ne plus utiliser)

> **La flotte d'émulateurs est arrêtée et désactivée** (relevé antérieur, hors de
> cet arbre) : on ne teste que sur le **téléphone réel** (section « Téléphone réel »
> ci-dessous).
> La recette émulateur ci-dessous est conservée pour mémoire — ses commandes
> ne passent plus sur aucune machine du projet.

Le script `scripts/install-emulator.sh` faisait la chaîne complète — reconstruisait l'APK
s'il manquait, l'installait, accordait `CAMERA`, lançait l'app, prenait une capture
d'écran et en affichait le chemin :

```bash
EMULATOR_SERIAL=emulator-5554 bash scripts/install-emulator.sh   # tout ci-dessous
```

Variables lues par le script (`scripts/install-emulator.sh`, en-tête) : `EMULATOR_SERIAL`
(**défaut `emulator-5554`**), `SKIP_BUILD=1` (ne pas rebâtir l'APK), `APK`, `ADB`, `TMPDIR`.
Il ne lit ni n'affiche aucune valeur secrète.

À la main, si besoin :

```bash
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell pm grant dev.sam3vision.app android.permission.CAMERA
adb -s emulator-5554 shell pm grant dev.sam3vision.app android.permission.RECORD_AUDIO   # non posée par le script
adb -s emulator-5554 shell am start -n dev.sam3vision.app/.MainActivity
adb -s emulator-5554 shell screencap /sdcard/s.png && adb -s emulator-5554 pull /sdcard/s.png /tmp/samvision.png
```

- Le script accorde **`CAMERA` seulement** : les recettes de dictée et de voix exigent
  `RECORD_AUDIO`, à accorder à la main.
- Coordonnées de recette : propres à **1080×2400 @ 420 dpi** ; re-mesurer avec
  `uiautomator dump` si l'UI bouge. `KEYCODE_BACK` peut sortir de l'app (préférer le bouton
  « Retour » de l'écran).
- L'app reprend l'écran où elle était après `am start` : pour rejouer depuis l'accueil,
  `adb -s <serial> shell am force-stop dev.sam3vision.app` avant.
- `install -r` échoue si un build antérieur a changé de signature : `uninstall` d'abord.
- Les champs de clés sont masqués à l'écran (`•••`) : aucune clé lisible dans une capture.

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

- les **10 tests instrumentés** (§5) — jamais lancés sur appareil ;
- la **recette manuelle du mode tap** (vidéo → SAM 3.1, écran « Envoyé / Échec ») ;
- la **recette du direct WebSocket** (pastille « WebSocket » / « KO », `X ips`, question
  « qu'est-ce qui était à gauche il y a 5 s ? » avec le journal de pistes) ;
- la **recette voix ElevenLabs** (« 🔊 Tester la voix », lecture automatique des réponses) ;
- la **recette de dictée / appui long** (Meta Muse, repli ElevenLabs Scribe) ;
- la **recette d'intro d'accueil** (rejouée à chaque ouverture froide) ;
- la **vérification de bascule de région EU/US** (« Tester SAM ») ;
- les **recettes lunettes** (`app/…/glasses/` et le banc `app/…/glasses/bench/`, `Route.Bench`).

## 5. Tests instrumentés (9 + 1 banc)

`app/src/androidTest/…/cleanup/CaptureCleanupTest.kt` (6 tests),
`app/src/androidTest/…/depth/DepthBenchDeviceTest.kt` (1 test : DA2 tourne sur
appareil), `app/src/androidTest/…/segment/SegmentBenchDeviceTest.kt` (1 test : TwinLite
téléchargé depuis la release GitHub et inféré sur appareil, verdict dans logcat) et
`app/src/androidTest/…/media/MediaDowngradeTest.kt` (1 test : rétrogradation v2
vers v1 recréée vide, sans crash) ;
`app/src/androidTestDebug/…/glasses/bench/MockGlassesTest.kt` (1 test — source set
`androidTestDebug`, compilé pour la variante debug seulement et lancé par la **même** tâche
`:app:connectedDebugAndroidTest`). Commandes (téléphone réel `2c6852ac` sous verrou,
jamais d'émulateur — flotte arrêtée) :

```bash
phone take --task "tests instrumentés"   # depuis le poste (relâche auto après 5 min)
cd builds/<worktree> && ANDROID_SERIAL=2c6852ac \
  ./gradlew :app:connectedDebugAndroidTest --offline   # les 10 tests instrumentés
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
  -e class dev.sam3vision.app.segment.SegmentBenchDeviceTest \
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

## 6. Tests JVM par domaine (comptage du 29/10)

Une entrée par fichier `*Test.kt`, avec son nombre d’annotations `@Test` (deux fichiers portent deux classes : `PictoMatcherTest.kt` — 6 + 2 — et `TapZonesTest.kt` — 4 + 2).

### `:core` — 1765

- **audio** (15) : PcmPreviewBufferTest 2, SentenceChunkerTest 5, TempWavStoreTest 5, WavTest 3
- **crossing** (68) : CrossingAnnouncerTest 9, CrossingFeaturesTest 4, CrossingStateTest 8, CrossingTextTest 5, CrossingVoiceCommandTest 9, PedLightColorTest 9, PedLightGateTest 20, TrainedLightColorTest 4
- **curb** (25) : CurbWatcherTest 11, SidewalkKeepTest 14
- **depth** (29) : DepthEstimatorTest 3, DepthFusionTest 9, DepthMapTest 5, DepthReadingsTest 7, SerialRunGuardTest 5
- **distance** (92) : DistanceFusionTest 9, DistanceMeterTest 12, DistanceTextEnglishTest 7, DistanceTextTest 8, GroundPlaneTest 17, KnownSizesTest 8, LensSpecTest 7, OpticsTest 24
- **gesture** (19) : IseeGestureBrainTest 5, LongPressTriggerTest 10, TapOrHoldTest 4
- **glasses** (7) : CaptureGestureTest 6, DatPinTest 1
- **ground** (45) : ConnectedComponentsTest 5, DirectionalFeedbackTest 5, GroundConfigTest 4, GroundPipelineTest 3, GroundSeedsTest 4, ObstacleRegionsTest 6, PointCloudTest 7, RansacPlaneTest 6, RiskZonesTest 3, SceneLabelsTest 2
- **haptics** (10) : CrossingHapticsTest 2, WaveformTest 8
- **iseedesign** (33) : DissolveTest 10, IseePortraitTest 11, IseeSceneTest 9, SpokenCaptionTest 3
- **keyframe** (37) : KeyframeSchedulerTest 21, KeyframeSimTest 9, RequestBudgetTest 7
- **live** (299) : CaptureStampTest 3, FrameGateEraTest 4, FrameGateTest 5, FrameRateTest 4, FrameRosterTest 7, GuidanceAnnouncerSidewalkTest 6, GuidanceAnnouncerSteeringTest 2, GuidanceAnnouncerTest 11, GuidanceSteeringTest 13, GuidanceTest 17, HomeModesTest 15, LatencyBenchTest 1, LatencyProfileTest 11, LatencyTraceTest 12, LatestFrameSlotTest 5, LiveCadenceSimTest 1, LiveFrameBudgetTest 1, LiveMemoryFlowTest 2, LiveNarratorTest 7, LivePipelineTest 7, LivePromptsNavigationTest 2, LivePromptsTest 10, LivePumpTest 4, LiveSessionCloseTest 2, LiveSessionStopTest 3, LiveSessionTest 19, LiveStatsTest 6, LiveTimelineTest 10, LiveTimingsTest 15, LiveWireGuardTest 5, MaisonEnterCommandTest 4, PositionAnnouncerTest 7, PositionTextEnglishTest 5, PositionTextTest 12, ScreenTimingsTest 4, SteeringTest 12, TimelineTextTest 7, TrackRunnerStartTest 3, TrackRunnerTest 16, UploadLadderTest 19
- **local** (27) : LocalOverlayEngineTest 24, LocalOverlaySimTest 3
- **mask** (97) : MaskBenchTest 2, MaskColorizePerfTest 4, MaskCoreTest 20, MaskDecodeTest 7, MaskGoldenTest 2, MaskMotionTest 6, MaskPolyTest 7, MaskPropagatorBenchTest 1, MaskPropagatorTest 13, MaskRleTest 8, MaskRobustnessTest 20, MaskUpscaleTest 7
- **model** (7) : GeometryTest 4, ObjectLimitsTest 3
- **motion** (9) : MotionBenchTest 1, MotionSourceTest 8
- **nav** (180) : BanGeocoderTest 7, ConfirmAnswerTest 4, CrossingManeuverTest 4, DestinationQueryTest 10, GeocodeCacheTest 6, GeocodeChainFailureTest 7, GeocodeChainTest 10, GuidanceUpdateTest 8, MapsUriTest 13, NavGeoTest 9, NavRouteTest 6, NavigationFlowTest 4, NavigationIntentTest 21, RouteMapProjectionTest 6, RouteSteeringFusionTest 7, RouteSummaryTest 11, RouteTripNavTest 6, StopGuidanceTest 4, ValhallaRouteTest 8, ValhallaShapeTest 6, VoiceNavArrivalBoundaryTest 4, VoiceNavCrossingTest 3, VoiceNavEngineTest 6, VoiceNavRerouteRobustnessTest 4, VoiceNavScriptTest 6
- **navcore** (50) : NavErrorTest 10, NavMessagesTest 12, NavResultsTest 7, NavStopTest 6, NavUiStateTest 8, RetryPolicyTest 7
- **net** (289) : ElevenLabsSttClientTest 5, ElevenLabsTtsClientTest 8, FallbackSpeechClientTest 4, GpuMonitorTest 11, HttpErrorTest 4, HttpLlmClientTest 3, HttpRequestBuilderTest 10, HttpRequestsTest 3, LifecycleContractTest 10, LiveClientFactoryTest 4, LiveClientTest 16, LiveContractTest 13, LiveNarrationClientTest 4, LlmClientTest 12, LlmContractTest 23, LlmInjectionTest 5, LlmPromptsTest 6, LlmRequestLanguageTest 3, MetaAsrClientTest 8, MetaAsrContractTest 8, MetaImageClientTest 5, RouteClientTest 9, SamClientTest 6, SamContractTest 12, SamLifecycleTest 5, TrackClientTest 12, ValhallaRouteClientTest 6, VideoClientTest 12, VideoContractTest 10, VoiceContractTest 17, WsLiveClientTest 24, WsLiveContractTest 11
- **picto** (8) : PictoMatcherTest 8 (deux classes : PictoMatcherTest 6 + PictoPromptTest 2)
- **predict** (39) : BlockShiftTest 7, BoxPredictorTest 10, BoxTest 10, MotionModelTest 7, ObjectAssociatorTest 5
- **segment** (39) : TwinLiteAnnouncerTest 12, TwinLiteDecodeTest 6, TwinLiteGuideTest 13, WalkGuideTest 8
- **settings** (95) : CaregiverInviteTest 7, PrivateFileSettingsStoreTest 17, ResilientSettingsStoreTest 2, RouteEndpointTest 5, SamRegionTest 8, SettingsAnnounceTest 4, SettingsAsrTest 2, SettingsCipherTest 9, SettingsCodecTest 13, SettingsGuidanceTest 4, SettingsIntroTest 6, SettingsSecretsTest 9, SettingsStoreParityTest 4, SettingsTtsTest 5
- **share** (22) : CaregiverPageTest 7, SharePayloadTest 7, ShareSendMessageTest 4, SharedSceneTest 4
- **speech** (46) : LockedMicStartTest 15, LockedMicTest 11, SettingsVoiceCommandTest 4, SpeechLanguageTest 5, TtsSpeedCommandTest 11
- **stairs** (42) : StairConceptsTest 4, StairDetectorTest 13, StairLabelSignalTest 5, StairWatcherTest 7, StairsCannyTest 8, StairsFeedTest 5
- **text** (14) : PromptShieldTest 10, TextSanitizerTest 4
- **traverse** (66) : StairConceptsTest 6, TraversePipelineTest 8, TraverseStateTest 12, TraverseVetoTest 14, TraverseVoiceTest 18, VehicleApproachTest 8
- **usecase** (40) : DictationUseCaseTest 4, NavigationRouterTest 14, SpeechUseCaseTest 8, UseCasesTest 14
- **voicenav** (16) : VoiceDestinationParserTest 10, VoiceDestinationUseCaseTest 6

### `:app` — 944

- **(racine)** (171) : BuildInfoTest 2, CaregiverShareLinkTest 6, DistanceLayoutTest 15, FirstRunPermissionsTest 3, GoogleMapsSeedTest 3, GpuStatusLineTest 1, GpuWakeTest 4, LiveLocalOverlayTest 13, LiveMaskTransportTest 4, LiveMemoryContextTest 3, LiveStateTest 21, LiveVoiceTest 4, LocaleIndependenceTest 2, MicBusTest 5, ModelPinTest 7, ParentShareTest 5, PhotoTimingsTest 2, PrewarmTest 2, RouteBaseUrlSeedTest 3, RouteSeedTest 3, RouteStatusTextTest 3, SettingsSeedTest 20, ShareTrackingPolicyTest 3, SpeechClientsTest 6, TtsLogicTest 9, UiLogicTest 18, VoiceLanguageSettingsTest 4
- **cleanup** (15) : CaptureCleanerTest 6, CaptureScannerTest 5, CleanupDowngradeTest 4
- **depth** (11) : DepthPreprocessTest 6, ModelDownloaderTest 5
- **glasses** (71) : GlassesAnnexBTest 10, GlassesAudioPlanTest 7, GlassesFrameCollectorTest 9, GlassesMessagesTest 7, GlassesPhaseMappingTest 2, GlassesPolicyTest 8, GlassesUiStateTest 7, GlassesVideoCropTest 4, GlassesVideoDecoderTest 10, GlassesVideoLinkTest 7
- **glasses.bench** (95) : ChaineCompleteTest 8, CycleLatchTest 5, GlassesAudioRouteTest 7, GlassesFrameEncoderTest 4, GlassesFramePolicyTest 22, LinkGateTest 3, SessionPolicyTest 18, SpikeLaunchTest 9, SpikeLogTest 6, YuvConverterTest 13
- **isee** (165) : BubbleCountTest 2, CrossingWiringTest 3, FramePolicyTest 6, IseeCaptionStateTest 3, IseePlaybackTest 11, IseeRenderTest 12, IseeSceneGlassesTest 8, IseeSceneTest 17, IseeShareTest 3, IseeStatusLineTest 5, LiveProbeCadenceTest 4, LiveProbeDepthOfferTest 2, LiveProbeForegroundTest 2, LiveProbeGuidanceTest 3, LiveProbeSchedulingTest 2, LiveProbeStateTest 1, LiveProbeSteeringTest 5, LiveProbeStopTest 5, LiveProbeTextTest 23, LiveProbeWakeTest 2, OnboardingGesturesTest 6, OrbShaderTest 3, PictoStoreTest 4, ProbeVoiceSidewalkTest 6, ProbeVoiceTest 8, RevealShiftTest 4, SendingGateTest 4, SpeakableTextTest 3, TwinLiteWiringTest 8
- **isee.vm** (74) : IseeAnswersTest 17, IseeCameraCoordinatorTest 6, IseeDictationTest 12, IseeLiveDictationTest 3, IseePhotoShotTest 10, IseePictoTest 8, IseeStatusTextTest 4, IseeTranscriptTest 5, MaisonEnterOrderTest 3, NavigationHookTest 6
- **live** (68) : LiveAskForgetTest 1, LiveAskSessionTest 13, LiveFpsMeterTest 6, LiveFrameCadenceTest 5, LiveFrameDispatcherTest 7, LiveMaskDecoderTest 6, LiveNarrationTest 5, LivePositionVoiceTest 7, LivePredictionTest 8, LiveTrackFallbackTest 3, LiveWakeGuardTest 7
- **locked** (6) : LockedMicAnswerTest 6
- **media** (14) : MediaCacheStoreTest 4, Nv21JpegEncoderTest 9, PcmToMp3EncoderTest 1
- **navigation** (22) : AndroidGeocoderLabelTest 4, NavigationLauncherTest 12, VoiceNavTrackerTest 6
- **perms** (11) : PermDecisionTest 11
- **segment** (2) : SegmentPreprocessTest 2
- **speech** (66) : SpeechArbitrationTest 6, SystemTtsEngineTest 9, VoiceNavAnnouncerTest 4, VoiceNavPhrasesTest 7, VoiceNavPolicyTest 26, VoiceNavSpeakerTest 14
- **tap** (34) : AnnouncementPlayerTest 2, TapConfigTest 4, TapMachineTest 8, TapPhotoFallbackTest 8, TapSessionTest 6, TapZonesTest 6 (deux classes : TapZonesTest 4 + TapReleaseTest 2)
- **traverse** (17) : TraverseClipStoreTest 4, TraverseWiringTest 13
- **vm** (39) : AppAnalysisTest 9, AppChatTest 6, AppDiagnosticsTest 10, AppImageLoaderTest 4, AppScreenStateTest 3, AppVoiceTest 7
- **voicenav** (63) : DestinationTextFieldTest 6, GuidanceLifecycleTest 4, GuidanceNotificationTextTest 5, OffRouteBannerTest 2, ValhallaRouteLoaderTest 8, VoiceNavEngineDriverTest 4, VoiceNavSessionTest 26, VoiceNavSessionWiringTest 8

Le banc lunettes (`glasses.bench`) n'a plus de section à part : il est dans `:app` depuis la
suppression du module dédié (décision : `docs/GLASSES-DECISION.md`). Ses dix fichiers
mélangent l'ancien périmètre du spike (`GlassesFrameEncoderTest`, `SessionPolicyTest`,
`YuvConverterTest`) et les pièces ajoutées depuis (`ChaineCompleteTest`, `CycleLatchTest`,
`GlassesAudioRouteTest`, `GlassesFramePolicyTest`, `LinkGateTest`, `SpikeLaunchTest`,
`SpikeLogTest`).

Aucun test ne touche le GPU ni le réseau réel : `/live`, `/live/ws` et les autres endpoints sont
simulés (`core/src/test/…/TestHttpServer.kt`, `net/TestLiveClients.kt`, `net/NetContractHarness.kt`).

## 7. Gardes et limites connues

- `tools/check-kotlin-limits.py` (heuristique accolades, faux positifs possibles) : **vert le
  29/10** sur cet arbre (967 fichiers `.kt`/`.kts`/`.py` inspectés, 0 dépassement).
- `bash scripts/test_scripts.sh` : **101 assertions, 101 ok**.
- Voix ElevenLabs sans clé : `VoiceAnnouncer.say()` rend `InvalidKey`
  (`app/VoiceAnnouncer.kt:32-33`), mais l'annonce des positions
  (`app/AppViewModel.kt:216,225`) et les bandeaux ignorent le résultat —
  **rien ne s'affiche**. Mettre une clé ou lire le log.
- GPU Modal : **une seule carte à la fois**, toutes apps confondues ; sur cette machine,
  `~/bin/sam3-gpu status|busy` avant toute manœuvre, et ne rien redéployer.
- Coordonnées d'écran, seuils de gestes et textes d'affichage ne sont pas assertés par un test
  de bout en bout : une recette manuelle reste le seul contrôle pour eux.
