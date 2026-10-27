# TESTING — iSee (`:core`, `:app`)

Relu le **27/10** dans cet arbre (`glasses-bench`, branche `glasses-bench`). Les comptages de
tests ont été **refaits à la main le 27/10** ; ils sont **statiques** (annotations `@Test`), aucun
`gradlew` n'a été lancé pour cette passe. Les chemins cités sont vérifiés un par un.
**Aucun résultat d'exécution sur un appareil (téléphone ou émulateur) n'existe dans cet
arbre** : tout ce qui est décrit comme recette est une *procédure*, pas une preuve. La liste de
ce qui n'a pas été rejoué est au §4.

## 1. Compter les tests (cet arbre, 27/10)

`rg` n'est pas installé sur cette machine : le comptage se fait avec `grep`, à l'identique.
`grep -rho '@Test' <dir> --include='*.kt' | wc -l` compte les **occurrences** de l'annotation
(une par test, `-o` évite de compter deux fois une ligne qui en porterait deux), pas les fichiers.

```bash
grep -rho '@Test' core/src/test --include='*.kt' | wc -l                    # 907
grep -rho '@Test' app/src/test --include='*.kt' | wc -l                     # 466
grep -rho '@Test' app/src/test/java/dev/sam3vision/app/glasses/bench --include='*.kt' | wc -l   # 84
grep -rho '@Test' app/src/androidTest --include='*.kt' | wc -l              # 6
grep -rho '@Test' app/src/androidTestDebug --include='*.kt' | wc -l         # 1
```

| | Fichiers `src/test` | Classes `@Test` | Annotations `@Test` |
|---|---|---|---|
| `:core` | 126 (dont 12 aides) | 114 | **907** |
| `:app` | 71 (dont 2 aides) | 69 | **466** |
| **Total JVM** | **197** | **183** | **1 373** |
| `:app` `androidTest` | 1 | 1 | **6** (instrumentés) |
| `:app` `androidTestDebug` (banc) | 1 | 1 | **1** (instrumenté) |

- **1 373 tests JVM** au total, dont **907** dans `:core` et **466** dans `:app`. Dans `:app`, le
  banc lunettes pèse **84** (`glasses/bench/`) et le reste du paquet `glasses` **26**. Les
  **7 tests instrumentés** (6 + 1) sont hors de ce total.
- Le banc lunettes **n'est plus un module** : ses tests JVM sont dans
  `app/src/test/…/glasses/bench/` et comptent donc dans `:app`, sans ligne à part.
- Les fichiers d'aide (pas des classes de test) : `:core` — `TestHttpServer.kt`,
  `net/NetContractHarness.kt`, `net/TestLiveClients.kt`, `live/FrameBench.kt`,
  `mask/M1Fixtures.kt`, `mask/MaskCodecFixtures.kt`, `mask/MaskFixtures.kt`,
  `mask/MaskGoldenData.kt`, `mask/JvmAllocations.kt`, `keyframe/KeyframeScene.kt`,
  `keyframe/KeyframeSim.kt`, `motion/MotionScenes.kt` ; `:app` — `ihear/vm/FakeMessageDao.kt`,
  `vm/Fakes.kt`.

### Ce que Gradle exécute réellement

```bash
export JAVA_HOME=<un JDK 17 qui a javac>
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest --offline --rerun-tasks
# rapports : <module>/build/test-results/testDebugUnitTest/*.xml
```

Relevé du 26/10 après 19 h, sur r630 : **`:core` 663 / 0 échec / 0 ignoré**, **`:app` 305 / 0 / 0**
et, à l'époque, **le banc lunettes 36 / 0 / 0** — **1 004 exécutés** au total. Ce relevé décrit
l'arbre **d'avant** la fusion du banc dans `:app` : il **n'est plus comparable** aux comptages du
§1. Aucune exécution Gradle n'a été refaite dans cette passe (aucun `gradlew` appelé ici), donc les
**1 373** sont des comptages statiques d'annotations, pas le résultat d'un run.

## 2. Commandes exactes

