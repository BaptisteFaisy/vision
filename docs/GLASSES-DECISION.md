# Lunettes Meta — l'arbitrage tranché

**Réécrit le 2026-10-27.** La décision du 26/10/2026 — « le DAT reste fondu dans `:app` **et**
`:glasses-spike` reste un module séparé » — est **remplacée** : le banc d'essai lunettes vit
désormais **dans `:app`** et le module `:glasses-spike` est **supprimé**.

Chaque affirmation porte son `fichier:ligne`, sa mesure ou sa provenance datée. Aucune paire de
lunettes n'existe sur ce poste : tout ce qui exige du matériel est marqué **non mesuré**. Les
relevés de cette page sont datés du 2026-10-27 et refaits sur l'arbre
`~/isee-wt/glasses-bench` (branche `glasses-bench`), jamais sur un build : aucun `gradlew`
n'a été lancé.

## 1. La décision (2026-10-27)

**Une seule app, un seul paquet : `dev.sam3vision.app`.** Le banc d'essai « SAM Vision pour les
lunettes » devient un paquet interne de `:app` (`dev.sam3vision.app.glasses.bench`) et le module
Gradle `:glasses-spike` disparaît. Provenance : `CONTRAT.md` de cet arbre, décision du
2026-10-27.

### Pourquoi — trois motifs, dans cet ordre

1. **Un seul identifiant d'application chez Meta.** Le SDK DAT identifie l'app appelante par
   deux `meta-data` du manifeste, `com.meta.wearable.mwdat.APPLICATION_ID` et `.CLIENT_TOKEN`
   (`app/src/main/AndroidManifest.xml:41-47`), injectés par Gradle depuis `local.properties`.
   Ces valeurs se déclarent **par paquet** dans la console Meta Wearables : un second APK au
   paquet `dev.sam3vision.glasses` était une **seconde** déclaration à tenir à jour, avec son
   propre enregistrement à refaire. Un seul paquet = une seule déclaration, donc une seule
   vérité entre « l'app iSee » et « l'app du banc » — la confusion que la décision supprime.
2. **Une seule app installée.** Un banc livré comme application séparée s'installe à côté
   d'iSee et se lance par sa propre icône : deux applis sur le téléphone de test, deux
   `versionName`, deux états d'appairage possibles. Une seule app installée lève l'ambiguïté
   au niveau du système, pas seulement dans la doc.
