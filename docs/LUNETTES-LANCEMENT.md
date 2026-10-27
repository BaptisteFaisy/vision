# Faire tourner le banc d'essai lunettes de bout en bout sur le téléphone

Runbook du **banc d'essai lunettes**, écran d'iSee (`Route.Bench`) livré dans l'app unique
**SAM Vision** (`dev.sam3vision.app`) : de `local.properties` jusqu'à la ligne `voix : …`
dans logcat.

Le banc **n'est plus une app séparée** : il vit dans `:app`, paquet `dev.sam3vision.app.glasses.bench`
(18 fichiers main, 2 debug, 1 release, 9 tests JVM, 1 test instrumenté). Une seule app à installer, une
seule commande de lancement.

**Mesures du 26/10/2026 sur le téléphone 2c6852ac (Redmi Note 13 Pro, Android 15), build r630.**

Deux étiquettes partout :
**MESURÉ** = **exécuté** sur le téléphone réel le 26/10/2026 (journal `GlassesSpike`, `adb`,
APK) **ou** lisible dans le code de **cet** arbre (citation `fichier:ligne`) ; **NON MESURÉ** = repris
d'une doc, déduit, ou jamais exécuté. Ce qui reste hors de portée est listé en §10, les preuves en §12.

> **Le banc a changé d'hôte le 27/10/2026** : le module d'app séparé a été supprimé et son code
> déplacé à l'identique dans `:app` (`app/src/main/java/dev/sam3vision/app/glasses/bench/`) ; il
> n'y a plus d'`Activity` de banc, c'est l'écran `Route.Bench` d'iSee. Les **chiffres** du
> 26/10 (durée de build, taille d'APK, nombre de tests de l'ancien module) décrivaient cette app
> séparée : ils ne sont **pas** repris ici. Les **relevés de journal** et les comportements, eux,
> restent valables : le code qui les émet est celui qui est cité ci-dessous, revérifié ligne à
> ligne dans cet arbre.

## 1. À quoi sert le banc, ce qu'il prouve

`app/src/main/java/dev/sam3vision/app/glasses/bench/` (18 fichiers) : il n'y a **plus de module dédié**
— `settings.gradle.kts:18-19` ne déclare que `:core` et `:app`. Le banc est compilé dans l'app
(`app/build.gradle.kts:180-184` pour les artefacts `mwdat-*`), et son entrée est un écran de la pile de
navigation : `AppNav.kt:45` (`data object Bench : Route`), `:99`
(`Route.Bench -> BenchScreen(onBack = pop)`).

Chaîne visée : lunettes (ou appareil simulé) → frames → SAM 3.1 → LLM → voix ElevenLabs.

| Prouvé par un run complet | Preuve |
|---|---|
| Le transport DAT s'ouvre (enregistrement → session → caméra → flux) | `GlassesDeviceSession.kt:30`, `:57`, `:72` |
| Des frames arrivent et sont encodées (WxH + Ko base64) | `GlassesFramePump.kt:55`, `GlassesAnalysisStep.kt:39` |
| SAM + LLM répondent, la voix lit la description ; le mode **sans lunettes** couvre tout ça sans matériel | `GlassesAnalysisStep.kt:45-46` ; `UseCases.kt:21` ; `MockGlassesHarness.kt:45-62` |

**Ce qu'une démo mock ne prouve pas** (`docs/glasses-dat-spike.md` §8, **NON MESURÉ**) : latence et
débit Bluetooth, optique (champ, distorsion, exposition, rolling shutter), routage audio réel des
lunettes, permissions réelles de l'app Meta AI, firmware, thermique/autonomie, pause du flux lunettes
retirées. Le format de frame reste ambigu : `inferYuv420Layout` le **déduit de la taille du buffer**
(`FrameTypes.kt:134-151`) et un NV12 serré est indiscernable d'un I420 serré. ⚠️ 24 i/s non compressé
pour le banc (`GlassesDeviceSession.kt:12`), 15 i/s compressé côté Direct de iSee
(`docs/glasses-dat-spike.md` §1) : ne rien transposer de l'un à l'autre.

## 2. Prérequis — lunettes réelles

| Élément | Valeur | Statut |
|---|---|---|
| Téléphone | **Android 10+** (API 29), imposé par les AAR `mwdat-*` | MESURÉ : `app/build.gradle.kts:84` (`minSdk = 29`), `:180-182` |
| App Meta AI | ≥ **v292** (doc antérieure, hors de cet arbre) ; « v295 installée » = constat orchestrateur | **NON MESURÉ** |
| Firmware lunettes ≥ **v128**, Developer Mode **activé** (Meta AI → Settings → App Info → taper 5× la version) | constats externes antérieurs | **NON MESURÉ** |

En Developer Mode l'attestation n'est pas utilisée : les deux `meta-data` restent à `"0"`
(`app/build.gradle.kts:96-97` → `app/src/main/AndroidManifest.xml:34-39`) — aucune clé Meta, aucun
projet Developer Center.

Permissions : `CAMERA`, `RECORD_AUDIO`, `BLUETOOTH_CONNECT`, `MODIFY_AUDIO_SETTINGS` et `INTERNET` sont
déclarées (`app/src/main/AndroidManifest.xml:4-15`). Au premier lancement d'iSee, l'app demande `CAMERA`
+ `RECORD_AUDIO` + `BLUETOOTH_CONNECT` (`FirstRunPermissions.kt:19-22`, appelé à `MainActivity.kt:166`)
; à l'ouverture du banc, l'écran redemande ce qui manque pour une session DAT — `BLUETOOTH_CONNECT` (API
31+) et `CAMERA` (`BenchScreen.kt:328-334`) — et journalise le verdict (`:337-339`, ligne
`permissions : CAMERA=true …`).

