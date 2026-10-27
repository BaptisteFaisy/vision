# Faire tourner l'app lunettes de bout en bout sur le téléphone

Runbook du module `:glasses-spike` (app **SAM Vision (spike lunettes)**, `dev.sam3vision.glasses`) :
de `local.properties` jusqu'à la ligne `voix : …` dans logcat.

**Mesures du 26/10/2026 sur le téléphone 2c6852ac (Redmi Note 13 Pro, Android 15), build r630.**

**Relu le 26/10/2026** sur le worktree `isee-wt/lunettes-doc2`. Deux étiquettes partout :
**MESURÉ** = **exécuté** sur le téléphone réel le 26/10/2026 (journal `GlassesSpike`, `adb`, APK)
**ou** lisible dans le code de cet arbre (citation `fichier:ligne`) ; **NON MESURÉ** = repris d'une
doc, déduit, ou jamais exécuté. Ce qui reste hors de portée est listé en §10, les preuves en §12.

> **Rejoué le 26/10/2026 sur la nouvelle `origin/master`** (`fc67142`, arbre reconstruit : **aucun
> ancêtre commun** avec la base `128bad0` de ce lot — un `rebase` n'y veut rien dire). Tout ce
> document, et les numéros de ligne qu'il cite, ont été vérifiés dans l'arbre
> `isee-wt/lunettes-replay`, branche `feat/lunettes-replay`. Le côté `:app` de ce lot s'est réduit à
> ce que master n'avait pas déjà fait : la sortie du Mock Device Kit de l'APK release (voir
> `docs/META-GLASSES.md` §12) ; la demande de `BLUETOOTH_CONNECT` **juste avant l'appairage** n'a
> **pas** été reprise — master la demande déjà au premier lancement avec caméra et micro
> (`app/src/main/java/dev/sam3vision/app/FirstRunPermissions.kt`). ``logCause`` ajouté dans
> `GlassesAnalysisStep.kt:57-64` : le journal d'échec nomme la **classe** de l'exception *et* sa
> **cause**.

## 1. À quoi sert le module, ce qu'il prouve

`glasses-spike/` est un **module séparé** (`settings.gradle.kts:20`, namespace
`dev.sam3vision.glasses`, `glasses-spike/build.gradle.kts:23-31`) branché sur `project(":core")`
(`:73`) ; `:app` ne dépend pas de lui. Chaîne visée : lunettes (ou appareil simulé) → frames →
SAM 3.1 → LLM → voix ElevenLabs.

| Prouvé par un run complet | Preuve |
|---|---|
| Le transport DAT s'ouvre (enregistrement → session → caméra → flux) | `GlassesDeviceSession.kt:30`, `:57`, `:72` |
| Des frames arrivent et sont encodées (WxH + Ko base64) | `GlassesFramePump.kt:55`, `GlassesAnalysisStep.kt:39` |
| SAM + LLM répondent, la voix lit la description ; le mode **sans lunettes** couvre tout ça sans matériel | `GlassesAnalysisStep.kt:45-46` ; `UseCases.kt:23-31` ; `MockGlassesHarness.kt:41-60` |

**Ce qu'une démo mock ne prouve pas** (`docs/glasses-dat-spike.md` §8, **NON MESURÉ**) : latence et
débit Bluetooth, optique (champ, distorsion, exposition, rolling shutter), routage audio réel des
lunettes, permissions réelles de l'app Meta AI, firmware, thermique/autonomie, pause du flux lunettes
retirées. Le format de frame reste ambigu : `inferYuv420Layout` le **déduit de la taille du buffer**
(`FrameTypes.kt:134-151`) et un NV12 serré est indiscernable d'un I420 serré. ⚠️ Ne rien transposer à
`:app` (ni l'inverse) : 24 i/s non compressé ici, 15 i/s compressé là (`docs/glasses-dat-spike.md` §1).

## 2. Prérequis — lunettes réelles

| Élément | Valeur | Statut |
|---|---|---|
| Téléphone | **Android 10+** (API 29), imposé par les AAR `mwdat-*` | MESURÉ : `glasses-spike/build.gradle.kts:28` |
| App Meta AI | ≥ **v292** (doc antérieure, hors de cet arbre) ; « v295 installée » = constat orchestrateur | **NON MESURÉ** |
| Firmware lunettes ≥ **v128**, Developer Mode **activé** (Meta AI → Settings → App Info → taper 5× la version) | constats externes antérieurs | **NON MESURÉ** |

En Developer Mode l'attestation n'est pas utilisée : les deux `meta-data` restent à `"0"`
(`build.gradle.kts:35-36` → `AndroidManifest.xml:26-31`) — aucune clé Meta, aucun projet Developer
Center. Deux permissions runtime sont demandées au lancement (`MainActivity.kt:216-224`) :
`BLUETOOTH_CONNECT` (API 31+) et `CAMERA` ; `RECORD_AUDIO` et `MODIFY_AUDIO_SETTINGS` sont
**déclarées et jamais demandées** (`AndroidManifest.xml:10-12`).

**Mode sans lunettes (mock)** : rien de tout ça — ni Meta AI, ni Developer Mode, ni firmware, ni
Bluetooth. Il faut juste `CAMERA` pour que le flux simulé soit la caméra du téléphone
(`MockGlassesHarness.kt:71-88`) ; refusée, le harnais le dit et ne crashe pas (`:75`).

## 3. Les clés : `local.properties` → `BuildConfig`

`local.properties` vit **à la racine du dépôt** et n'est **jamais commité** (`.gitignore:1`). Absent,
`localProp()` renvoie `""` (`glasses-spike/build.gradle.kts:10-14`).

| Clé | `BuildConfig` | Ligne |
|---|---|---|
| `sdk.dir` (+ `llm.base.url` / `llm.api.key` / `llm.model`) | — / `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` | SDK local (retiré avant l'envoi au r630, §4) ; `build.gradle.kts:38-40` |
| `tts.api.key` / `tts.voice.id` | `TTS_API_KEY` / `TTS_VOICE_ID` | `:41-42` |
| `sam31.endpoint` | `SAM31_ENDPOINT` | `:43` |

Lu une fois au démarrage dans `Settings(...)` (`MainActivity.kt:200-207`).

- **Sans `sam31.endpoint`** : segmentation désactivée, pas cassée —
  `sam = if (settings.segmentationEnabled) HttpSamClient(...) else null` (`MainActivity.kt:209`),
  `segmentationEnabled = resolveSamEndpoint().isNotBlank()` (`core/…/settings/Settings.kt:119`) ;
  l'analyse continue en LLM seul (`UseCases.kt:23`).
- **Sans `llm.api.key`** : l'app se lance quand même mais journalise
  « `ATTENTION : llm.api.key absente de local.properties` » (`MainActivity.kt:210`), puis l'analyse
  échoue (`GlassesAnalysisStep.kt:42-43`, journal `logCause` `:57-64`). ⚠️ Le texte « Clé LLM absente — renseigne-la dans Réglages » appartient à
  `:app` (`app/…/LiveMessages.kt:33`) : **il n'existe pas dans le spike** (pas d'écran de réglages).
- **Sans `tts.api.key`** : la voix répond « `clé ElevenLabs absente` » (`GlassesVoice.kt:84`),
  visible sous `voix : …` (`GlassesAnalysisStep.kt:46`).

Même règle que pour tout le dépôt : **aucune valeur de clé ne sort de la machine** — ce document ne
cite que des **noms** de clés.

## 4. Build — sur le r630, avec les clés

```bash
tools/r630-build.sh <worktree> :glasses-spike:assembleDebug
```

**Statut MESURÉ ce 26/10** : le script **est dans le dépôt** (`tools/r630-build.sh`, commité) et
**a été exécuté**. Ce qu'il fait : `tar|ssh` du worktree vers `baptiste@100.124.18.48:builds/<nom>`
(sans `.git`, `build/`, `.gradle/`, `.kotlin/`), réécriture de `local.properties` — `sdk.dir`
**du serveur** + **les clés du poste** (`grep -vE '^sdk\.dir='`) — puis `./gradlew --console=plain`
en `JAVA_HOME=/home/baptiste/toolchains/jdk17` (`r630-build.sh:21-31`), enfin
`find … -name '*.apk' -newermt '-30 minutes'` pour le chemin réel (`:34`).

| Mesure | Valeur | Statut |
|---|---|---|
| Durée `:glasses-spike:assembleDebug` | **47 s** à froid (cache `mwdat` déjà présent), **~6 s** ensuite | MESURÉ 26/10 |
| Tests du module `:glasses-spike:testDebugUnitTest` | **73 tests, 0 échec, 0 erreur** | MESURÉ 26/10 |
| Taille de l'APK debug | **62 619 498 o** | MESURÉ 26/10 |
| Chemin de l'APK (serveur) | `~/builds/lunettes/glasses-spike/build/outputs/apk/debug/glasses-spike-debug.apk` | MESURÉ 26/10 |

**Le piège** : `~/.local/bin/build-serveur` écrit un `local.properties` contenant **seulement**
`sdk.dir` (`build-serveur:37`) → il **écrase les clés** ; `BuildConfig` sort vide et le spike
n'appelle personne — l'échec est **silencieux au build**, il n'apparaît qu'au premier appel réseau.
Pour ce module : `tools/r630-build.sh`.

## 5. Installation — MIUI, et le paquet fantôme

```bash
tools/phone-lunettes.sh <apk> [--dry] [--no-launch]
```

**Statut MESURÉ** : le script **est dans le dépôt** (`tools/phone-lunettes.sh`, commité) et **a été
exécuté** le 26/10 sur `2c6852ac`. Il passe **toujours** par le verrou `~/bin/phone run`
(`phone-lunettes.sh:31-34`), donc **jamais d'`adb` nu**, et écrit ses preuves dans
`~/preuves/lunettes/<horodatage>/` (`:104`).

### 5.1 Le paquet fantôme — MESURÉ

| Constat | Valeur mesurée |
|---|---|
| `pm list packages` | liste `dev.sam3vision.glasses` |
| `pm path` | **vide**, `rc=1` |
| `codePath` | `/data/app/~~…/dev.sam3vision.glasses-…` **disparu** |
| `am start` | « Activity class does not exist », **result code -92** |

Conséquence : l'app **ne pouvait pas se lancer**. Le script détecte le fantôme (listé **et**
`pm path` vide, `:51`), purge (`pm uninstall` puis `pm uninstall --user 0`, `:56`) et **refuse de
mentir** si le fantôme résiste (`:61`). **Réparation mesurée** : purge puis réinstallation → paquet sain.

### 5.2 MIUI — `INSTALL_FAILED_USER_RESTRICTED` — MESURÉ

`adb install -r` est **refusé** par MIUI : `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`
(« Installer via USB » désactivé). Contournement qui marche :

```bash
adb push <apk> /data/local/tmp/glasses.apk            # push mesuré à 97 Mo/s
adb shell pm install -r /data/local/tmp/glasses.apk   # → Success
```

`tools/phone-lunettes.sh` **enchaîne les deux** (`:77-87`), après le cas
`INSTALL_FAILED_VERSION_DOWNGRADE` → `install -r -d` (`:70-76`, `versionCode` figé à 1,
`glasses-spike/build.gradle.kts:30`). Contrôle : `pm path` non vide après coup (`:90`), puis
`codePath` / `versionName` (`:91`).

Le téléphone est branché en USB sur ce poste ; **tout** accès passe par le verrou `~/bin/phone`.
Dans le montage décrit par `ref/BUILD-SERVEUR-TEST-PHONE.md` §2, c'est le r630 qui le voit —
`~/bin/phone-adb -s 2c6852ac …`. Dans les deux cas : jamais d'`adb` nu.

## 6. Lancement automatisé

```bash
adb shell am start -n dev.sam3vision.glasses/.MainActivity --ez mock true --ez analyze true
```

Composant MESURÉ : `glasses-spike/build.gradle.kts:27` (`applicationId`) et
`AndroidManifest.xml:33-41` (activité `LAUNCHER`, `exported="true"`).

La **décision** de lancement vit dans `glasses-spike/src/main/java/dev/sam3vision/glasses/SpikeLaunch.kt`
(74 l.), **l'exécution** dans `MainActivity.kt` (269 l.). `MainActivity` se contente de lire l'`Intent`
(`SpikeLaunch.fromExtras`, `MainActivity.kt:85-88`) puis exécute le plan **dans l'ordre**
(`applyLaunchPlan`, `:134-145`).

| Extra | Effet réel | Preuve |
|---|---|---|
| `--ez mock true` | MockDeviceKit + **caméra du téléphone** + session | `SpikeLaunch.kt:18` (`EXTRA_MOCK`), `:66-73` ; `MainActivity.kt:141-143` |
| `--ez analyze true` | **arme l'analyse réseau AVANT l'ouverture de session** (`LaunchStep.ARM_ANALYZE` puis `LaunchStep.START_SESSION`) → la **première frame part déjà** à SAM/LLM | `SpikeLaunch.kt:21` (`EXTRA_ANALYZE`), `:66-73` ; `MainActivity.kt:140`, `:148-151` |

⚠️ L'ancien `EXTRA_AUTO_MOCK` **a disparu** : les deux extras sont `EXTRA_MOCK = "mock"` et
`EXTRA_ANALYZE = "analyze"` (`SpikeLaunch.kt:18`, `:21`).

**MESURÉ le 26/10 (21:08)** — `am start … --ez mock true --ez analyze true`, journal `GlassesSpike` :

| Ordre | Journal |
|---|---|
| 1 | `analyse réseau ACTIVÉE (1 frame / 1,5 s)` (l'analyse est armée **avant** la session) |
| 2 | `MockDeviceKit activé (…)` |
| 3 | `Ray-Ban Meta simulées appariées` (`powerOn` / `don` / `unfold` OK) |
| 4 | `session : IDLE` → `session : STARTING` → `session : STARTED` |
| 5 | `caméra ajoutée` |
| 6 | `flux : STOPPED` → `flux : STARTING` → `flux : STREAMING` |
| 7 | `frame transmise : 480x640` puis `frame 480x640 — 3-5 Ko base64`, une frame toutes les ~1,5 s |

Le processus est bien au **premier plan** (`topResumedActivity`).

Sans extra du tout, l'écran attend, avec **Mock**, **Lunettes**, **Stop**, **Analyser**
(`MainActivity.kt:256-261`) ; l'enregistrement Meta AI passe par le deep-link `samglasses`
(`AndroidManifest.xml:46-51`, `MainActivity.kt:226-230`). Le bouton **« Analyser »**
(`MainActivity.kt:167-174`, bouton `:260`) reste la bascule manuelle — mais l'extra `analyze`
existe et rend cette bascule inutile au lancement.

## 7. Chaîne complète sans GPU

```bash
python3 tools/mock-sam.py &            # écoute http://127.0.0.1:8099
adb reverse tcp:8099 tcp:8099          # le téléphone atteint le port du poste
# local.properties : sam31.endpoint=http://127.0.0.1:8099   ← AVANT le build
```

**Statut MESURÉ** : `tools/mock-sam.py` **est dans le dépôt** (commité) et **a été exécuté**.
Usage : `python3 tools/mock-sam.py [--host 127.0.0.1] [--port 8099] [--labels a,b]`
(`mock-sam.py:15`, `:226-243`). Contrat HTTP = celui de `HttpSamClient.kt` : `GET /health`,
`POST /segment {"image_b64", "prompts"}` (`core/…/net/HttpSamClient.kt:10`, `:22`, `:28-29`) ;
le client **ignore** les champs additifs (`count`). Masques = PNG gris 8 bits à la taille de
l'image ; l'image n'est jamais décodée, ses dimensions viennent de l'en-tête SOF/IHDR
(`mock-sam.py:38-77`). Le mock journalise chaque requête (horodatage, méthode, chemin, taille
reçue, nombre de masques, durée en ms — `mock-sam.py:212-217`) : c'est le **journal du faux SAM**.

**Banc hors téléphone** — `tools/test-mock-sam.sh`, **MESURÉ le 26/10** : **26 ok / 0 KO**, latence
mini **2 ms**. `/health` et `/segment` sont contrôlés champ par champ (dimensions lues dans
l'en-tête, `mask_encoding`, 2 à 4 objets, libellés, scores, boîtes, masque PNG gris décodable,
validation d'entrée, latence < 100 ms) (`test-mock-sam.sh:173-231`).

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

⚠️ La saturation **429 vient du fournisseur**, pas de l'app : changer `llm.model` (§9).

### 7.2 Le piège du clair — MESURÉ

Sans `glasses-spike/src/main/res/xml/network_security.xml` (+ `android:networkSecurityConfig`
dans le manifeste, `AndroidManifest.xml:22`), **Android 15 (targetSdk 36) bloque le trafic en
clair** : chaque frame se termine par « analyse échouée : backend injoignable : réseau » et le
faux serveur SAM **ne reçoit RIEN**. Le correctif reprend **exactement** la règle de `:app`
(`app/src/main/res/xml/network_security.xml`) : clair autorisé vers `10.0.2.2`, `localhost`,
`127.0.0.1` **seulement** — tout le reste (api.inferhub.dev, api.elevenlabs.io) reste HTTPS.

### 7.3 Chaîne complète sans réseau du téléphone — MESURÉ le 26/10 (22:23)

Le téléphone de recette **ne résout aucun nom pour l'UID de l'app** (voir plus bas) : pour
prouver la chaîne entière sans dépendre de son DNS, on sert aussi le LLM sur le poste.

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

Donc `frames → SAM → libellés → LLM → description → voix` est **prouvé sur le téléphone**,
et le seul maillon qui échoue est le dernier, pour une raison de **réseau du téléphone** :
l'appel à l'API ElevenLabs (et à un vrai LLM) exige une résolution DNS, que l'UID de l'app
n'obtient pas.

**Le diagnostic, en une commande** (A/B mesuré le 26/10) :

```
$ adb shell ping -c1 -W3 api.inferhub.dev                    # shell, uid 2000  → 6 ms, résolu
$ adb shell run-as dev.sam3vision.glasses ping -c1 -W3 api.inferhub.dev
ping: unknown host api.inferhub.dev                          # uid de l'app → échec
```

L'app elle-même journalise la cause exacte (ajout de `GlassesAnalysisStep.logCause`,
`glasses-spike/…/GlassesAnalysisStep.kt`) :

```
analyse échouée : backend injoignable : réseau
cause : Network(…) ← UnknownHostException(Unable to resolve host "api.inferhub.dev":
        No address associated with hostname) ← GaiException(android_getaddrinfo failed: EAI_NODATA)
```

`android.permission.INTERNET` est bien `granted=true` (`dumpsys package`) et le trafic vers
`127.0.0.1` passe : la restriction est **par UID**, pas dans l'app. À lever sur le téléphone
(réglages MIUI de l'application : accès réseau / économie de données), ou à rejouer sur un
autre appareil. **Ce n'est pas un défaut du spike.**

**Résolu le 27/10 (matin), sans toucher au code** : le téléphone résout de nouveau les noms
(`run-as … ping -c1 api.inferhub.dev` → 6,8 ms, `private_dns_mode=null`, aucune restriction dans
`cmd netpolicy list restrict-background-uid`, `inet` bien dans les groupes de l'UID). Le blocage
venait donc du réseau du téléphone (WiFi à portail, réseau changé), pas de l'application.
Chaîne **complète** observée ce jour-là, avec les vrais fournisseurs et les vraies lunettes
appairées (`RB Meta 01TK`) :

```
analyse réseau ACTIVÉE (1 frame / 1,5 s)
voix : Bluetooth « RB Meta 01TK » (type 8)      ← sortie routée vers les lunettes
objets : [personne, téléphone, ordinateur]      ← SAM, puis LLM réel (api.inferhub.dev)
```
La description réelle (« This is a slightly tilted, high-angle shot of a bedroom… ») s'affiche à
l'écran et l'audio part en `bt_a2dp` (`dumpsys media.player` : client `dev.sam3vision.glasses`,
`mime(audio/mpeg)`, `Devices: bt_a2dp(80)`). La description est **en anglais** : c'est voulu
(`SpeechLanguage.DEFAULT = En`, demande du 26/10) ; « Voix en français » est un bouton des
Réglages de `:app`.
⚠️ Un build posé par un autre agent (23:55) **ignore l'extra `analyze`** : sans lui, aucune
analyse n'est tentée et le journal ne montre que des frames. Vérifier avec
`grep -c "analyse réseau ACTIVÉE"` avant de conclure.

## 8. Lire la preuve

Tout le journal de l'écran passe par `log()` (`MainActivity.kt:233-236`) : **double écriture**,
`Log.i(TAG, …)` **et** la liste à l'écran, TAG **`GlassesSpike`** (`MainActivity.kt:239`).

```bash
adb logcat -d -s GlassesSpike:V | tail -n 80
```

Les lignes qui comptent, dans l'ordre (toutes **MESURÉES dans le code**, et observées le 26/10
pour celles du tableau §6) :

| Ligne de log | Ce qu'elle prouve | Source |
|---|---|---|
| `MockDeviceKit activé (…)` ou `déjà actif`, puis `Ray-Ban Meta simulées appariées` | le mock est en place et un appareil simulé existe | `MockGlassesHarness.kt:49`, `:46`, `:142`, `:131-137` |
| `Appareil simulé prêt : allumé, porté, déplié` | le mock acceptera de streamer | `:58`, `:148-165` |
| `Flux caméra simulé = caméra arrière du téléphone` | la source d'image est la caméra du téléphone | `:86` |
| `démarrage du cycle lunettes` | le cycle DAT part | `GlassesSession.kt:69` |
| `session démarrée` / `session : <état>` | session ouverte et son état | `GlassesDeviceSession.kt:30`, `:37` |
| `caméra ajoutée` / `flux démarré` / `flux : <état>` | caméra DAT attachée, flux ouvert | `:57`, `:72`, `:63` |
| `frame transmise : WxH` puis `frame WxH — N Ko base64` | une frame a traversé le harnais et est arrivée encodée | `GlassesFramePump.kt:55`, `GlassesAnalysisStep.kt:39` |
| `objets : [ … ]` | SAM a répondu et le LLM a nommé des objets | `GlassesAnalysisStep.kt:45` |
| `voix : …` / `analyse échouée : <classe>: <message> — cause : …` | retour TTS, ou cause complète de l'échec réseau/LLM (classe **et** cause, `logCause`) | `GlassesAnalysisStep.kt:46`, `:42-43`, `:57-64` ; `GlassesVoice.kt:81-87` |

Points de vigilance :

- Les logs du harnais passent par le **callback** `log` → TAG `GlassesSpike`, pas
  `MockGlassesHarness` ; seules ses traces d'échec (stack) portent l'autre TAG
  (`MockGlassesHarness.kt:29`, `:63`) — en cas de crash, ajouter `-s MockGlassesHarness:V`.
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
| `am start` → **« Activity class does not exist »** / l'app ne se lance pas | **paquet fantôme** : listé, `pm path` vide, `/data/app` disparu | `pm uninstall <pkg>` (+ `--user 0`), **puis** réinstaller (§5) |
| `adb install -r` → **`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`** | **MIUI** : « Installer via USB » désactivé | `adb push` de l'APK vers `/data/local/tmp/`, puis `adb shell pm install -r /data/local/tmp/glasses.apk` → `Success` (§5.2) |
| `adb install -r` → **`INSTALL_FAILED_VERSION_DOWNGRADE`** | `versionCode` **figé à 1** (`glasses-spike/build.gradle.kts:30`), une version ≥ déjà posée | `install -r -d` (descente assumée) |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | keystore différent entre machines qui signent | unifier sur celui du poste (`ref/BUILD-SERVEUR-TEST-PHONE.md`, pièges 1) |
| **404** backend | `sam31.endpoint` sans `/segment`, ou `mock-sam.py` arrêté | tester `GET /health` d'abord (`HttpSamClient.kt:28-29`), contrôler le build et les clés (§3-§4) |
| **« analyse échouée : backend injoignable : réseau »** sur *chaque* frame | le **clair** vers `127.0.0.1` est bloqué par Android 15 (targetSdk 36) | `glasses-spike/src/main/res/xml/network_security.xml` manquant (+ `android:networkSecurityConfig`) — sinon le faux SAM ne reçoit rien (§7.2) |
| « erreur de flux : Erreur critique, le stream devrait prendre fin » | **téléphone occupé par un autre agent** (verrou `~/bin/phone`), ou caméra déjà prise | `~/bin/phone status`, attendre la libération, **relancer** : après libération, ça passe (§10) |
| **429 `capacity_saturated`** (« every provider within your price limit is busy ») | **fournisseur LLM saturé**, pas l'app | changer `llm.model` dans `local.properties` (ex. `gemini-3.8-flash`, qui a répondu 200), rebâtir (§4) |
| Écran sans aucune frame | flux jamais démarré, caméra non attachée, lunettes non portées | lire `flux : <état>`, `caméra refusée`, `frame non encodable` (`GlassesDeviceSession.kt:63`, `:51` ; `GlassesFramePump.kt:52`) ; côté mock, `powerOn`/`don`/`unfold` (`MockGlassesHarness.kt:149-153`) |
| `Permission CAMERA refusée : pas de flux caméra du téléphone` | le flux simulé utilise la caméra arrière **du téléphone** | accorder `CAMERA` (`MainActivity.kt:218-222`) ou passer à `useVideoFeed` (`MockGlassesHarness.kt:75`, `:100`) |
| Analyse absente ou sans objet | `sam31.endpoint` vide (segmentation désactivée, normal) | poser l'endpoint (GPU Modal) ou accepter le LLM seul (`MainActivity.kt:209`) ; sinon lancer avec `--ez analyze true` ou appuyer sur **« Analyser »** |
| Voix muette | pas de routage audio lunettes, ou clé TTS absente | `routeToGlasses()` retombe **silencieusement** sur le haut-parleur sans `MODIFY_AUDIO_SETTINGS` (`GlassesVoice.kt:29`) ; `voix : clé ElevenLabs absente` (`:84`) |
| Lancement refusé : « téléphone occupé … » | le verrou `~/bin/phone` est tenu par un autre agent | `~/bin/phone status`, attendre, relancer |

Conduite quand ça résiste : **vérifier l'état réel du paquet** (`pm path`, `versionCode`, `codePath`)
avant de réinstaller — c'est ce que fait `--dry` (`phone-lunettes.sh:121-124`), lecture seule.

| `voix : backend injoignable : réseau` / `analyse échouée` alors que le faux serveur répond | l'UID de l'app ne résout aucun nom sur ce téléphone (restriction réseau par app), cf. §7.3 | `adb shell run-as dev.sam3vision.glasses ping -c1 api.inferhub.dev` : si échec alors que `ping` en shell réussit → lever la restriction MIUI de l'app |

## 10. Non mesuré ici

Ce qui n'a **pas** été observé le 26/10/2026, et qui ne doit pas être présenté comme acquis :

- **Aucune paire de Ray-Ban Meta réelle** : **0 appareil Bluetooth appairé** sur le téléphone. Tout ce
  qui précède repose sur MockDeviceKit.
- **Aucune segmentation SAM 3.1 réelle** : la **GPU Modal n'a pas été allumée** (règle maison : jamais
  de sa propre initiative). La segmentation vue le 26/10 est celle du faux serveur local (§7).
- **Aucun routage audio vers les lunettes** : le son est reparti sur le haut-parleur du téléphone.
- **La chaîne complète SAM→LLM→voix n'a pas encore été observée aboutir** sur le téléphone : l'essai
  s'est arrêté à l'appel SAM, bloqué par le **trafic en clair** (§7.2). Corrigé depuis par
  `network_security.xml`, **à rejouer**.
- **Le build de l'application principale `:app`** n'a été **ni construit ni installé** : seuls
  `:glasses-spike` et `:core` ont été compilés.
- **L'app Meta AI v295 est bien installée** (constat orchestrateur) mais **aucune paire de lunettes
  n'est appairée** ; Developer Mode et firmware ≥ v128 restent **non vérifiés** (constats externes
  antérieurs).
- **Le téléphone est partagé** (verrou `~/bin/phone`) : pendant qu'un autre agent le tenait, le flux
  a lâché sur « erreur de flux : Erreur critique, le stream devrait prendre fin » et le lancement a
  même été refusé (« téléphone occupé … »). **Relancer après libération : ça passe.**
- **Latence bout en bout, débit Bluetooth, optique, thermique/autonomie** : non mesurés (hors mock).
- Le **404 backend** est un symptôme d'orchestrateur, **non reproduit** ici.

## 11. Fichiers cités

| Fichier | Rôle |
|---|---|
| `glasses-spike/…/MainActivity.kt` (269 l.) | extras, lancement, journal, pipeline, permissions |
| `glasses-spike/…/SpikeLaunch.kt` (74 l.) | **décision** de lancement : extras lus, ordre des étapes (`ARM_ANALYZE` avant `START_SESSION`) |
| `glasses-spike/…/GlassesAnalysisStep.kt` (51 l.) | maillon frame → analyse → voix (`frame …`, `objets :`, `voix :`, `analyse échouée :`) |
| `glasses-spike/…/MockGlassesHarness.kt` (188 l.) | MockDeviceKit : activation, appariement, source vidéo |
| `glasses-spike/…/GlassesSession.kt` (83 l.), `GlassesDeviceSession.kt` (74 l.), `GlassesFramePump.kt` (57 l.), `GlassesSessionPolicy.kt` (157 l.) | cycle DAT, session/caméra/flux, collecte et étranglement, `FRAME_THROTTLE_MS = 1_500L` (`GlassesSessionPolicy.kt:22`) |
| `glasses-spike/…/GlassesVoice.kt` (253 l.) | ElevenLabs + routage audio des lunettes |
| `glasses-spike/build.gradle.kts` (100 l.), `src/main/AndroidManifest.xml`, `src/main/res/xml/network_security.xml` | clés → `BuildConfig`, `versionCode = 1` ; permissions, deep-link `samglasses` ; clair local |
| `core/…/settings/Settings.kt`, `core/…/usecase/UseCases.kt`, `core/…/net/HttpSamClient.kt` | `segmentationEnabled` (`:119`), `llmConfigured` (`:122`) ; pipeline optionnel ; contrat `/health` + `/segment` |
| `tools/r630-build.sh`, `tools/phone-lunettes.sh`, `tools/mock-sam.py`, `tools/test-mock-sam.sh` | build r630, install + lancement + preuves, faux serveur SAM, banc hors téléphone |
| `docs/glasses-dat-spike.md`, `docs/META-GLASSES.md`, `docs/glasses-spike-ci.md` | relevé SDK, prérequis datés, coût CI |
| `ref/BUILD-SERVEUR-TEST-PHONE.md` | montage build r630 / test téléphone, pièges déjà payés |

## 12. Preuves

Captures du 26/10/2026 (téléphone `2c6852ac`, build r630) dans `~/preuves/lunettes/<horodatage>/` :

| Fichier | Contenu |
|---|---|
| `logcat.txt` | journal `GlassesSpike` (`adb logcat -d -s GlassesSpike:V`, écrit par `phone-lunettes.sh:105`) |
| `ecran.png` | capture de l'écran du spike (`phone-lunettes.sh:108`) |
| `version.txt` | `versionName` / `versionCode` / `codePath` du paquet installé (`phone-lunettes.sh:106-107`) |
| `reverse.txt` | tunnels `adb reverse` en cours (port 8099 vers le faux SAM) |

Le **journal du faux serveur SAM** est celui affiché par `tools/mock-sam.py` — une ligne par
requête : horodatage, méthode, chemin, taille reçue, nombre de masques, durée en ms
(`mock-sam.py:212-217`) ; le banc `tools/test-mock-sam.sh` en réaffiche les dernières lignes
(`test-mock-sam.sh:241-244`).