3. **Le banc reste un banc.** Le déplacement ne promeut rien : `bench/` n'est pas une seconde
   voie de production. La production garde son chemin, dans `:app`, sous
   `app/src/main/java/dev/sam3vision/app/glasses/` (§2) ; `bench/` porte l'outillage de preuve
   (session DAT réelle, appareil simulé, pompe d'images, encodage/analyse, voix, journal) et
   n'est atteint que depuis l'écran Lunettes, jamais par une icône ni une `Activity` exportée
   (`CONTRAT.md`, « Cible fonctionnelle »).

## 2. Ce qui reste vrai — et une correction

- Le chemin lunettes de **production** vit dans `:app` : **12 fichiers** `.kt` mesurés sous
  `app/src/main/java/dev/sam3vision/app/glasses/` (+ le sous-dossier `bench/`, donc
  `git ls-tree --name-only HEAD …/glasses/ | wc -l` → 13). L'ancienne version de cette
  page annonçait **7** fichiers : le chiffre ne tient pas contre cet arbre, même au
  commit de départ.
- La logique pure des appuis reste dans `:core` :
  `core/src/main/java/dev/sam3vision/core/glasses/CaptureGesture.kt` (unique fichier du
  dossier), testée là-bas.
- Le pont photo → analyse/chat reste `GlassesBridge` (`app/src/main/java/dev/sam3vision/app/AppNav.kt:92`,
  définition `:128`).
- `minSdk = 29` dans `:app` (`app/build.gradle.kts:84`), imposé par les AAR `mwdat-*` ; `:core`
  reste une bibliothèque (`core/build.gradle.kts`). L'argument `minSdk` de l'arbitrage
  d'origine est donc mort depuis longtemps : il opposait un spike en 29 à un `:app` en 26, ce
  qui n'est plus le cas.
- La cadence de production reste **15 i/s, `MEDIUM`, `compressVideo = true`**
  (`app/src/main/java/dev/sam3vision/app/glasses/GlassesCamera.kt:61-63`, `FRAME_RATE = 15`
  ligne 129) : le banc demande autre chose (24 i/s, §4), et cela ne change pas la production.

## 3. Migration : ancien → nouveau chemin

Le contenu du module supprimé a été copié dans `:app`, paquet renommé. Le tableau dit la
**correspondance de chemins** et ce qui a été contrôlé sur le disque le 2026-10-27 ; il ne
prétend pas que les fichiers sont restés identiques depuis — le banc évolue (voir la note
après le tableau). Au moment du relevé, les deux arbres existaient encore côte à côte ;
depuis, le module a disparu du disque : seule cette page garde la correspondance.

| Ancien chemin (`:glasses-spike`, supprimé) | Nouveau chemin dans `:app` | Contrôle effectué le 27/10 |
|---|---|---|
| `glasses-spike/src/main/java/dev/sam3vision/glasses/*.kt` — **17** fichiers, 1 824 l. | `app/src/main/java/dev/sam3vision/app/glasses/bench/` | 17 destinations ; relevé de 11 h 15 : **12 identiques** au `.kt` source au `package` près, 3 adaptés (`SpikeLaunch`, `GlassesVoice`, `GlassesAudioRoute`), 2 remplacés par l'écran du banc |
| `glasses-spike/src/debug/java/dev/sam3vision/glasses/*.kt` — **2**, 243 l. | `app/src/debug/java/dev/sam3vision/app/glasses/bench/` | 2 destinations (`MockControl.kt`, `MockGlassesHarness.kt`), adaptées au passage |
| `glasses-spike/src/release/java/dev/sam3vision/glasses/*.kt` — **1**, 24 l. | `app/src/release/java/dev/sam3vision/app/glasses/bench/` | 1 destination (`MockControl.kt`) |
| `glasses-spike/src/test/java/dev/sam3vision/glasses/*.kt` — **9**, 1 239 l. | `app/src/test/java/dev/sam3vision/app/glasses/bench/` | 9 destinations ; **8 identiques** au `.kt` source au `package` près |
| `glasses-spike/src/androidTestDebug/java/dev/sam3vision/glasses/*.kt` — **1**, 158 l. | `app/src/androidTestDebug/java/dev/sam3vision/app/glasses/bench/` | 1 destination (`MockGlassesTest.kt`) |
| paquet `dev.sam3vision.glasses` | paquet `dev.sam3vision.app.glasses.bench` | 20 déclarations `package` sous `bench/` (`main` + `debug` + `release`), toutes `dev.sam3vision.app.glasses.bench` |
| `glasses-spike/src/androidTest/assets/mock_feed.mp4` (54 964 o) | `app/src/androidTest/assets/mock_feed.mp4` | présent, **54 964 o** des deux côtés |
| `glasses-spike/src/test/resources/frame-64.jpg` | *aucune destination (plus nécessaire)* | **résolu** : le JPEG 64×64 est embarqué en base64 (`ChaineCompleteTest.kt:199-228`, `FRAME_JPEG_B64`), plus aucune ressource externe à charger |
| `glasses-spike/src/main/res/values/themes.xml`, `…/res/xml/network_security.xml` | non copiés : `:app` a déjà les siens | `app/src/main/res/values/themes.xml`, `app/src/main/res/xml/network_security.xml` |
| `glasses-spike/src/main/AndroidManifest.xml` (activité du banc, schéma `samglasses`) | pas de manifeste de banc : un écran dans la navigation d'iSee | `MODIFY_AUDIO_SETTINGS` ajouté (`app/src/main/AndroidManifest.xml:22`) ; le seul schéma déclaré par `:app` est `samvision` (`:62`) ; aucune activité de banc au manifeste |
| `glasses-spike/build.gradle.kts` (module `application`, `versionName "0.1-spike"`) | plus rien à créer : `:app` porte déjà `buildConfigField` et `mockdevice` | `app/build.gradle.kts:103-125` (LLM/TTS/SAM31/SAM) et `:186` (`libs.mwdat.mockdevice` en `debugImplementation`) |
| job CI `spike` (`:glasses-spike:assembleDebug testDebugUnitTest`) | aucun job dédié : le banc compile et se teste dans les jobs de `:app` | `.github/workflows/` : **0** occurrence de `glasses-spike`, les jobs (`ci-core`, `ci-app`, `apk`, `lint`), périmètres `^(app\|core)/…` + `^server/` (action `perimetre`) |

Le module totalisait **30 `.kt` / 3 488 lignes** (17 + 2 + 1 + 9 + 1 fichiers). L'ancienne
version de cette page citait « 21 `.kt`, 2 396 lignes » : compte d'avant l'éclatement en
`debug` / `release` / `androidTestDebug`, périmé contre cet arbre.

**Le banc ne cesse pas de bouger pour autant.** Au relevé de 11 h 10, les deux arbres avaient
les mêmes 30 fichiers et les mêmes 3 488 lignes ; à 11 h 15, `bench/` comptait 18 `.kt` en
`main` : l'activité `MainActivity.kt` et `GlassesApp.kt` ont été remplacées par un écran
Compose `BenchScreen.kt` et un contrat de simulation `BenchMock.kt`. C'est le comportement
attendu d'un banc vivant : le tableau fixe les **chemins**, pas la taille des fichiers.

## 4. Ce que le banc demande au SDK, et pourquoi ça ne contamine pas la production

Le banc est un **banc de preuve** : il pousse le SDK dans ses retranchements pour savoir ce
qu'il livre vraiment, pas pour livrer.

| | production (`glasses/`) | banc (`glasses/bench/`) |
|---|---|---|
| cadence demandée | **15** (`GlassesCamera.kt:129`, `:62`) | **24** (`bench/GlassesDeviceSession.kt:12`, `:46`) — soit le défaut du SDK |
| compression | `compressVideo = true` (`GlassesCamera.kt:63`) | autre choix, avec son propre encodeur (`bench/GlassesFrameEncoder.kt`) |
| trames vidéo | **jamais** consommées (0 occurrence de `videoStream` hors `bench/`, mesuré) | pompées et étranglées à une frame / 1,5 s (`bench/GlassesFramePump.kt:38-44`, `bench/GlassesSessionPolicy.kt:23`) |
| audio | piste PCM demandée par palier (`GlassesAudioPlan`, `GlassesCamera.kt:43-44`) ; voix sortie dans la paire en session (`GlassesVoiceRoute`, `SpeechPlayer.preferredDevice`), haut-parleur en repli | routage explicite avant chaque phrase (`bench/GlassesVoice.kt`, `routeToGlasses`), même décision partagée (`glasses/GlassesAudioRoute.kt`) |
| conversion d'image | aucune (`PhotoData.toBitmapOrNull()`, `glasses/GlassesFrames.kt:40`) | convertisseur YUV écrit pour le banc (`bench/FrameTypes.kt:76`, `:134`) |

Conséquence, inchangée depuis le 26/10 : **aucun chiffre du banc ne se transpose à la
production**, et réciproquement. La proximité des deux paquets ne crée pas de dépendance — elle
les rend seulement plus faciles à comparer.

## 5. Le câblage : un écran d'iSee, pas une seconde app

La cible du `CONTRAT.md` est tenue par la navigation d'iSee, pas par un manifeste : le banc est
une **route** (`Route.Bench`, `app/src/main/java/dev/sam3vision/app/AppNav.kt:46`) rendue par
`BenchScreen` (`AppNav.kt:105`), ouverte depuis le bloc « Lunettes » des Réglages par une
`CompositionLocal` (`LocalBenchOpener`, `glasses/GlassesSection.kt:23`, consommée ligne 91,
fournie par `AppNav.kt:107`). Conséquences, toutes vérifiables :

- **aucune `Activity` de banc au manifeste** : `app/src/main/AndroidManifest.xml` ne déclare que
  `.MainActivity` (`:49`), et une seule activité exportée — la seule app installée, la seule
  icône ;
- **aucun schéma dédié** : `:app` ne déclare que `samvision` (`AndroidManifest.xml:62`) ; le
  retour d'enregistrement de l'app Meta AI revient donc à iSee, qui est bien l'app enregistrée ;
- **le banc ne peut pas être lancé par `adb am start`** sur un paquet séparé : il n'existe plus.

Un écart mesuré au 27/10, **corrigé depuis** :

- **`frame-64.jpg` manquait** — résolu sans copie : le JPEG 64×64 est embarqué en base64
  (`ChaineCompleteTest.kt:199-228`, `FRAME_JPEG_B64`), le test ne charge plus rien sur le
  classpath.

## 6. Écrans

Deux écrans existants sont réutilisés, deux sont explicitement écartés aujourd'hui :

| Écran candidat | Verdict | Preuve |
|---|---|---|
| `AnalysisScreen` | **réutilisé** — appui court sur le bouton capture → photo → analyse (masques SAM + description) | `GlassesBridge` (`AppNav.kt:92`) pousse `Route.Analysis` ou `Route.Chat` selon l'appui |
| `ChatScreen` | **réutilisé** — appui long (`HOLD`) → photo → question posée sur le téléphone | idem ; gestes dans `core/…/glasses/CaptureGesture.kt` |
| `LiveScreen` / `LiveViewModel` | **non portés** — le double appui ne lance rien et le dit à l'écran | `GlassesMessages.liveNotOnGlasses` : « Mode Direct : pas encore porté sur lunettes, il reste sur le téléphone. » (`GlassesPolicy.kt:97`) |
| écran des lunettes (Ray-Ban Display) | **écarté** — aucun rendu n'est envoyé aux lunettes | le Direct est le seul chemin écran, et il n'est pas porté ci-dessus |

Le chemin image est **le même que celui d'une capture téléphone** : la photo des lunettes est
écrite en JPEG dans le cache interne (`glasses/GlassesFrames.kt:49`, `writeJpegToCache`) puis
poussée par `GlassesBridge` dans `AppViewModel.onPhoto`, exactement comme une photo prise à la
main. Conséquence assumée : **les masques et les distances s'affichent sur l'écran du
téléphone**, et la lunette sert d'œil et d'oreille, pas de viseur.

Deux points ouverts, non tranchés ici parce qu'ils demandent du matériel : le Direct sur
lunettes (cadence et rendu) et la restitution des masques dans le champ de vision.

## 7. Cadence

**Décision : la production reste à 15 i/s, qualité `MEDIUM`, flux compressé.** C'est le code
livré, et il est justifié par ce que l'app fait vraiment : elle **ne consomme aucun flux
vidéo** et prend une photo à la demande.

- demandé par `:app` : `videoQuality = MEDIUM`, `frameRate = FRAME_RATE` (15),
  `compressVideo = true` (`glasses/GlassesCamera.kt:61-63`, constante ligne 129) ;
- le commentaire du code pose la règle (« au-delà, le lien Bluetooth Classic casse l'image ») :
  c'est un **constat matériel non remesuré ici**, aucune lunette n'étant disponible ;
- le banc, lui, demande **24** (`bench/GlassesDeviceSession.kt:12`, `:46`), qui est exactement
  le **défaut du SDK** relevé au bytecode, avec un étranglement de sortie à une frame / 1,5 s,
  soit ≈ **0,67 image/s** utiles : une conclusion du banc ne se transpose pas à l'app, et
  réciproquement.

Le choix se lit sur les cinq valeurs acceptées par le lien (2 / 7 / 15 / 24 / 30 i/s, avec un
ladder automatique qui dégrade jusqu'à 15 ; provenance : doc Meta citée par le maître,
**non revérifiée ici**) :

- **2** : plancher du lien, jamais demandé par l'app (elle ne streame pas) ;
- **7** : intermédiaire du ladder, inutile tant qu'aucun flux n'est consommé ;
- **15** : **retenu** — c'est la borne basse du ladder et ce que demande déjà le code livré ;
- **24** : défaut SDK, demandé par le banc seulement ; à ne pas reprendre tel quel pour l'app ;
- **30** : plafond du lien, **jamais demandé** ; une permanence à 15 i/s est déjà chiffrée trop
  cher au § Spéculatif.

Cible pour un futur Direct lunettes, si le portage est décidé : **5–8 images/s utiles bout en
bout** (lunettes → téléphone → backend), pas 30 — la conversion, l'encodage et le réseau sont
plus lents que le lien.

> **Amendement 2026-11-01 (direct écran)** : le § ci-dessus vise la cadence **utile bout en
> bout** (lunettes → backend), pas l'affichage local. Pour l'écran du téléphone, le user
> exige le maximum (60 i/s si le lien le tient, 30 minimum) : `GLASSES_FRAME_RATE = 60`
> avec échelle de repli 60 → 30 → 24 → 15 (`GLASSES_FRAME_RATE_LADDER`), repli annoncé
> (`GlassesMessages.frameRateFallback`), cadence tenue mesurée (`LUNETTES-RECETTE-TEL` §3).
> 60 n'est pas dans les cadences citées par la doc Meta : c'est un essai, le repli fait foi.
> Mesuré 01/11/2026 : 60 acceptés sans repli, ~30 i/s émis, ~24.5 i/s rendus
> (plafond CPU), B2 p95 111 ms — voir `LUNETTES-RECETTE-TEL.md` §7.

## 8. Matériel

**Cible retenue : Ray-Ban Meta de deuxième génération, sans écran.** La réponse est lue dans les
oreilles : TTS de l'app, dans les lunettes quand la session est ouverte (`GlassesVoiceRoute`,
repli haut-parleur), et les masques restent sur l'écran du téléphone.

- écarté : **Ray-Ban Display** — rendu dans le champ de vision, donc +3–4 semaines
  d'estimation, et les Web Apps du Display n'ont pas d'accès caméra (utiles à l'affichage, pas
  à SAM) ;
