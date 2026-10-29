# État du banc d'essai lunettes — `app/…/glasses/bench/`

> **Relu le 27/10/2026** dans l'arbre `~/isee-wt/glasses-bench` (branche `glasses-bench`).
> Le banc n'est plus un module séparé : il vit dans **`:app`**, paquet
> `dev.sam3vision.app.glasses.bench`, et se lance depuis l'app elle-même.
>
> **Rien n'a été compilé ni exécuté dans cette passe** (règle de la mission : aucun `./gradlew`) :
> tout ce qui suit est **lu dans le code** (fichier:ligne) ou **compté** (commande donnée). Aucune
> paire de lunettes n'était branchée : le matériel reste « non vérifié », comme avant.
>
> Cette page remplace l'audit du module séparé, supprimé du build. La fiche SDK
> [`glasses-dat-spike.md`](glasses-dat-spike.md) porte l'audit du **bytecode** des AAR
> `mwdat-*` 1.0.1 (`javap`, relevé du 26/10/2026) : ce relevé n'a **pas** été rejoué le 27/10 et
> n'est donc pas recopié ici.
>
> **Pin du 27/10/2026** : le build utilise `mwdat = "1.0.0"`, pas 1.0.1 — c'est la seule version
> que Meta AI installe sur la paire, et 1.0.1 fait couper chaque session par les lunettes
> (« Your glasses need an update »). Raison écrite dans `gradle/libs.versions.toml`, garde dans
> `core/…/DatPinTest.kt`, constats dans `docs/META-GLASSES.md` §13.

## 1. Où vit le banc (compté le 27/10/2026)

| Partie | Emplacement | Compte |
|---|---|---|
| code du banc | `app/src/main/java/dev/sam3vision/app/glasses/bench/` | **18** fichiers `.kt`, **2 015** lignes |
| variante **debug** (démo sans matériel) | `app/src/debug/java/dev/sam3vision/app/glasses/` | **3** fichiers (`MockControl.kt`, `MockGlassesHarness.kt`, `GlassesMock.kt`) |
| variante **release** | `app/src/release/java/dev/sam3vision/app/glasses/bench/` | **1** fichier (`MockControl.kt`, muet) |
| tests JVM | `app/src/test/java/dev/sam3vision/app/glasses/bench/` | **10** fichiers / **92** `@Test` |
| test instrumenté | `app/src/androidTestDebug/java/dev/sam3vision/app/glasses/bench/` | **1** fichier / **1** test |
| asset du test | `app/src/androidTest/assets/mock_feed.mp4` | 54 964 o |

```bash
find app/src/main/java/dev/sam3vision/app/glasses/bench -name '*.kt' | wc -l                    # 18
cat app/src/main/java/dev/sam3vision/app/glasses/bench/*.kt | wc -l                             # 2015
grep -rho '@Test' app/src/test/java/dev/sam3vision/app/glasses/bench | wc -l                    # 92
```

`rg` n'est pas installé sur ce poste : un `rg` absent rend **0** sans le dire, d'où le `grep -rho`.

## 2. Comment on l'ouvre (lu dans le code)

- **Réglages → Lunettes → « Banc d'essai lunettes »** : `GlassesSection.kt:90-98` (`BenchEntry`)
  lit `LocalBenchOpener` (`:23`), fourni par `AppNav.kt:107` → `push(Route.Bench)`
  (`Route.Bench` en `:46`), rendu par `BenchScreen` (`:105`).
- **Par adb**, comme n'importe quel écran de l'app :
  `am start -n dev.sam3vision.app/.MainActivity --ez mock true --ez analyze true`. `MainActivity.kt:80-90`
  lit `EXTRA_MOCK` / `EXTRA_ANALYZE` (`SpikeLaunch.kt:20,23`) et pose la demande **hors
  composition** (`BenchEntryPoint.kt`), qu'`AppNav.kt:71-72` observe pour pousser `Route.Bench`.
  C'est ce détour qui garde la recette adb utilisable alors que le banc n'est plus une `Activity`.
- Le banc est donc un **écran Compose de l'app** : il hérite de son processus, de ses réglages et
  de sa navigation.