**Mode sans lunettes (mock)** : rien de tout ça — ni Meta AI, ni Developer Mode, ni firmware, ni
Bluetooth. Il faut juste `CAMERA` pour que le flux simulé soit la caméra du téléphone
(`MockGlassesHarness.kt:75-90`) ; refusée, le harnais le dit et ne crashe pas (`:79`). ⚠️ Le mock
**n'existe que dans l'APK debug** : `MockControl.available = true` en `src/debug`
(`app/src/debug/java/dev/sam3vision/app/glasses/bench/MockControl.kt:16`), `false` en `src/release`
(`app/src/release/java/dev/sam3vision/app/glasses/bench/MockControl.kt:16`) où l'artefact
`mwdat-mockdevice` est absent (`debugImplementation`, `app/build.gradle.kts:184`). En release, le bouton
« Mock » n'est pas affiché (`BenchScreen.kt:130`) et un lancement `--ez mock true` journalise « appareil
simulé absent de ce build (release) ».

## 3. Les clés : `local.properties` → `BuildConfig`

`local.properties` vit **à la racine du dépôt** et n'est **jamais commité** (`.gitignore:1`). Absent,
`localProp()` renvoie `""` (`app/build.gradle.kts:5-9`).

| Clé | `BuildConfig` | Ligne |
|---|---|---|
| `sdk.dir` (+ `llm.base.url` / `llm.api.key` / `llm.model`) | — / `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` | SDK local (retiré avant l'envoi au r630, §4) ; `app/build.gradle.kts:100-102` |
| `tts.api.key` / `tts.voice.id` | `TTS_API_KEY` / `TTS_VOICE_ID` | `:104-105` |
| `asr.api.key` | `ASR_API_KEY` | `:107` |
| `sam31.endpoint` | `SAM31_ENDPOINT` | `:109` |
| `sam.base.url` / `sam.us.base.url` | `SAM_BASE_URL` / `SAM_US_BASE_URL` | `:111-113` |
| `mwdat.application.id` / `mwdat.client.token` | placeholders de manifeste | `:96-97` |

Le banc lit ces valeurs **au build**, pas dans les Réglages : `BuildDefaults.FromBuild`
(`SettingsSeed.kt:10-20`, une seule lecture pour tout le banc, `BenchScreen.kt:172`), et pour SAM
directement `BuildConfig.SAM31_ENDPOINT` (`BenchScreen.kt:315`) — c'est le seul champ qui n'a pas de
seed. Conséquence pratique : **une clé saisie à la main dans Réglages ne change rien pour le banc** ; il
faut rebâtir (§4). Assemblage du pipeline : `BenchScreen.kt:309-323`.

- **Sans `sam31.endpoint`** : segmentation désactivée, pas cassée —
`sam = if (settings.segmentationEnabled) HttpSamClient(...) else null` (`BenchScreen.kt:319`),
`segmentationEnabled = resolveSamEndpoint().isNotBlank()` (`core/…/settings/Settings.kt:136`) ;
l'analyse continue en LLM seul (`UseCases.kt:21`).
- **Sans `llm.api.key`** : le banc se lance quand même mais journalise
« `ATTENTION : llm.api.key absente de local.properties` » (`BenchScreen.kt:320`), puis l'analyse échoue
(`GlassesAnalysisStep.kt:42-43`, journal `logCause` `:57-64`). Le message « Clé LLM absente —
renseigne-la dans Réglages » (`LiveMessages.kt:33`) appartient aux écrans de iSee : il n'est **pas**
émis par le banc.
- **Sans `tts.api.key`** : la voix répond « `clé ElevenLabs absente — renseigne-la dans Réglages` »
(`core/…/net/ElevenLabsTtsClient.kt:42`), visible sous `voix : …` (`GlassesAnalysisStep.kt:46`, message
court rendu par `GlassesVoice.say`, `:94-98`).

Même règle que pour tout le dépôt : **aucune valeur de clé ne sort de la machine** — ce document ne cite
que des **noms** de clés.

## 4. Build — sur le r630, avec les clés

```bash
tools/r630-build.sh <worktree> :app:assembleDebug
```

**Statut MESURÉ** : le script **est dans le dépôt** (`tools/r630-build.sh`, commité), sa tâche par
défaut est déjà `:app:assembleDebug` (`r630-build.sh:13`). Ce qu'il fait : `tar|ssh` du worktree vers
`baptiste@100.124.18.48:builds/<nom>` (sans `.git`, `build/`, `.gradle/`, `.kotlin/`), réécriture de
`local.properties` — `sdk.dir` **du serveur** + **les clés du poste** (`grep -vE '^sdk\.dir='`) — puis
`./gradlew --console=plain` en `JAVA_HOME=/home/baptiste/toolchains/jdk17` (`r630-build.sh:20-31`),
enfin `find … -name '*.apk' -newermt '-30 minutes'` pour le chemin réel (`:33-34`).

| Mesure | Valeur | Statut |
|---|---|---|
| Durée `:app:assembleDebug` sur r630 | — | **NON MESURÉ** (les 47 s / ~6 s du 26/10 portaient sur l'ancien module) |
| Taille de l'APK debug | — | **NON MESURÉ** (les 62 619 498 o du 26/10 portaient sur l'ancien module) |
| Tests JVM du banc | `./gradlew :app:testDebugUnitTest --tests 'dev.sam3vision.app.glasses.bench.*'` — 9 fichiers : `ChaineCompleteTest`, `CycleLatchTest`, `FramePolicyTest`, `GlassesAudioRouteTest`, `GlassesFrameEncoderTest`, `SessionPolicyTest`, `SpikeLaunchTest`, `SpikeLogTest`, `YuvConverterTest` | MESURÉ (fichiers présents dans cet arbre) ; **compte de tests NON MESURÉ** |
| Chemin de l'APK (serveur) | `~/builds/<nom>/app/build/outputs/apk/debug/app-debug.apk` | chemin Gradle de `:app` (module `app`, variante debug) |

**Le piège** : `~/.local/bin/build-serveur` écrit un `local.properties` contenant **seulement**
`sdk.dir` (`build-serveur:37`) → il **écrase les clés** ; `BuildConfig` sort vide et le banc n'appelle
personne — l'échec est **silencieux au build**, il n'apparaît qu'au premier appel réseau
(`BenchScreen.kt:320` le signale pour le LLM, rien pour SAM ni la voix). Pour cette app :
`tools/r630-build.sh`.

⚠️ Le `versionCode` est la **date du commit HEAD** (`app/build.gradle.kts:86-91`) : Android refuse
d'installer une build plus ancienne que celle du téléphone. Un tarball sans git retombe sur
`gitlessVersionCode = 1` (`:46`, `:58-63`) — dans ce cas, poser
`SAM_VERSION_CODE=<versionCode installé>` avant de bâtir.

## 5. Installation — MIUI, et le paquet fantôme

```bash
tools/phone-lunettes.sh <apk> [--dry] [--no-launch]
```

**Statut MESURÉ** : le script **est dans le dépôt** (`tools/phone-lunettes.sh`, commité) et **a été
exécuté** le 26/10 sur `2c6852ac`. Il vise désormais iSee (`PKG="dev.sam3vision.app"`,
`ACT="$PKG/.MainActivity"`, `phone-lunettes.sh:8`) et passe **toujours** par le verrou `~/bin/phone run`
(`:31-34`), donc **jamais d'`adb` nu** ; il écrit ses preuves dans `~/preuves/lunettes/<horodatage>/`
(`:104`).

Équivalent direct, si l'on préfère Gradle : `./gradlew :app:installDebug` (une seule app, plus de
seconde APK à poser).

