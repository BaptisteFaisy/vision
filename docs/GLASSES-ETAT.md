# État du scaffold lunettes — audit `glasses-spike/` contre la fiche

> **Relu le 26/10/2026** dans l'arbre de la mission M67 (`mission/m067`, base `master`).
> Deux sources seulement, comme la fiche : **le code**, cité `fichier:ligne` (c'est l'autorité), et le
> **bytecode** des AAR `com.meta.wearable:mwdat-*` **1.0.1** du cache Gradle local, relu au `javap`
> (`javap` n'est pas dans le `PATH` de ce poste : celui du JDK 17 du cache Gradle a servi).
> **Aucune paire de lunettes n'était disponible ici** : tout le matériel reste « non vérifié ».
>
> Référence comparée : [`glasses-dat-spike.md`](glasses-dat-spike.md) (fiche) et
> [`glasses-spike-ci.md`](glasses-spike-ci.md) (CI). Le module est **identique bit à bit** sur
> l'import `790ce7f` et sur le `master` courant : l'audit du code vaut pour les deux.
>
> **Trois écarts prouvés ont été corrigés dans cette PR** (détail en fin de § 4) : le commentaire
> « trois boutons » de `MainActivity.kt`, et deux passages périmés de la fiche (§ 0 chiffres du
> module, § 5 `color-format = 19`).

## Ce que le bytecode prouve

`javap -p -c` sur les `classes.jar` extraits des trois AAR : rejouable hors ligne, sans réseau et
sans lunettes. Rejeu :

```bash
W=/tmp/mwdat && mkdir -p "$W"
for m in core camera mockdevice; do
  unzip -o -q "$(find ~/.gradle/caches/modules-2/files-2.1/com.meta.wearable/mwdat-$m \
      -name '*.aar' | head -1)" -d "$W/$m"
done
javap -p -c -classpath "$W/camera/classes.jar" \
  'com.meta.wearable.dat.camera.internal.codec.types.VideoFormat$Companion'
```

| Élément vérifié | Constat (bytecode) |
|---|---|
| `VideoFrame` | `(ByteBuffer, int width, int height, long presentationTimeUs, boolean isCompressed, boolean isCodecConfig)` + 6 accesseurs. **Ni format, ni rowStride, ni pixelStride** → c'est ce qui justifie `inferYuv420Layout` (`FrameTypes.kt:134-151`) |
| `StreamConfiguration` | 4 paramètres ; le constructeur de défauts substitue **`null` / `MEDIUM` / `24` / `false`** (masque `15`) — la configuration explicite du spike (`GlassesDeviceSession.kt:44-47`) ne change donc rien aux défauts du SDK |
| `VideoFormat.getDefaultFormat()` | **H.265, 504×896, 30 i/s**, `iFrameInterval = 3`, `bitRate = 750 000`, **`colorFormat = 19`** |
| `StreamImpl.createVideoFormat()` | copie ce format avec un masque `113` (`0b1110001`) : codec, `iFrameInterval`, `bitRate` **et `colorFormat` sont conservés**, seuls largeur, hauteur et cadence sont résolus depuis la `StreamConfiguration` |
| `VideoDecoder` | construit son `MediaFormat` par `createVideoFormat(codec.type, width, height)` puis `setInteger("frame-format", …)`, `"i-frame-interval"`, `"bitrate"` et **`setInteger("color-format", getColorFormat())`** → la constante demandée au décodeur est bien **19** |
| sortie du décodeur | `VideoDecoder.setOnDecodedVideoAvailable(Function4<ByteBuffer, Int width, Int height, Long ptsUs, Unit>)` : c'est la source des `VideoFrame` brutes livrées à l'app ; **aucun stride n'y est transmis** |
| `Stream` | `getState()`, `getVideoStream()`, `getAudioStream()`, `getErrorStream()`, `start()` → `DatResult<Unit, StreamError>`, `capturePhoto()` (suspend), `stop()` |
| `Camera` | `getState()`, `getStream()`, `getPhoto()`, `stop()` ; `addCamera`/`removeCamera` sont des **extensions** de `SessionCameraExtensionsKt` → `DatResult<…, DeviceSessionError>` |
| `DeviceSession` | `getState()`, `getErrors()`, **`start()` et `stop()` sont `void`** (non suspendus), le reste est `internal` sous `$fbandroid_java_com_meta_wearable_dat_core_core` |
| énumérations | `RegistrationState` **5**, `StreamError` **8** (dont `CRITICAL_STREAM_ERROR`), `DeviceSessionState` **6**, `StreamState` **7**, `CameraState` **4**, `VideoQuality` **3** |
| `DatResult` / `DatError` | `fold`, `onFailure(error, cause)`, `getOrNull`, `errorOrNull`, `getOrThrow`, `map`… ; `DatError` = `description` + `getLocalizedDescription(Context)` → seule cette dernière exige un `Context` (d'où le repli de `GlassesRuntime.describe`, `GlassesSessionRuntime.kt:76`) |
| `minSdk` des AAR | **29** dans les trois manifestes binaires (`strings … | grep minSdkVersion`), `targetSdkVersion 33` → c'est bien ce qui impose `minSdk = 29` au module |
| tailles des AAR 1.0.1 | `mwdat-core` **8 979 777 o**, `mwdat-camera` **784 799 o**, `mwdat-mockdevice` **871 060 o** = **10 635 636 o ≈ 10,64 Mo** (10,14 Mio) |
| modèles simulables | `GlassesModel` : `RAYBAN_META`, **`META_RAYBAN_DISPLAY`**, `OAKLEY_META_HSTN`, `OAKLEY_META_VANGUARD`, `RAYBAN_META_OPTICS`, `META_GLASSES` |
| API du mock | `MockDeviceKit` : `enable(MockDeviceKitConfig)`, `isEnabled`, `disable()`, `pairGlasses(GlassesModel)`, `getPairedDevices()`, `unpairDevice`, `simulateRegistrationOutcome(boolean)`, `startTestServer(int)`, `stopTestServer()` |

Ce que ce tableau **change par rapport à la fiche** : la fiche § 5 classait `color-format = 19` en
« non vérifié dans le SDK ici ». C'est faux sur ce cache Gradle : la constante est demandée par le
SDK lui-même (`VideoDecoder` → `"color-format"` = 19 = `COLOR_FormatYUV420Planar`, I420). Ce qui
reste ouvert n'est pas la constante demandée, c'est **le format effectivement livré** (MediaCodec
peut refuser une couleur) et **les strides/padding du buffer** — et ceux-là, aucun bytecode ne les
expose, seul le matériel peut les trancher.

## Ce que le mock prouve

Le harnais (`MockGlassesHarness.kt`) est la seule chose exécutable sans matériel, et il prouve
**la chaîne DAT**, pas les lunettes :

- **`MockDeviceKit.enable`** + **`pairGlasses(GlassesModel.RAYBAN_META)`** (`MockGlassesHarness.kt:137`)
  enregistrent un appareil simulé et accordent les permissions simulées (`mockKit.enable(MockDeviceKitConfig())`) ;
- **allumer / porter / déplier** (`:148-165`, `powerOn` → `don` → `unfold`) : sans ces trois états, le
  SDK simulé refuse de streamer ;
- **la source vidéo** : caméra arrière du téléphone (`setCameraFeed(CameraFacing.BACK)`, `:71-94`) ou
  **fichier vidéo h.265** (`setCameraFeed(uri)`, `:100-115`) ;
- **la chaîne s'ouvre** : session (`Wearables.createSession(AutoDeviceSelector())`) → `STARTED` →
  `addCamera` → `stream.start()` → `videoStream` émet, sans qu'aucune exception ne sorte du harnais —
  toutes les erreurs passent dans le journal, jamais dans une exception non capturée (`enable`,
  `usePhoneCameraFeed`, `useVideoFeed` rendent un `Boolean` et journalisent le motif) ;
- **`MockGlassesTest`** (instrumentation, 1 test) ferme la démonstration : il attend une première
  frame du flux simulé (2 délais de 20 s) sur un appareil réel ou un émulateur.

Côté JVM, **56 tests** (`src/test`, 6 fichiers) prouvent la **couche de décision**, sans aucun stub du
SDK : c'est elle qui porte tout le raisonnement (états d'enregistrement, erreurs de flux, verdict de
frame, étranglement, horodatage, réduction, journal, conversion YUV). `python3 tools/check-kotlin-limits.py`
→ **372 fichiers inspectés, OK — aucun dépassement** (module couvert comme le reste du dépôt).

## Ce que le mock ne prouve pas

À lire avant toute phrase du type « ça marche avec les lunettes ». **Rien de cette liste n'a été
observé sur matériel.**

- **Latence et débit Bluetooth** : jamais mesurés. La cadence **24 i/s est une demande**
  (`GlassesDeviceSession.kt:12`, `:44-47`), égale au défaut du SDK : aucune cadence constatée n'existe.
- **L'optique** : champ, distorsion, exposition, rolling shutter — le mock reçoit des pixels du
  téléphone ou d'un fichier, pas d'un capteur de lunettes. La résolution `MEDIUM` (504×896) est un
  **format de flux**, pas une mesure d'image.
- **Le routage audio vers les lunettes** : `GlassesVoice.routeToGlasses()` (`:56-73`) et `applyRoute`
  (`:194-224`) n'ont jamais tourné sur une paire réelle ; `MODIFY_AUDIO_SETTINGS` est déclarée
  (`AndroidManifest.xml:12`), un refus de la plateforme retombe silencieusement sur le haut-parleur.
- **Les verrous Meta** : appairage réel, Developer Mode, firmware, permissions de l'app Meta AI.
- **Le thermique et la batterie** : ni la montée en température, ni l'autonomie, ni la mise en pause du
  flux quand les lunettes sont retirées (les erreurs `THERMAL_HOT`, `BATTERY_LOW`, `HINGE_CLOSED`
  sont traitées comme transitoires, `GlassesSessionPolicy.kt:120-124` — c'est un choix, pas une mesure).
- **`mock_feed.mp4`** (54 964 o) : le harnais attend du **h.265** (Android ne transcode pas, le h.264 est
  rejeté) ; le contenu du fichier n'a **pas** été inspecté (lecture binaire hors périmètre) → non vérifié.
- **`MockGlassesTest` n'est jamais exécuté en CI** (le job `spike` ne fait qu'`assembleDebug` et
  `testDebugUnitTest`) : vert ici = vert sur *cet* appareil, jamais « vert en CI ».

## Écarts restants

Ce qui reste ouvert après l'audit — aucun de ces points n'est fermable ici :

1. **Format et strides du buffer livré** : I420 ou NV12, et quel padding de ligne. L'ambiguïté est
   *mesurée* par un test du module (un NV12 serré fait exactement la taille d'un I420 serré) ; le
   bytecode prouve seulement que **19** est demandé au décodeur. À trancher sur matériel, en premier.
2. **Cadence, latence, pertes, reprises de session** : non mesurées (pas de lunettes). Et la sortie
   du spike est de toute façon bornée à ≈ **0,67 image/s** par `FRAME_THROTTLE_MS = 1_500L`
   (`GlassesSessionPolicy.kt:22`) : « pipeline 24 i/s » désigne l'entrée, pas la cadence utile.
3. **`presentationTimeUs`** : référentiel inconnu (horloge média côté lunettes/décodeur vs horloge
   monotone du téléphone, `GlassesFramePolicy.kt:23-25`). À unifier le jour où l'aval compare ces valeurs.
4. **`MockGlassesTest`** : exige un appareil (ou un émulateur) et l'asset vidéo ; aucun workflow ne
   l'exécute, il n'est ni compilé ni lancé en CI.
5. **`mockdevice` dans l'APK** : `TODO(spike)` (`build.gradle.kts:76`) — à retirer avant tout build public.
6. **Le harnais n'apparie qu'un modèle** : `RAYBAN_META` est en dur (`MockGlassesHarness.kt:137`) alors
   que le SDK expose **6** modèles simulés, dont **`META_RAYBAN_DISPLAY`**. La question ouverte
   « version lunettes Meta ? » (A-Z § 7.2) n'est donc **pas instruite** par le scaffold : la trancher
   demande une ligne de code, pas du matériel.
7. **Constantes dupliquées** : `MAX_IMAGE_EDGE = 1024` et `JPEG_QUALITY = 85`
   (`GlassesFrameEncoder.kt:13,16`) valent exactement celles de `:app`
   (`app/src/main/java/dev/sam3vision/app/ImageSource.kt:16-17`) — duplication **voulue** (le spike ne
   dépend pas de `:app` et ne doit pas en dépendre) : à ne pas « factoriser ».
8. **Le job `spike` n'est pas un passage obligé** : `.mergify.yml` est **vide** sur ce `master` (aucun
   check requis déclaré dans le dépôt), donc `spike`, `apk`, `apk-release` et `lint` ne bloquent rien
   tant qu'aucune protection de branche ne les déclare requis — un module cassé peut être fusionné
   avec `ci` vert. Ce n'est pas un écart du scaffold, c'est un trou de porte.

**Les trois écarts corrigés dans cette PR** (et eux seuls) :

- **`MainActivity.kt`** : le commentaire annonçait « un journal, **trois** boutons » ; `SpikeScreen`
  en déclare **quatre** (`Button(onClick` × 4 : Mock, Lunettes, Stop, Analyser, `:227-230`), comme la
  fiche § 1. Commentaire aligné sur le code.
- **`glasses-dat-spike.md` § 0** : « 1 dans `src/androidTest` (**135 l.**) = **2 373 l.** » → mesuré
  ici **158 l.** et **2 396 l.** (14 fichiers `main` 1 596 l. + 6 `test` 642 l. + 1 `androidTest` 158 l.).
  Les deux autres totaux de la fiche (1 596 et 642) étaient exacts.
- **`glasses-dat-spike.md` § 5** : « `color-format = 19` : **non vérifié** dans le SDK ici » → vérifié
  au `javap` sur ce cache Gradle (voir § « Ce que le bytecode prouve »), en gardant ce qui reste ouvert
  (format réellement livré, strides).

Rien d'autre n'a été touché : ni le code du module (hors le commentaire ci-dessus), ni les fichiers de
garde (`ci.yml`, `.mergify.yml`, `tools/`, `scripts/`), ni `:app`.