## 3. Ce qui est prouvé sur JVM — 92 tests, 10 fichiers

| Fichier | `@Test` | Ce que ça prouve |
|---|---:|---|
| `SessionPolicyTest` | 26 | décisions du cycle : enregistrement, erreurs de flux, verdict de frame, étranglement, `realStartAdvice` (4 faits en entrée) |
| `YuvConverterTest` | 13 | conversion YUV → ARGB pure, et `inferYuv420Layout` (`FrameTypes.kt:134`) qui **déduit** la disposition d'un buffer sans strides |
| `SpikeLaunchTest` | 9 | séquence de démarrage (`SpikeLaunch.plan`) sans `Intent` ni `Context` |
| `ChaineCompleteTest` | 8 | bout en bout de la **logique** : frame JPEG réelle → `AnalyzeUseCase` de `:core` → SAM et LLM servis par un faux serveur local → texte à dire |
| `GlassesAudioRouteTest` | 7 | choix de la sortie audio : une paire nommée passe avant toute autre sortie, un appareil qui n'est pas un puits de son n'est jamais choisi |
| `GlassesFramePolicyTest` | 11 | `frameTimestampMs` / `scaledDimensions` / `cropTo16x9`, sortis de l'encodeur pour être testables |
| `LinkGateTest` | 3 | porte d'entrée de la session : `hasLinkedDevice` (`GlassesSessionPolicy.kt:176`) refuse tout appareil qui n'est pas `CONNECTED` |
| `SpikeLogTest` | 6 | bornage du journal et erreurs tenant en une ligne |
| `CycleLatchTest` | 5 | un seul armement par cycle (ne pas relancer l'UI, la session ou la caméra à chaque émission d'état du SDK) |
| `GlassesFrameEncoderTest` | 4 | garde-fous de l'encodeur, éprouvés sur de vraies `VideoFrame` |
| **Total** | **92** | aucun `Context`, aucun Robolectric |

Le SDK n'est pas stubbé : `SessionPolicyTest` importe `StreamError` et `RegistrationState`,
`GlassesFrameEncoderTest` instancie `VideoFrame`. Ce sont des **types** utilisés sur la JVM, pas un
SDK simulé.

## 4. Ce que la variante de build garantit (lu, pas éprouvé)

`mwdat.mockdevice` est en `debugImplementation` (`app/build.gradle.kts:186`). `MockControl` a deux
implémentations du même contrat (`BenchMock`) : `available = true` côté debug, `available = false`
côté release (stub qui journalise). `BenchScreen.kt:130-131` n'affiche le bouton **Mock** que si
`available`.

Ce que cela garantit **par construction** : un APK public ne peut ni apparier ni retirer une paire
simulée, et `AutoDeviceSelector` ne peut pas prendre un mock oublié pour de vraies lunettes.
Ce que cela **ne** garantit pas : que la variante release compile — elle n'a pas été construite
dans cette passe.

## 5. Le harnais mock — exécutable, mais seulement sur un appareil

`MockGlassesHarness` (debug) enchaîne : `enable` (`:45`) → `reuseOrPair` (`:166`) →
`pairGlasses(GlassesModel.RAYBAN_META)` (`:172`) → `prepareForStreaming` (`:183`) ; la source vidéo
est la caméra arrière du téléphone (`:75`, `:89`) ou un fichier h.265 (`:106`, `:113`).
`dropSimulatedPairs` (`:144`) retire une paire simulée laissée par un run précédent — sans quoi le
mode « vraies lunettes » la verrait.

`MockGlassesTest` (`androidTestDebug`, 1 test) ferme la démonstration : il attend l'apparition de
la paire simulée puis la première frame du flux (2 délais de 20 s, `:180-181`), et vérifie que
`dropSimulatedPairs` retire bien la paire (`:147-156`). L'asset `mock_feed.mp4` a été vérifié le
27/10 au `ffprobe` : **hevc, 540×960, 3,000 s, 54 964 o** — c'est bien du h.265, comme l'exige le
harnais. Ce que la vidéo montre n'a pas été regardé.