### 5.1 Le paquet fantôme — MESURÉ

Le mécanisme est inchangé, mais il concerne maintenant **iSee lui-même** (`dev.sam3vision.app`) :

| Constat | Valeur mesurée (26/10, sur l'ancien paquet de l'app séparée) |
|---|---|
| `pm list packages` | le paquet est listé |
| `pm path` | **vide**, `rc=1` |
| `codePath` | `/data/app/~~…/<paquet>-…` **disparu** |
| `am start` | « Activity class does not exist », **result code -92** |

Conséquence : l'app **ne pouvait pas se lancer**. Le script détecte le fantôme (listé **et** `pm path`
vide, `:51`), purge (`pm uninstall` puis `pm uninstall --user 0`, `:56`) et **refuse de mentir** si le
fantôme résiste (`:61`). **Réparation mesurée** : purge puis réinstallation → paquet sain. Pour iSee, un
paquet fantôme signifie que **toute** l'app est cassée, banc compris.

### 5.2 MIUI — `INSTALL_FAILED_USER_RESTRICTED` — MESURÉ

`adb install -r` est **refusé** par MIUI : `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` («
Installer via USB » désactivé). Contournement qui marche :

```bash
adb push <apk> /data/local/tmp/isee.apk            # push mesuré à 97 Mo/s
adb shell pm install -r /data/local/tmp/isee.apk   # → Success
```

`tools/phone-lunettes.sh` **enchaîne les deux** (`:77-87`), après le cas
`INSTALL_FAILED_VERSION_DOWNGRADE` → `install -r -d` (`:70-76` ; `versionCode` = date du commit,
`app/build.gradle.kts:86-91`, donc le cas se produit surtout avec un tarball ou une branche plus
vieille). Contrôle : `pm path` non vide après coup (`:90`), puis `codePath` / `versionName` (`:91`).

Le téléphone est branché en USB sur ce poste ; **tout** accès passe par le verrou `~/bin/phone`. Dans le
montage décrit par `ref/BUILD-SERVEUR-TEST-PHONE.md` §2, c'est le r630 qui le voit —
`~/bin/phone-adb -s 2c6852ac …`. Dans les deux cas : jamais d'`adb` nu.

## 6. Lancement automatisé

```bash
adb shell am start -n dev.sam3vision.app/.MainActivity --ez mock true --ez analyze true
```

