# TESTING — iSee (`:core`, `:app`, `:glasses-spike`)

Relu le **26/10 après 19 h** dans cet arbre (commit unique de ce dépôt). Les
comptages de tests ci-dessous ont été **refaits et exécutés** ; les chemins cités sont vérifiés.
**Aucun résultat d'exécution sur un appareil (téléphone ou émulateur) n'existe dans cet
arbre** : tout ce qui est décrit comme recette est une *procédure*, pas une preuve. La liste de
ce qui n'a pas été rejoué est au §4.

## 1. Compter les tests (cet arbre, 26/10)

```bash
grep -rho '@Test' core/src/test --include='*.kt' | wc -l               # 663
grep -rho '@Test' app/src/test --include='*.kt' | wc -l                # 305
grep -rho '@Test' glasses-spike/src/test --include='*.kt' | wc -l      # 36
grep -rho '@Test' app/src/androidTest --include='*.kt' | wc -l         # 6
grep -rho '@Test' glasses-spike/src/androidTest --include='*.kt' | wc -l  # 1
```

| | Fichiers `src/test` | Classes `@Test` | Annotations `@Test` |
|---|---|---|---|
| `:core` | 92 (dont 6 aides) | 86 | **663** |
| `:app` | 51 (dont 2 aides) | 50 | **305** |
| `:glasses-spike` | 3 | 3 | **36** |
| **Total JVM** | **146** | **139** | **1 004** |
| `:app` `androidTest` | 1 | 1 | **6** (instrumentés) |
| `:glasses-spike` `androidTest` | 1 | 1 | **1** (instrumenté) |

- **1 004 tests JVM** au total, dont **663** dans `:core`, **305** dans `:app`, **36** dans
  `glasses-spike`. Les **7 tests instrumentés** (6 + 1) sont hors de ce total.
- Les fichiers d'aide (pas des classes de test) : `:core` — `TestHttpServer.kt`,
  `net/NetContractHarness.kt`, `net/TestLiveClients.kt`, `live/FrameBench.kt`,
  `mask/M1Fixtures.kt`, `mask/JvmAllocations.kt` ; `:app` — `ihear/vm/FakeMessageDao.kt`,
  `vm/Fakes.kt`.

### Ce que Gradle exécute réellement

```bash
export JAVA_HOME=<un JDK 17 qui a javac>
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest :glasses-spike:testDebugUnitTest \
  --offline --rerun-tasks
# rapports : <module>/build/test-results/testDebugUnitTest/*.xml
```

Relevé du 26/10 après 19 h, sur r630 : **`:core` 663 / 0 échec / 0 ignoré**, **`:app` 305 / 0 /
0**, **`:glasses-spike` 36 / 0 / 0** — soit **1 004 exécutés**. Les annotations `@Test` et les
tests exécutés coïncident (aucun test ignoré recensé).

## 2. Commandes exactes

```bash
export JAVA_HOME=<un JDK 17 qui a javac> ANDROID_HOME=$HOME/android-sdk
./gradlew :core:testDebugUnitTest :app:testDebugUnitTest --offline   # tests JVM
./gradlew :glasses-spike:assembleDebug :glasses-spike:testDebugUnitTest --offline
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
- les **recettes lunettes** (`app/…/glasses/`, `glasses-spike/`).

## 5. Tests instrumentés (module de nettoyage, 6 tests)

`app/src/androidTest/…/cleanup/CaptureCleanupTest.kt` (6 tests) et
`glasses-spike/src/androidTest/…/MockGlassesTest.kt` (1 test). Commandes (émulateur) :

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --offline
EMULATOR_SERIAL=emulator-5554 bash scripts/install-emulator.sh   # installe l'APK
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w \
  -e class dev.sam3vision.app.cleanup.CaptureCleanupTest \
  dev.sam3vision.app.test/androidx.test.runner.AndroidJUnitRunner
```

Périmètre couvert par ces tests : capture de l'app supprimée · fichier hors périmètre intact ·
trace MediaStore dé-synchronisée oubliée sans erreur · capture en cours d'écriture reportée ·
périmètre limité au stockage privé de l'app · job horaire unique et sans réseau.
**Non exécutés ici** (voir §4).

## 6. Tests JVM par domaine (comptage du 26/10)

Une entrée par fichier `*Test.kt`, avec son nombre d'annotations `@Test` (un seul fichier porte deux classes : `TapZonesTest.kt`).

### `:core` — 663