- écartées aussi comme pistes non réalisées : l'invocation vocale « Hey Meta, … » demande un
  projet dans le Wearables Developer Center et une **version** portant la permission (aucune
  occurrence de `VoiceInvocation` dans le code de cet arbre), et le micro des lunettes est une capacité
  beta **jamais exécutée ici** — le code existe pourtant des deux côtés de la frontière : la
  production collecte la piste audio du flux en WAV 16 kHz mono
  (`glasses/GlassesAudio.kt`, plan dans `glasses/GlassesAudioPlan.kt`), et la sortie de la
  voix vers les lunettes est choisie une seule fois (`glasses/GlassesAudioRoute.kt`), pour la
  production comme pour le banc ;
- prérequis d'une recette réelle, imposés mais non testés ici : téléphone **Android 10+**
  (API 29, imposé par les AAR Meta), app **Meta AI ≥ v292**, firmware lunettes **≥ v128**,
  **Developer Mode** activé, une **seule** app tierce à la fois en Developer Mode ;
- recalibrage : le mock (Mock Device Kit) **ne prouve ni** latence Bluetooth, ni optique, ni
  routage audio, ni thermique/batterie ; aucune phrase « ça marche avec les lunettes » n'est
  écrite ici sans une paire réelle.

## 9. Coût & durée