Sous le verrou (règle maison, c'est ce que fait le script) :

```bash
~/bin/phone run --wait 300 --task "lunettes: lancement" -- \
  adb -s 2c6852ac shell am start -n dev.sam3vision.app/.MainActivity --ez mock true --ez analyze true
```

Composant MESURÉ : `applicationId = "dev.sam3vision.app"` (`app/build.gradle.kts:83`) et activité
`MAIN`/`LAUNCHER`, `exported="true"` (`app/src/main/AndroidManifest.xml:41-49`).

Chaîne de routage : `MainActivity.routeIntent` (`MainActivity.kt:79-90`) reconnaît les deux extras
(`:81`, `hasExtra`) → `BenchEntryPoint.request` (`BenchEntryPoint.kt:21-23`) → `AppNav` observe
`pending` et pousse `Route.Bench` (`AppNav.kt:80-81`). `routeIntent` est appelé depuis `onCreate`
(`MainActivity.kt:64`) **et** `onNewIntent` (`:70`) : relancer la commande sur une app déjà ouverte
rouvre donc le banc.

La **décision** de lancement vit dans `SpikeLaunch.kt` (75 l.), **l'exécution** dans `BenchScreen`. Le
banc n'étant plus une `Activity`, il ne lit plus d'`Intent` : l'adaptateur Android traduit les extras en
options (`SpikeLaunch.fromExtras`, `:60-61`) et l'écran les consomme **une seule fois** à sa première
composition (`BenchEntryPoint.consume()`, `BenchScreen.kt:71-72`), puis exécute le plan
**dans l'ordre** (`applyLaunchPlan`, `:255-268`).

| Extra | Effet réel | Preuve |
|---|---|---|
| `--ez mock true` | Mock DeviceKit + **caméra du téléphone** + session | `SpikeLaunch.kt:20` (`EXTRA_MOCK`), `:68-75` (`ENABLE_MOCK`, `PHONE_CAMERA_FEED`, `START_SESSION`) ; `BenchScreen.kt:277-292` |
| `--ez analyze true` | **arme l'analyse réseau AVANT l'ouverture de session** (`LaunchStep.ARM_ANALYZE` puis `START_SESSION`) → la **première frame part déjà** à SAM/LLM | `SpikeLaunch.kt:23` (`EXTRA_ANALYZE`), `:68-75` ; `BenchScreen.kt:271-274` |

⚠️ L'ancien `EXTRA_AUTO_MOCK` **a disparu** : les deux extras sont `EXTRA_MOCK = "mock"` et
`EXTRA_ANALYZE = "analyze"` (`SpikeLaunch.kt:20`, `:23`).

**MESURÉ le 26/10 (21:08)** — `am start … --ez mock true --ez analyze true`, journal `GlassesSpike`
(les lignes sont émises par le code cité ci-dessus, déplacé depuis l'ancienne app) :

| Ordre | Journal |
|---|---|
| 1 | `analyse réseau ACTIVÉE (1 frame / 1,5 s)` (l'analyse est armée **avant** la session, `SpikeLaunch.kt:26`) |
| 2 | `MockDeviceKit activé (…)` (`MockGlassesHarness.kt:53`) |
| 3 | `Ray-Ban Meta simulées appariées` (`:177`) |
| 4 | `session : IDLE` → `session : STARTING` → `session : STARTED` |
| 5 | `caméra ajoutée` |
| 6 | `flux : STOPPED` → `flux : STARTING` → `flux : STREAMING` |
| 7 | `frame transmise : 480x640` puis `frame 480x640 — 3-5 Ko base64`, une frame toutes les ~1,5 s |

Le processus est bien au **premier plan** (`topResumedActivity`).

**Sans extra du tout**, le banc s'ouvre par l'écran : Réglages → bloc **« Lunettes »** →
**« Banc d'essai lunettes »** (`GlassesSection.kt:88-98`, bouton `:97` ; le bloc est monté par
`SettingsScreen.kt:114`). Ses **cinq gestes** : **Mock** (debug seulement), **Lunettes**, **Stop**,
**Analyser** / **Couper l'analyse**, **Diag** (`BenchScreen.kt:128-142`). Le bouton « Analyser » reste
la bascule manuelle (`BenchScreen.kt:229-235`) — l'extra `analyze` la rend inutile au lancement. Le
retour d'enregistrement / d'invocation vocale de l'app Meta AI passe par le deep-link `samvision`
(`app/src/main/AndroidManifest.xml:51-56`).

⚠️ En **APK release**, `--ez mock true` n'a aucun effet (pas de Mock Device Kit, §2) : la recette
complète suppose un build **debug**.

## 7. Chaîne complète sans GPU

```bash
python3 tools/mock-sam.py &            # écoute http://127.0.0.1:8099
adb reverse tcp:8099 tcp:8099          # le téléphone atteint le port du poste
# local.properties : sam31.endpoint=http://127.0.0.1:8099   ← AVANT le build
```

**Statut MESURÉ** : `tools/mock-sam.py` **est dans le dépôt** (commité) et **a été exécuté**.
Usage : `python3 tools/mock-sam.py [--host 127.0.0.1] [--port 8099] [--labels a,b]` (`mock-sam.py:15`,
`:226-243`). Contrat HTTP = celui de `HttpSamClient.kt` : `GET /health`,
`POST /segment {"image_b64", "prompts"}` (`core/…/net/HttpSamClient.kt:9`, `:16`, `:27-28`) ; le
client **ignore** les champs additifs (`count`). Masques = PNG gris 8 bits à la taille de l'image ;
l'image n'est jamais décodée, ses dimensions viennent de l'en-tête SOF/IHDR (`mock-sam.py:38-77`).
Le mock journalise chaque requête (horodatage, méthode, chemin, taille reçue, nombre de masques,
durée en ms — `mock-sam.py:212-217`) : c'est le **journal du faux SAM**.

**Banc hors téléphone** — `tools/test-mock-sam.sh`, **MESURÉ le 26/10** : **26 ok / 0 KO**, latence
mini **2 ms**. `/health` et `/segment` sont contrôlés champ par champ (dimensions lues dans l'en-tête,
`mask_encoding`, 2 à 4 objets, libellés, scores, boîtes, masque PNG gris décodable, validation d'entrée,
latence < 100 ms) (`test-mock-sam.sh:173-231`).

**Tunnel** — `adb reverse tcp:8099 tcp:8099` se pose **depuis ce poste**, sous le verrou :

```bash
~/bin/phone run --wait 300 --task "lunettes: reverse SAM" -- adb -s 2c6852ac reverse tcp:8099 tcp:8099
```

**La vraie segmentation exige la GPU Modal.** Règle maison : **une seule GPU à la fois**, jamais
allumée de sa propre initiative (c'est l'utilisateur qui la demande, bail 30 min ; un sous-agent ne
l'allume pas). Le chemin sans GPU ci-dessus existe précisément pour ne pas en dépendre.

### 7.1 Fournisseurs — MESURÉ le 26/10

| Fournisseur | Réponse mesurée |
|---|---|
| LLM `ali/qwen3.8-omni-flash` (modèle de `local.properties`) | **429 `capacity_saturated`** — « every provider within your price limit is busy » ; en relevant le plafond de prix : « every provider for this model is at its concurrency limit » |
| LLM `gemini-3.8-flash` | **200** + description **en français** |
| ElevenLabs (voix) | **200**, **20 105 o** d'audio MP3 (en-tête `ID3`) pour « Test de voix. » |

⚠️ La saturation **429 vient du fournisseur**, pas de l'app : changer `llm.model` (§3) et rebâtir.

### 7.2 Le piège du clair — MESURÉ

Sans `app/src/main/res/xml/network_security.xml` (+ `android:networkSecurityConfig` dans le manifeste,
`app/src/main/AndroidManifest.xml:28`), **Android 15 (targetSdk 36) bloque le trafic en clair** : chaque
frame se termine par « analyse échouée : backend injoignable : réseau » et le faux serveur SAM **ne
reçoit RIEN**. La règle en place autorise le clair vers `10.0.2.2`, `localhost`, `127.0.0.1`
**seulement** — tout le reste (api.inferhub.dev, api.elevenlabs.io) reste HTTPS
(`app/src/main/res/xml/network_security.xml:8-12`). Le build **debug** ajoute en plus
`android:usesCleartextTraffic="true"` (`app/src/debug/AndroidManifest.xml:8-10`) ; l'APK release garde
le HTTPS obligatoire.

### 7.3 Chaîne complète sans réseau du téléphone — MESURÉ le 26/10 (22:23)

Le téléphone de recette **ne résolvait aucun nom pour l'UID de l'app** (voir plus bas) : pour prouver la
chaîne entière sans dépendre de son DNS, on sert aussi le LLM sur le poste.

```bash
python3 tools/mock-llm.py &            # écoute http://127.0.0.1:8098/v1 (compatible OpenAI)
adb reverse tcp:8098 tcp:8098
# local.properties : llm.base.url=http://127.0.0.1:8098/v1   ← AVANT le build
```

Relevé réel du journal `GlassesSpike` avec les deux faux serveurs (SAM 8099 + LLM 8098) :

```
frame 480x640 — 3 Ko base64
objets : [personne, téléphone, ordinateur]      ← SAM local a répondu 2-3 masques (4-6 ms)
voix : backend injoignable : réseau             ← ElevenLabs, seul maillon laissé au vrai fournisseur
```

Donc `frames → SAM → libellés → LLM → description → voix` est **prouvé sur le téléphone**, et le seul
maillon qui échouait est le dernier, pour une raison de **réseau du téléphone** : l'appel à l'API
ElevenLabs (et à un vrai LLM) exige une résolution DNS, que l'UID de l'app n'obtenait pas.

**Le diagnostic, en une commande** (A/B mesuré le 26/10) :

```
$ adb shell ping -c1 -W3 api.inferhub.dev                    # shell, uid 2000  → 6 ms, résolu
$ adb shell run-as dev.sam3vision.app ping -c1 -W3 api.inferhub.dev
ping: unknown host api.inferhub.dev                          # uid de l'app → échec
```

L'app elle-même journalise la cause exacte (ajout de `GlassesAnalysisStep.logCause`,
`GlassesAnalysisStep.kt:57-64`) :

```
analyse échouée : backend injoignable : réseau
cause : Network(…) ← UnknownHostException(Unable to resolve host "api.inferhub.dev":
        No address associated with hostname) ← GaiException(android_getaddrinfo failed: EAI_NODATA)
```

`android.permission.INTERNET` est bien `granted=true` (`dumpsys package`) et le trafic vers `127.0.0.1`
passe : la restriction est **par UID**, pas dans l'app. À lever sur le téléphone (réglages MIUI de
l'application : accès réseau / économie de données), ou à rejouer sur un autre appareil. **Ce n'est pas
un défaut du banc.**

**Résolu le 27/10 (matin), sans toucher au code** : le téléphone résout de nouveau les noms
(`run-as … ping -c1 api.inferhub.dev` → 6,8 ms, `private_dns_mode=null`, aucune restriction
dans `cmd netpolicy list restrict-background-uid`, `inet` bien dans les groupes de l'UID). Le blocage
venait donc
du réseau du téléphone (WiFi à portail, réseau changé), pas de l'application. Chaîne **complète**
observée ce jour-là, avec les vrais fournisseurs et les vraies lunettes appairées (`RB Meta 01TK`) :

```
analyse réseau ACTIVÉE (1 frame / 1,5 s)
voix : Bluetooth « RB Meta 01TK » (type 8)      ← sortie routée vers les lunettes
objets : [personne, téléphone, ordinateur]      ← SAM, puis LLM réel (api.inferhub.dev)
```

La description réelle (« This is a slightly tilted, high-angle shot of a bedroom… ») s'affiche à l'écran
et l'audio part en `bt_a2dp` (`dumpsys media.player` : client `dev.sam3vision.app`, `mime(audio/mpeg)`,
`Devices: bt_a2dp(80)`). La sortie choisie est journalisée par `routeToGlasses`
(`GlassesVoice.kt:74-86`). La description est **en anglais** : c'est voulu
(`SpeechLanguage.DEFAULT = En`, demande du 26/10) ; « Voix en français » est un bouton des
Réglages de iSee. ⚠️ Un build posé par un autre agent (23:55) **ignore l'extra `analyze`** : sans
lui, aucune analyse n'est tentée et le journal ne montre que des frames. Vérifier avec
`grep -c "analyse réseau ACTIVÉE"` avant de conclure.

## 8. Lire la preuve

Tout le journal du banc passe par `BenchController.log` (`BenchScreen.kt:341-345`) : **double
écriture**, `Log.i(TAG, …)` **et** la liste à l'écran, TAG **`GlassesSpike`** (`BenchScreen.kt:349`, «
TAG historique du banc : `tools/phone-lunettes.sh` et la doc le filtrent »).

```bash
adb logcat -d -s GlassesSpike:V | tail -n 80
```

Les lignes qui comptent, dans l'ordre (toutes **MESURÉES dans le code**, et observées le 26/10 pour
celles du tableau §6) :