- **audio** (8) : SentenceChunkerTest 5, WavTest 3
- **distance** (37) : DistanceMeterTest 12, DistanceTextEnglishTest 6, DistanceTextTest 7, KnownSizesTest 5, LensSpecTest 7
- **gesture** (17) : IhearGestureBrainTest 3, LongPressTriggerTest 10, TapOrHoldTest 4
- **glasses** (6) : CaptureGestureTest 6
- **iseedesign** (23) : DissolveTest 10, IseeSceneTest 13
- **live** (160) : CaptureStampTest 3, FrameGateEraTest 4, FrameGateTest 5, FrameRateTest 4, FrameRosterTest 7, LatestFrameSlotTest 5, LiveFrameBudgetTest 1, LiveMemoryFlowTest 2, LiveNarratorTest 7, LivePromptsTest 8, LiveSessionStopTest 3, LiveSessionTest 19, LiveStatsTest 5, LiveTimelineTest 10, LiveTimingsTest 15, LiveWireGuardTest 4, PipelineGateTest 8, PositionAnnouncerTest 8, PositionTextEnglishTest 7, PositionTextTest 10, ScreenTimingsTest 4, TimelineTextTest 7, TrackRunnerTest 14
- **mask** (44) : MaskColorizePerfTest 4, MaskCoreTest 20, MaskRobustnessTest 20
- **model** (3) : ObjectLimitsTest 3
- **net** (229) : ElevenLabsSttClientTest 5, ElevenLabsTtsClientTest 7, FallbackSpeechClientTest 4, HttpErrorTest 4, HttpRequestBuilderTest 10, LifecycleContractTest 10, LiveClientFactoryTest 3, LiveClientTest 16, LiveContractTest 11, LiveNarrationClientTest 4, LlmClientTest 9, LlmContractTest 11, LlmPromptsTest 5, LlmRequestLanguageTest 3, MetaAsrClientTest 8, MetaAsrContractTest 8, SamClientTest 6, SamContractTest 12, SamLifecycleTest 4, TrackClientTest 12, TrackContractTest 10, VideoClientTest 9, VideoContractTest 10, VoiceContractTest 17, WsLiveClientTest 23, WsLiveContractTest 8
- **predict** (39) : BlockShiftTest 7, BoxPredictorTest 10, BoxTest 10, MotionModelTest 7, ObjectAssociatorTest 5
- **settings** (68) : PrivateFileSettingsStoreTest 17, SamRegionTest 8, SettingsAnnounceTest 4, SettingsAsrTest 2, SettingsCipherTest 9, SettingsCodecTest 6, SettingsIntroTest 6, SettingsSecretsTest 9, SettingsStoreParityTest 4, SettingsTtsTest 3
- **speech** (5) : SpeechLanguageTest 5
- **text** (4) : TextSanitizerTest 4
- **usecase** (22) : DictationUseCaseTest 4, SpeechUseCaseTest 8, UseCasesTest 10

### `:app` — 305

- **(racine)** (77) : BuildInfoTest 2, DistanceLayoutTest 15, FirstRunPermissionsTest 3, LiveMemoryContextTest 3, LiveStateTest 16, LiveVoiceTest 4, LocaleIndependenceTest 2, MicBusTest 5, SettingsSeedTest 4, TtsLogicTest 6, UiLogicTest 13, VoiceLanguageSettingsTest 4
- **cleanup** (5) : CaptureScannerTest 5
- **ihear** (88) : BubblePaletteTest 3, FramePolicyTest 6, IhearDistancesTest 5, IhearPhotoContextTest 8, IseeRenderTest 12, IseeSceneTest 11, LiveProbeCadenceTest 4, LiveProbeSchedulingTest 2, LiveProbeTextTest 21, RevealShiftTest 4, SpeakableTextTest 3, SwipeStepTest 9
- **ihear.vm** (42) : IhearAnswersTest 6, IhearCameraCoordinatorTest 6, IhearDictationTest 9, IhearPhotoShotTest 6, IhearStatusTextTest 9, IhearTranscriptTest 6
- **live** (42) : LiveAskForgetTest 1, LiveAskSessionTest 7, LiveFpsMeterTest 6, LiveFrameCadenceTest 5, LiveMaskDecoderTest 6, LiveNarrationTest 5, LivePositionVoiceTest 5, LiveWakeGuardTest 7
- **media** (4) : MediaCacheStoreTest 3, PcmToMp3EncoderTest 1
- **tap** (14) : TapMachineTest 8, TapZonesTest.kt 6 (deux classes : TapZonesTest 4 + TapReleaseTest 2)
- **vm** (33) : AppAnalysisTest 6, AppChatTest 6, AppDiagnosticsTest 7, AppImageLoaderTest 4, AppScreenStateTest 3, AppVoiceTest 7

### `:glasses-spike` — 36

- **(racine)** (36) : GlassesFrameEncoderTest 6, SessionPolicyTest 20, YuvConverterTest 10

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