```bash
export JAVA_HOME=<un JDK 17 qui a javac> ANDROID_HOME=$HOME/android-sdk
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest --offline   # tests JVM
./gradlew :app:assembleDebug --offline          # l'APK, banc lunettes inclus (plus de module dédié)
bash scripts/build.sh                            # assembleDebug --offline (GRADLE_TIMEOUT, 600 s par défaut)
python3 tools/check-kotlin-limits.py             # fichier < 500 l, fonction < 50 l
bash scripts/test_scripts.sh                     # 75 assertions
python3 -m unittest tools/test_check_kotlin_limits.py    # 28 tests
python3 .github/ci-budget/check_timeouts.py      # 4 workflows
```

Le dépôt **n'épingle aucun JDK** : `gradle.properties` ne porte que
`org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8` (`gradle.properties:15`) et un commentaire qui
renvoie à `JAVA_HOME`. Ce qui est obligatoire, c'est un JDK **qui fournit `javac`** — un JRE
seul ne suffit pas. Bytecode et `-jvm-target` sont fixés à **17** (`app/build.gradle.kts`,
`core/build.gradle.kts`).

## 3. Recette émulateur

Le script `scripts/install-emulator.sh` fait la chaîne complète — reconstruit l'APK s'il
manque, l'installe, accorde `CAMERA`, lance l'app, prend une capture d'écran et en affiche le
chemin :

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
`2` = occupé), `--reinstall`, `--wait S`, `--init`, et `--hold 30m` pour **figer** le téléphone le temps
de tester une branche de mission. Le script ne prend jamais le verrou d'un autre agent : téléphone
occupé = sortie `2`, le tour suivant réessaie.

## 4. Ce qui n'a jamais été rejoué sur appareil

Aucun des éléments suivants n'a d'exécution attestée dans cet arbre. Ils sont **à rejouer**
avant d'être présentés comme validés :