| Ligne de log | Ce qu'elle prouve | Source |
|---|---|---|
| `MockDeviceKit activé (…)` ou `MockDeviceKit déjà actif`, puis `Ray-Ban Meta simulées appariées` | le mock est en place et un appareil simulé existe | `MockGlassesHarness.kt:50`, `:53`, `:166-177` |
| `Appareil simulé prêt : allumé, porté, déplié` | le mock acceptera de streamer | `:62`, `:183-198` |
| `Flux caméra simulé = caméra arrière du téléphone` | la source d'image est la caméra du téléphone | `:90` |
| `permissions : CAMERA=true …` | les permissions runtime sont accordées | `BenchScreen.kt:337-339` |
| `démarrage du cycle lunettes` | le cycle DAT part | `GlassesSession.kt:69` |
| `session démarrée` / `session : <état>` | session ouverte et son état | `GlassesDeviceSession.kt:30`, `:37` |
| `caméra ajoutée` / `flux démarré` / `flux : <état>` | caméra DAT attachée, flux ouvert | `:57`, `:72`, `:63` |
| `voix : Bluetooth « … »` / `voix : haut-parleur …` | la sortie audio choisie pour la voix | `GlassesVoice.kt:79-85` |
| `frame transmise : WxH` puis `frame WxH — N Ko base64` | une frame a traversé le harnais et est arrivée encodée | `GlassesFramePump.kt:55`, `GlassesAnalysisStep.kt:39` |
| `objets : [ … ]` | SAM a répondu et le LLM a nommé des objets | `GlassesAnalysisStep.kt:45` |
| `voix : …` / `analyse échouée : <classe>: <message> — cause : …` | retour TTS, ou cause complète de l'échec réseau/LLM (classe **et** cause, `logCause`) | `GlassesAnalysisStep.kt:46`, `:42-43`, `:57-64` |