Ce qui est **déjà payé** dans le code (et n'est donc plus une estimation) : le DAT est fondu
dans `:app`, le pont photo → SAM/LLM tourne sur le même chemin JPEG 1024 px / q85 que le
téléphone (`app/src/main/java/dev/sam3vision/app/ImageSource.kt:17-18`), et la logique des
gestes est testée en JVM dans `:core`. La position « adaptateur, pas refonte » est un **fait
mesuré dans cet arbre**, pas une projection.

Estimations de la page 15 (chat antérieur, hors de cet arbre, **non revérifiées**, reprises
telles quelles) :

| Poste | Estimation |
|---|---|
| MVP sans écran (audio + capture) | **7–12 j**, dont compte dev + Developer Center + lunettes + mode dev 0,5–1 j, intégration DAT Android 2–3 j, pont images → SAM/LLM 1–2 j, voix TTS + micro demi-duplex 1–2 j, terrain 2–3 j |
| avec Ray-Ban Display | **~3–4 semaines** (dont 5–8 j de rendu overlay + input) |

Coûts d'exploitation et de CI :

- **CI** : il n'y a **plus** de job `spike` — le fichier `.github/workflows/ci.yml` ne contient
  aucune occurrence de `glasses-spike` et déclare les jobs (`ci-core`, `ci-app`, `ci`, `apk`,
  `lint`), chacun borné (**5 min** en PR, **10 min** hors PR). Le banc est donc compilé et testé
  par les jobs de `:app` (tests JVM du job `ci-app`, `assembleDebug` du job `apk`). Le coût propre au module — job `spike` mesuré à
  **50 s** en CI le 26/10 — disparaît avec lui. Détail et historique :
  [`glasses-spike-ci.md`](glasses-spike-ci.md) ;
- **backend** : rien à allumer pour cette mission — **0 €**, aucune GPU, aucun `modal deploy`.
  Le projet Baseten de SAM 3.1 (`/home/baptiste/tmp/sam31-baseten`) vise une **A10G à
  1,21 $/h**, facturée en service seulement, et reste **bloqué par un moyen de paiement et une
  clé absents** (voir la page 15).

## Spéculatif

Tout ce qui suit **n'a pas été mesuré ici**. Un chiffre non mesuré s'écrit « non mesuré »,
jamais inventé.

| Affirmation | Provenance | Statut |
|---|---|---|
| `4 600 lignes de Kotlin` pour l'app actuelle | page 15, marqué « à confirmer » | **contredit, ne pas reprendre.** Relevé du 2026-10-27 (11 h 10) sur cet arbre, hors module supprimé : **472 `.kt`, 28 706 lignes de `src/main`, 1 380 `@Test`** (`:core` 105 f./11 121 l., 907 tests ; `:app` 164 f./17 585 l. dont le banc 17 f./1 824 l., 466 tests JVM et 7 instrumentés) |
| `7–12 j` MVP sans écran, `~3–4 semaines` avec Display | page 15 | estimation, **jamais mesurée** |
| `~350–400 €` Ray-Ban Meta Gen 2, `~800 $` Display | page 15 | prix relevés hors de cet arbre, **non revérifiés** |
| cadences acceptées 2 / 7 / 15 / 24 / 30 i/s et ladder à 15 i/s | doc Meta citée par le maître | donnée externe, **non revérifiée** |
| `A10G` à `1,21 $/h`, facturée en service, scale-to-zero | page 15 | donnée externe |
| 15 i/s permanent = 54 000 frames/h = 69 % d'une A100 = 1 512 $/mois | chat cité par le maître | estimation, **non recalculée sur facture** |
| `VideoFormat.getDefaultFormat()` = H.265, 504×896, 30 i/s | bytecode des AAR 1.0.1 (`javap`, analyse antérieure) | prouvé au bytecode, **non revérifié dans cet arbre** |
| dimensions `MEDIUM` annoncées par le SDK | bytecode | format de flux, pas une mesure optique |
| I420 vs NV12 et padding de ligne réel | — | **non vérifié** ; l'ambiguïté est mesurée (un NV12 serré ≡ un I420 serré) |
| latence, débit, pertes de frames sur le lien Bluetooth | — | **non mesuré** (aucune lunette) |
| routage audio vers les lunettes | production (`GlassesVoiceRoute` + `SpeechPlayer`) et banc (`bench/GlassesVoice.kt`) compilés, décision testée en JVM | **non entendu dans la paire en production** ; la sortie A2DP de la paire a été constatée au banc le 27/10 |
| thermique, batterie, optique, mise en pause sur retrait | — | **non mesuré** |
| alternatives matérielles (Brilliant Labs Frame, Even Realities G1, RayNeo/Rokid) | page 15 | liste externe, **non revérifiée** |
| durée d'un build ou d'une session réels sur lunettes | — | **non mesuré** ; les chiffres de CI cités sont des relevés datés des 26 et 27/10 |