- les **7 tests instrumentés** (§5) — jamais lancés (aucun émulateur construit pour eux) ;
- la **recette manuelle du mode tap** (vidéo → SAM 3.1, écran « Envoyé / Échec ») ;
- la **recette du direct WebSocket** (pastille « WebSocket » / « KO », `X ips`, question
  « qu'est-ce qui était à gauche il y a 5 s ? » avec le journal de pistes) ;
- la **recette voix ElevenLabs** (« 🔊 Tester la voix », lecture automatique des réponses) ;
- la **recette de dictée / appui long** (Meta Muse, repli ElevenLabs Scribe) ;
- la **recette d'intro d'accueil** (rejouée à chaque ouverture froide) ;
- la **vérification de bascule de région EU/US** (« Tester SAM ») ;
- les **recettes lunettes** (`app/…/glasses/` et le banc `app/…/glasses/bench/`, `Route.Bench`).

## 5. Tests instrumentés (nettoyage 6, banc 1)

`app/src/androidTest/…/cleanup/CaptureCleanupTest.kt` (6 tests) et
`app/src/androidTestDebug/…/glasses/bench/MockGlassesTest.kt` (1 test — source set
`androidTestDebug`, compilé pour la variante debug seulement et lancé par la **même** tâche
`:app:connectedDebugAndroidTest`). Commandes (émulateur) :

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --offline
./gradlew :app:connectedDebugAndroidTest --offline               # les 7 tests instrumentés
EMULATOR_SERIAL=emulator-5554 bash scripts/install-emulator.sh   # installe l'APK
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w \
  -e class dev.sam3vision.app.cleanup.CaptureCleanupTest \
  dev.sam3vision.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am instrument -w \
  -e class dev.sam3vision.app.glasses.bench.MockGlassesTest \
  dev.sam3vision.app.test/androidx.test.runner.AndroidJUnitRunner
```

(Le runner est `androidx.test.runner.AndroidJUnitRunner`, `app/build.gradle.kts:118`.)

Le banc n'a plus d'écran ni d'`Activity` à lui : c'est `Route.Bench` d'iSee
(Réglages → Lunettes → « Banc d'essai lunettes »), lançable par adb sans toucher l'écran —
`adb shell am start -n dev.sam3vision.app/.MainActivity --ez mock true --ez analyze true`.

Périmètre couvert par ces tests : capture de l'app supprimée · fichier hors périmètre intact ·
trace MediaStore dé-synchronisée oubliée sans erreur · capture en cours d'écriture reportée ·
périmètre limité au stockage privé de l'app · job horaire unique et sans réseau.
**Non exécutés ici** (voir §4).

## 6. Tests JVM par domaine (comptage du 27/10)

Une entrée par fichier `*Test.kt`, avec son nombre d'annotations `@Test` (un seul fichier porte deux classes : `TapZonesTest.kt`).

### `:core` — 905

- **audio** (13) : SentenceChunkerTest 5, TempWavStoreTest 5, WavTest 3
- **distance** (87) : DistanceFusionTest 9, DistanceMeterTest 12, DistanceTextEnglishTest 6, DistanceTextTest 7, GroundPlaneTest 17, KnownSizesTest 5, LensSpecTest 7, OpticsTest 24
- **gesture** (17) : IhearGestureBrainTest 3, LongPressTriggerTest 10, TapOrHoldTest 4
- **glasses** (6) : CaptureGestureTest 6
- **haptics** (8) : WaveformTest 8
- **iseedesign** (19) : DissolveTest 10, IseeSceneTest 9
- **keyframe** (37) : KeyframeSchedulerTest 21, KeyframeSimTest 9, RequestBudgetTest 7
- **live** (208) : CaptureStampTest 3, FrameGateEraTest 4, FrameGateTest 5, FrameRateTest 4, FrameRosterTest 7, LatencyBenchTest 1, LatencyProfileTest 11, LatencyTraceTest 12, LatestFrameSlotTest 5, LiveCadenceSimTest 1, LiveFrameBudgetTest 1, LiveMemoryFlowTest 2, LiveNarratorTest 7, LivePipelineTest 7, LivePromptsTest 8, LivePumpTest 4, LiveSessionStopTest 3, LiveSessionTest 19, LiveStatsTest 6, LiveTimelineTest 10, LiveTimingsTest 15, LiveWireGuardTest 4, PositionAnnouncerTest 8, PositionTextEnglishTest 7, PositionTextTest 10, ScreenTimingsTest 4, TimelineTextTest 7, TrackRunnerTest 14, UploadLadderTest 19
- **local** (27) : LocalOverlayEngineTest 24, LocalOverlaySimTest 3
- **mask** (97) : MaskBenchTest 2, MaskColorizePerfTest 4, MaskCoreTest 20, MaskDecodeTest 7, MaskGoldenTest 2, MaskMotionTest 6, MaskPolyTest 7, MaskPropagatorBenchTest 1, MaskPropagatorTest 13, MaskRleTest 8, MaskRobustnessTest 20, MaskUpscaleTest 7
- **model** (3) : ObjectLimitsTest 3
- **motion** (9) : MotionBenchTest 1, MotionSourceTest 8
- **net** (225) : ElevenLabsSttClientTest 5, ElevenLabsTtsClientTest 7, FallbackSpeechClientTest 4, HttpErrorTest 4, HttpRequestBuilderTest 10, LifecycleContractTest 10, LiveClientFactoryTest 3, LiveClientTest 16, LiveContractTest 11, LiveNarrationClientTest 4, LlmClientTest 9, LlmContractTest 11, LlmInjectionTest 5, LlmPromptsTest 6, LlmRequestLanguageTest 3, MetaAsrClientTest 8, MetaAsrContractTest 8, SamClientTest 6, SamContractTest 12, SamLifecycleTest 4, TrackClientTest 12, VideoClientTest 9, VideoContractTest 10, VoiceContractTest 17, WsLiveClientTest 23, WsLiveContractTest 8
- **predict** (39) : BlockShiftTest 7, BoxPredictorTest 10, BoxTest 10, MotionModelTest 7, ObjectAssociatorTest 5
- **settings** (69) : PrivateFileSettingsStoreTest 17, SamRegionTest 8, SettingsAnnounceTest 4, SettingsAsrTest 2, SettingsCipherTest 9, SettingsCodecTest 7, SettingsIntroTest 6, SettingsSecretsTest 9, SettingsStoreParityTest 4, SettingsTtsTest 3
- **speech** (5) : SpeechLanguageTest 5
- **text** (14) : PromptShieldTest 10, TextSanitizerTest 4
- **usecase** (22) : DictationUseCaseTest 4, SpeechUseCaseTest 8, UseCasesTest 10

### `:app` — 458

- **(racine)** (100) : BuildInfoTest 2, DistanceLayoutTest 15, FirstRunPermissionsTest 3, LiveLocalOverlayTest 13, LiveMaskTransportTest 4, LiveMemoryContextTest 3, LiveStateTest 16, LiveVoiceTest 4, LocaleIndependenceTest 2, MicBusTest 5, SettingsSeedTest 4, SpeechClientsTest 6, TtsLogicTest 6, UiLogicTest 13, VoiceLanguageSettingsTest 4
- **cleanup** (11) : CaptureCleanerTest 6, CaptureScannerTest 5
- **glasses** (26) : GlassesAudioPlanTest 7, GlassesMessagesTest 6, GlassesPhaseMappingTest 2, GlassesPolicyTest 4, GlassesUiStateTest 7
- **glasses.bench** (84) : ChaineCompleteTest 8, CycleLatchTest 5, FramePolicyTest 6, GlassesAudioRouteTest 7, GlassesFrameEncoderTest 4, SessionPolicyTest 26, SpikeLaunchTest 9, SpikeLogTest 6, YuvConverterTest 13
- **ihear** (86) : FramePolicyTest 6, IhearDistancesTest 5, IhearPhotoContextTest 8, IseeRenderTest 12, IseeSceneTest 11, LiveProbeCadenceTest 4, LiveProbeSchedulingTest 2, LiveProbeTextTest 21, OnboardingGesturesTest 8, RevealShiftTest 4, SpeakableTextTest 3, BubbleCountTest 2
- **ihear.vm** (41) : IhearAnswersTest 6, IhearCameraCoordinatorTest 6, IhearDictationTest 9, IhearPhotoShotTest 6, IhearStatusTextTest 9, IhearTranscriptTest 5
- **live** (50) : LiveAskForgetTest 1, LiveAskSessionTest 7, LiveFpsMeterTest 6, LiveFrameCadenceTest 5, LiveMaskDecoderTest 6, LiveNarrationTest 5, LivePositionVoiceTest 5, LivePredictionTest 8, LiveWakeGuardTest 7
- **media** (13) : MediaCacheStoreTest 3, Nv21JpegEncoderTest 9, PcmToMp3EncoderTest 1
- **tap** (14) : TapMachineTest 8, TapZonesTest.kt 6 (deux classes : TapZonesTest 4 + TapReleaseTest 2)
- **vm** (33) : AppAnalysisTest 6, AppChatTest 6, AppDiagnosticsTest 7, AppImageLoaderTest 4, AppScreenStateTest 3, AppVoiceTest 7

Le banc lunettes (`glasses.bench`) n'a plus de section à part : il est dans `:app` depuis la
suppression du module dédié (décision : `docs/GLASSES-DECISION.md`). Ses neuf fichiers
mélangent l'ancien périmètre du spike (`GlassesFrameEncoderTest`, `SessionPolicyTest`,
`YuvConverterTest`) et les pièces ajoutées depuis (`ChaineCompleteTest`, `CycleLatchTest`,
`FramePolicyTest`, `GlassesAudioRouteTest`, `SpikeLaunchTest`, `SpikeLogTest`).

Aucun test ne touche le GPU ni le réseau réel : `/live`, `/live/ws` et les autres endpoints sont
simulés (`core/src/test/…/TestHttpServer.kt`, `net/TestLiveClients.kt`, `net/NetContractHarness.kt`).

## 7. Gardes et limites connues

- `tools/check-kotlin-limits.py` (heuristique accolades, faux positifs possibles) : **vert le
  26/10** sur cet arbre (373 fichiers `.kt`/`.kts`/`.py` inspectés, 0 dépassement).
- `bash scripts/test_scripts.sh` : **53 assertions, 53 ok**.
- GPU Modal : **une seule carte à la fois**, toutes apps confondues ; sur cette machine,
  `~/bin/sam3-gpu status|busy` avant toute manœuvre, et ne rien redéployer.
- Coordonnées d'écran, seuils de gestes et textes d'affichage ne sont pas assertés par un test
  de bout en bout : une recette manuelle reste le seul contrôle pour eux.