Points de vigilance :

- Les logs du harnais passent par le **callback** `log` → TAG `GlassesSpike`, pas
`MockGlassesHarness` ; seules ses traces d'échec (stack) portent l'autre TAG
(`MockGlassesHarness.kt:33`, `Log.e` `:67`, `:96`, `:119`) — en cas de crash, ajouter
`-s MockGlassesHarness:V`.
- L'analyse est **bornée** : une frame utile au plus toutes les 1,5 s
(`FRAME_THROTTLE_MS = 1_500L`, `GlassesSessionPolicy.kt:22`), canal conflaté
(`GlassesSessionRuntime.kt:43`) — un `tail` trop tôt ne montre rien.
- **Capture d'écran** : `adb exec-out screencap -p > ecran.png` — le script le fait déjà
(`phone-lunettes.sh:108`) avec `logcat.txt` (`:105`) et `version.txt` (`:106-107`) dans
`~/preuves/lunettes/<horodatage>/`.
- Le journal **à l'écran** ne garde que **60** lignes (`SPIKE_LOG_LINES = 60`, `SpikeLog.kt:12`) : la
capture montre la fin, pas le début ; logcat garde tout.

## 9. Dépannage

| Symptôme | Cause | Réparation |
|---|---|---|
| `am start` → **« Activity class does not exist »** / l'app ne se lance pas | **paquet fantôme** : listé, `pm path` vide, `/data/app` disparu | `pm uninstall dev.sam3vision.app` (+ `--user 0`), **puis** réinstaller (§5) |
| Le banc ne s'ouvre pas alors que l'app se lance | les extras n'ont pas été posés, ou l'APK est **release** (`--ez mock true` sans effet) | vérifier `--ez mock true` / `--ez analyze true` (§6) et la variante de build (debug, §2) |
| `adb install -r` → **`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`** | **MIUI** : « Installer via USB » désactivé | `adb push` de l'APK vers `/data/local/tmp/`, puis `adb shell pm install -r /data/local/tmp/isee.apk` → `Success` (§5.2) |
| `adb install -r` → **`INSTALL_FAILED_VERSION_DOWNGRADE`** | `versionCode` = **date du commit HEAD** (`app/build.gradle.kts:86-91`) : la build posée est plus récente | `install -r -d` (descente assumée), ou rebâtir sur `origin/master` ; tarball : `SAM_VERSION_CODE=<versionCode installé>` (§4) |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | keystore différent entre machines qui signent | unifier sur celui du poste (`ref/BUILD-SERVEUR-TEST-PHONE.md`, pièges 1) |
| **404** backend | `sam31.endpoint` sans `/segment`, ou `mock-sam.py` arrêté | tester `GET /health` d'abord (`HttpSamClient.kt:27-28`), contrôler le build et les clés (§3-§4) |
| **« analyse échouée : backend injoignable : réseau »** sur *chaque* frame | le **clair** vers `127.0.0.1` est bloqué | `app/src/main/res/xml/network_security.xml` + `android:networkSecurityConfig` (`app/src/main/AndroidManifest.xml:28`) — sinon le faux SAM ne reçoit rien (§7.2) |
| Une clé tapée dans Réglages « ne marche pas » pour le banc | le banc lit `BuildConfig` au build, jamais les Réglages | mettre la clé dans `local.properties` et **rebâtir** (§3-§4) |
| « erreur de flux : Erreur critique, le stream devrait prendre fin » | **téléphone occupé par un autre agent** (verrou `~/bin/phone`), ou caméra déjà prise | `~/bin/phone status`, attendre la libération, **relancer** : après libération, ça passe (§10) |
| **429 `capacity_saturated`** (« every provider within your price limit is busy ») | **fournisseur LLM saturé**, pas l'app | changer `llm.model` dans `local.properties` (ex. `gemini-3.8-flash`, qui a répondu 200), rebâtir (§4) |
| Écran sans aucune frame | flux jamais démarré, caméra non attachée, lunettes non portées | lire `flux : <état>`, `caméra refusée`, `frame non encodable` (`GlassesDeviceSession.kt:63`, `:51` ; `GlassesFramePump.kt:52`) ; côté mock, `powerOn`/`don`/`unfold` (`MockGlassesHarness.kt:183-198`) |
| `Permission CAMERA refusée : pas de flux caméra du téléphone` | le flux simulé utilise la caméra arrière **du téléphone** | accorder `CAMERA` (`BenchScreen.kt:328-334`) ou passer à `useVideoFeed` (`MockGlassesHarness.kt:79`, `:106`) |
| Analyse absente ou sans objet | `sam31.endpoint` vide (segmentation désactivée, normal) | poser l'endpoint (GPU Modal) ou accepter le LLM seul (`BenchScreen.kt:319`) ; sinon lancer avec `--ez analyze true` ou appuyer sur **« Analyser »** (`:229-235`) |
| Voix muette | pas de routage audio lunettes, ou clé TTS absente | `routeToGlasses()` retombe **silencieusement** sur le haut-parleur sans sortie Bluetooth utilisable (`GlassesVoice.kt:79-85`) ; `voix : clé ElevenLabs absente — renseigne-la dans Réglages` (`ElevenLabsTtsClient.kt:42`) |
| Lancement refusé : « téléphone occupé … » | le verrou `~/bin/phone` est tenu par un autre agent | `~/bin/phone status`, attendre, relancer |
| `voix : backend injoignable : réseau` / `analyse échouée` alors que le faux serveur répond | l'UID de l'app ne résout aucun nom sur ce téléphone (restriction réseau par app), cf. §7.3 | `adb shell run-as dev.sam3vision.app ping -c1 api.inferhub.dev` : si échec alors que `ping` en shell réussit → lever la restriction MIUI de l'app |