**Aucun workflow ne l'exécute** (vérifié : `grep -n 'androidTest\|instrument' .github/workflows/*.yml`
ne rend rien). La CI a 4 jobs — `ci` (tests JVM `:core` + `:app`, `ci.yml:199-203`), `apk`
(`ci-native.yml:35`), `apk-release` (`:80`), `lint` (`:114`) — aucun n'a d'appareil. Vert ici = vert sur *cet*
appareil, jamais « vert en CI ».

## 6. Ce qui reste à prouver — et qui demande le matériel

Aucun de ces points n'a été observé, ni avant ni maintenant :

1. **Format et strides du buffer livré.** `inferYuv420Layout` (`FrameTypes.kt:134-152`) essaie trois
   conventions d'alignement et retombe sur une disposition serrée ; le code le dit lui-même
   « À VÉRIFIER » (`:148`). À trancher en premier, sur un flux réel.
2. **Cadence, latence, pertes, reprises de session.** `FRAME_RATE = 24` (`GlassesDeviceSession.kt:11-12`)
   est une **demande**, pas une mesure ; et la sortie du banc est de toute façon bornée à
   ≈ **0,67 frame/s** par `FRAME_THROTTLE_MS = 1_500L` (`GlassesSessionPolicy.kt:23`) : « 24 i/s »
   désigne l'entrée, jamais la cadence utile.
3. **L'optique** : champ, distorsion, exposition, rolling shutter. La résolution de flux est un
   format, pas une mesure d'image.
4. **Le routage audio réellement entendu dans les lunettes.** `GlassesVoice.routeToGlasses()`
   (`GlassesVoice.kt:74`) n'a jamais tourné sur une paire ; un refus de la plateforme retombe
   silencieusement sur le haut-parleur. Côté production, `GlassesVoiceRoute` branche la même
   décision sur `SpeechPlayer` depuis le 29/10 : compilé et testé en JVM, **pas encore entendu
   dans la paire** (la ligne `GlassesVoice` de logcat dira la sortie réellement choisie).
5. **Les verrous Meta** : appairage réel, Developer Mode, firmware, permissions de l'app Meta AI.
   `GlassesRealCheck.kt` ne fait que **lire** ces faits et les dire en français.
6. **Thermique et batterie** : rien sur la montée en température, l'autonomie, ni la mise en pause
   quand les lunettes sont retirées.
7. **`Map` du harnais** : il n'apparie qu'un modèle, `RAYBAN_META` en dur (`:172`), alors que le SDK
   simulé en expose plusieurs (dont un modèle à écran). La question « quelle paire ? » n'est donc
   pas instruite par le banc — une ligne de code suffirait, pas du matériel.
8. **Le test instrumenté** n'est exécuté nulle part ailleurs que sur un appareil branché à la main.

## 7. Écarts vérifiés le 27/10

- **Garde de taille** : `python3 tools/check-kotlin-limits.py` → **515** fichiers inspectés,
  **aucun dépassement** (le dépassement `AppNav.kt:64` relevé le 27/10 a été corrigé).
  Aucun fichier du banc n'est en cause.
- **Plus de duplication avec `:app`** : `GlassesFrameEncoder.kt:6-7` importe `MAX_IMAGE_EDGE` et
  `JPEG_QUALITY` de `:app` (`ImageSource.kt:17-18`) au lieu de les recopier — la duplication
  signalée avant l'intégration a disparu.
- **Noms hérités** : `SpikeLaunch.kt`, `SpikeLog.kt`, `SpikeLogTest.kt`, `SpikeLaunchTest.kt`
  gardent leur nom d'origine. Cosmétique, sans effet ; à renommer seulement si on touche au fichier.

## 8. Ce que cette page ne dit pas

- Aucun build, aucun test n'a tourné dans cette passe : « compile » et « vert » ne sont pas
  constatés ici.
- L'audit bytecode du SDK (AAR 1.0.1, `javap`) date du 26/10 et n'a pas été rejoué ; il n'est pas
  recopié ici pour ne pas faire passer un relevé ancien pour une vérification du jour.
- Rien n'a été mesuré sur un téléphone : ni durée, ni débit, ni image.