Conduite quand ça résiste : **vérifier l'état réel du paquet** (`pm path`, `versionCode`, `codePath`)
avant de réinstaller — c'est ce que fait `--dry` (`phone-lunettes.sh:121-124`), lecture seule.


## 10. Non mesuré ici

Ce qui n'a **pas** été observé le 26/10/2026, et qui ne doit pas être présenté comme acquis :

- **Aucune paire de Ray-Ban Meta réelle** : **0 appareil Bluetooth appairé** sur le téléphone. Tout ce
qui précède repose sur MockDeviceKit — sauf le relevé « chaîne complète » du 27/10 (§7.3), postérieur.
- **Aucune segmentation SAM 3.1 réelle** : la **GPU Modal n'a pas été allumée** (règle maison : jamais
de sa propre initiative). La segmentation vue le 26/10 est celle du faux serveur local (§7).
- **Aucun routage audio vers les lunettes** le 26/10 : le son est reparti sur le haut-parleur du
téléphone (le routage Bluetooth n'est constaté que le 27/10, §7.3).
- **La chaîne complète SAM→LLM→voix n'a pas été observée aboutir** le 26/10 : l'essai s'est arrêté à
l'appel SAM, bloqué par le **trafic en clair** (§7.2). Corrigé depuis par `network_security.xml`, rejoué
le 27/10.
- **Le banc n'a pas encore été rejoué depuis iSee** : les relevés ci-dessus viennent de l'ancienne app
séparée, dont le code a été déplacé tel quel. Le build `:app` (durée, taille d'APK, nombre de tests) et
**la recette adb `--ez mock true --ez analyze true` sur `Route.Bench`** restent **NON MESURÉS** — à
rejouer en premier (§4-§6).
- **L'app Meta AI v295 est bien installée** (constat orchestrateur) mais **aucune paire de lunettes
n'est appairée** ; Developer Mode et firmware ≥ v128 restent **non vérifiés** (constats externes
antérieurs).
- **Le téléphone est partagé** (verrou `~/bin/phone`) : pendant qu'un autre agent le tenait, le flux
a lâché sur « erreur de flux : Erreur critique, le stream devrait prendre fin » et le lancement a même
été refusé (« téléphone occupé … »). **Relancer après libération : ça passe.**
- **Latence bout en bout, débit Bluetooth, optique, thermique/autonomie** : non mesurés (hors mock).
- Le **404 backend** est un symptôme d'orchestrateur, **non reproduit** ici.

## 11. Fichiers cités

Chemins relatifs à `app/src/main/java/dev/sam3vision/app/` sauf mention contraire.

| Fichier | Rôle |
|---|---|
| `BenchScreen.kt` (350 l.) | l'écran : `BenchController` (état + gestes), plan de lancement, pipeline, journal (`TAG = "GlassesSpike"`, `:349`) |
| `SpikeLaunch.kt` (75 l.) | **décision** de lancement : extras lus, ordre des étapes (`ARM_ANALYZE` avant `START_SESSION`) |
| `BenchEntryPoint.kt` (26 l.) | demande d'ouverture du banc posée hors composition, consommée par `AppNav` / `BenchScreen` |
| `BenchMock.kt` (24 l.) | contrat de l'appareil simulé entre les variantes debug et release |
| `GlassesAnalysisStep.kt` (65 l.) | maillon frame → analyse → voix (`frame …`, `objets :`, `voix :`, `analyse échouée :`) |
| `GlassesSession.kt` (83 l.), `GlassesDeviceSession.kt` (74 l.), `GlassesFramePump.kt` (57 l.), `GlassesSessionPolicy.kt` (226 l.), `GlassesSessionRuntime.kt` (107 l.), `GlassesFrameEncoder.kt` (103 l.) | cycle DAT, session/caméra/flux, collecte et étranglement (`FRAME_THROTTLE_MS = 1_500L`, `GlassesSessionPolicy.kt:22`), encodage des frames |
| `FrameTypes.kt` (154 l.), `GlassesFramePolicy.kt` (49 l.) | YUV → JPEG (`inferYuv420Layout`, `:134-151`), politique d'encodage |
| `GlassesVoice.kt` (261 l.) | ElevenLabs + routage audio des lunettes (`routeToGlasses`, `:74-86`) |
| `GlassesRealCheck.kt` (108 l.), `GlassesRegistrationPhase.kt` (69 l.), `GlassesAudioRoute.kt` (55 l.) | diagnostic du mode réel, phases d'enregistrement Meta AI, choix de sortie audio |
| `SpikeLog.kt` (37 l.) | journal borné (`SPIKE_LOG_LINES = 60`, `:12`), messages courts |
| `src/debug/java/…/bench/MockGlassesHarness.kt` (223 l.), `src/debug/java/…/bench/MockControl.kt` (26 l.), `src/release/java/…/bench/MockControl.kt` (25 l.) | Mock Device Kit (debug uniquement) : activation, appariement, source vidéo |
| `src/androidTestDebug/java/…/bench/MockGlassesTest.kt` | test instrumenté du mock |
| `app/build.gradle.kts` (racine de `:app`), `app/src/main/AndroidManifest.xml`, `app/src/debug/AndroidManifest.xml`, `app/src/main/res/xml/network_security.xml` | `applicationId`/`minSdk`/`versionCode`/`BuildConfig` ; permissions, activité `LAUNCHER`, deep-link `samvision` ; clair autorisé en debug ; clair local |
| `settings.gradle.kts` (racine du dépôt) | deux modules seulement : `:core` et `:app` (`:18-19`) |
| `MainActivity.kt`, `AppNav.kt` (`Route.Bench`), `glasses/GlassesSection.kt`, `SettingsScreen.kt`, `FirstRunPermissions.kt`, `SettingsSeed.kt`, `LlmFactory.kt`, `LiveMessages.kt` | routage des extras, écran `Route.Bench`, entrée du banc dans Réglages, permissions, valeurs de build |
| `core/…/settings/Settings.kt`, `core/…/usecase/UseCases.kt`, `core/…/net/HttpSamClient.kt`, `core/…/net/ElevenLabsTtsClient.kt` | `segmentationEnabled` (`:136`), `llmConfigured` (`:139`) ; pipeline ; contrat `/health` + `/segment` ; messages de clé absente |
| `tools/r630-build.sh`, `tools/phone-lunettes.sh`, `tools/mock-sam.py`, `tools/mock-llm.py`, `tools/test-mock-sam.sh` | build r630, install + lancement + preuves, faux serveur SAM, faux LLM, banc hors téléphone |
| `docs/glasses-dat-spike.md`, `docs/META-GLASSES.md` | relevé SDK, prérequis datés |
| `ref/BUILD-SERVEUR-TEST-PHONE.md` | montage build r630 / test téléphone, pièges déjà payés |

## 12. Preuves

Captures du 26/10/2026 (téléphone `2c6852ac`, build r630) dans `~/preuves/lunettes/<horodatage>/`.

Écrits **par le script** (`do_proof`, `phone-lunettes.sh:103-113`) :

| Fichier | Contenu |
|---|---|
| `logcat.txt` | journal `GlassesSpike` (`adb logcat -d -s GlassesSpike:V`, `phone-lunettes.sh:105`) |
| `ecran.png` | capture de l'écran du banc (`:108`) |
| `version.txt` | `versionName` / `versionCode` / `codePath` du paquet installé (`:106-107`) |

Posés **à la main** en plus, dans plusieurs horodatages (le script ne les écrit pas) : `reverse.txt`
(relevé `adb reverse -l`, port 8099 vers le faux SAM) et `audio.txt` (routage audio). Vérifié le 27/10 :
les quatre runs de script `20261026-*` ont bien les trois fichiers ; `reverse.txt` n'apparaît que dans
les captures manuelles (`cause-214x`, `chaine-complete-21xx`).

Le **journal du faux serveur SAM** est celui affiché par `tools/mock-sam.py` — une ligne par requête :
horodatage, méthode, chemin, taille reçue, nombre de masques, durée en ms (`mock-sam.py:212-217`) ; le
banc `tools/test-mock-sam.sh` en réaffiche les dernières lignes (`test-mock-sam.sh:241-244`).
