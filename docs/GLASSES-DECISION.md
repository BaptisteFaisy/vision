# Lunettes Meta — l'arbitrage tranché

**Décision écrite le 26/10/2026** dans l'arbre `BaptisteFaisy/vision` (commit d'import `790ce7fb`),
sur la question restée ouverte au plan (A-Z § 7.2) : « version lunettes Meta ? ». Chaque affirmation
porte son `fichier:ligne`, sa mesure ou sa provenance datée. Rien n'a été construit, installé ni
essayé sur du matériel pour écrire ce document : **aucune paire de lunettes n'existe sur ce poste**.

Sources lues (pas citées de mémoire) : `app/src/main/java/dev/sam3vision/app/glasses/` (7 fichiers),
`glasses-spike/` (21 `.kt`), `core/src/main/java/dev/sam3vision/core/glasses/CaptureGesture.kt`,
`docs/META-GLASSES.md`, `docs/glasses-dat-spike.md`, `docs/glasses-spike-ci.md`,
`app/src/main/AndroidManifest.xml`, `app/src/main/java/dev/sam3vision/app/AppNav.kt`,
`~/Bureau/sam-vision-chats/pages/pc-P15.md` (chat antérieur, hors de cet arbre) et le maître
`~/Bureau/SAM-VISION-A-Z.md` (§ 5 bis, § 5 ter, § 7.2, règle 34).

## Décision

**Le DAT reste fondu dans `:app`, et `:glasses-spike` reste un module séparé, hors de l'APK de
production.** Les deux moitiés de la phrase sont déjà vraies dans cet arbre, et c'est ce qui est
tranché — pas un chantier à ouvrir :

- le chemin lunettes de production vit dans `:app` : **7 fichiers** sous
  `app/src/main/java/dev/sam3vision/app/glasses/` (mesuré ici), la logique pure des appuis dans
  `:core` (`core/…/glasses/CaptureGesture.kt`) et le pont photo → analyse/chat dans `AppNav.kt:89-100`
  (`GlassesBridge`) ;
- `:app` **ne dépend pas** de `:glasses-spike` : `app/build.gradle.kts` ne contient aucun
  `project(":glasses-spike")` (mesuré), et le module est un `com.android.application`
  (`glasses-spike/build.gradle.kts`), pas une bibliothèque. Il est déclaré une seule fois
  (`settings.gradle.kts:20`) et ne part donc dans aucun APK livré ;
- le fondre dans `:app` n'apporterait **aucune** fonction de production et coûterait deux choses :
  `mwdat-mockdevice` (déjà en `implementation` du spike sous `TODO(spike)`,
  `glasses-spike/build.gradle.kts:76`) et ≈ 2 400 lignes de banc d'essai (mesure : 21 `.kt`,
  2 396 lignes).

⚠️ **L'argument `minSdk` de l'arbitrage d'origine est mort** : il opposait un spike en 29 à un `:app`
en 26. Mesuré ici : `:app` est **déjà** en `minSdk = 29` (`app/build.gradle.kts:84`), `:core` reste en
26 (`core/build.gradle.kts:13`) et le spike en 29 (`glasses-spike/build.gradle.kts:28`). La raison de
garder le module à part n'est donc plus le plancher d'API : c'est qu'il est un **banc de preuve**
(convertisseur YUV, harnais de test DAT, mock device), pas un chemin de production. La décision ne
change pas de sens pour autant : **on ne fond pas le spike, et on ne rétrécit pas le motif de
périmètre CI** qui le compile (règle 34 : un rétrécissement le rendrait vert par absence de test).

Ce qui n'est **plus** une décision d'architecture : acheter des Ray-Ban pour la recette réelle et
dire si `:glasses-spike` survit dans le dépôt. Ce document tranche le second point (**oui**) ; le
premier est un acte user, hors code.

## Écrans

Deux écrans existants sont réutilisés, deux sont explicitement écartés aujourd'hui :

| Écran candidat | Verdict | Preuve |
|---|---|---|
| `AnalysisScreen` | **réutilisé** — appui court sur le bouton capture → photo → analyse (masques SAM + description) | `AppNav.kt:96` : `push(if (ASK) Route.Chat else Route.Analysis)` ; `Route.Analysis -> AnalysisRoute` (`AppNav.kt:69`) |
| `ChatScreen` | **réutilisé** — appui long (`HOLD`) → photo → question posée sur le téléphone | `AppNav.kt:96` ; `Route.Chat -> ChatRoute` (`AppNav.kt:71`) ; gestes dans `core/…/glasses/CaptureGesture.kt` |
| `LiveScreen` / `LiveViewModel` | **non portés** — le double appui ne lance rien et le dit à l'écran | `GlassesController.kt:180` : « Mode Direct : pas encore porté sur lunettes, il reste sur le téléphone. » |
| écran des lunettes (Ray-Ban Display) | **écarté** — aucun rendu n'est envoyé aux lunettes | le Direct est le seul chemin écran, et il n'est pas porté ci-dessus |

Le chemin image est **le même que celui d'une capture téléphone** : la photo des lunettes est écrite
en JPEG dans le cache interne (`app/…/glasses/GlassesFrames.kt`, `writeJpegToCache`) puis poussée par
`GlassesBridge` dans `AppViewModel.onPhoto`, exactement comme une photo prise à la main. Conséquence
assumée : **les masques et les distances s'affichent sur l'écran du téléphone**, et la lunette sert
d'œil et d'oreille, pas de viseur.

Deux points ouverts, non tranchés ici parce qu'ils demandent du matériel : le Direct sur lunettes
(cadence et rendu) et la restitution des masques dans le champ de vision.

## Cadence

**Décision : `:app` reste à 15 i/s, qualité `MEDIUM`, flux compressé.** C'est le code livré, et il est
justifié par ce que l'app fait vraiment : elle **ne consomme aucun flux vidéo** et prend une photo à
la demande.

- demandé par `:app` : `frameRate = 15`, `VideoQuality.MEDIUM`, `compressVideo = true`
  (`app/…/glasses/GlassesCamera.kt:43-48`, constante `FRAME_RATE` ligne 80) ;
- le commentaire du code pose la règle (« au-delà, le lien Bluetooth Classic casse l'image ») : c'est
  un **constat matériel non remesuré ici**, aucune lunette n'étant disponible ;
- le spike, lui, demande **24** (`glasses-spike/…/GlassesDeviceSession.kt:12`, `:44-47`), qui est
  exactement le **défaut du SDK** relevé au bytecode, avec `compressVideo = false` (YUV brut) et un
  étranglement de sortie à une frame / 1,5 s, soit ≈ **0,67 image/s** utiles : une conclusion du spike
  ne se transpose pas à l'app, et réciproquement.

Le choix se lit sur les cinq valeurs acceptées par le lien (2 / 7 / 15 / 24 / 30 i/s, avec un ladder
automatique qui dégrade jusqu'à 15 ; provenance : doc Meta citée par le maître, **non revérifiée
ici**) :

- **2** : plancher du lien, jamais demandé par l'app (elle ne streame pas) ;
- **7** : intermédiaire du ladder, inutile tant qu'aucun flux n'est consommé ;
- **15** : **retenu** — c'est la borne basse du ladder et ce que demande déjà le code livré ;
- **24** : défaut SDK, demandé par le spike seulement ; à ne pas reprendre tel quel pour l'app ;
- **30** : plafond du lien, **jamais demandé** ; une permanence à 15 i/s est déjà chiffrée trop cher
  au § Spéculatif.

Cible pour un futur Direct lunettes, si le portage est décidé : **5–8 images/s utiles bout en bout**
(lunettes → téléphone → backend), pas 30 — la conversion, l'encodage et le réseau sont plus lents que
le lien.

## Matériel

**Cible retenue : Ray-Ban Meta de deuxième génération, sans écran.** La réponse est lue dans les
oreilles (TTS de l'app sur la sortie du téléphone, aucun routage Bluetooth branché dans `:app`), et
les masques restent sur l'écran du téléphone.

- écarté : **Ray-Ban Display** — rendu dans le champ de vision, donc +3–4 semaines d'estimation, et
  les Web Apps du Display n'ont pas d'accès caméra (utiles à l'affichage, pas à SAM) ;
- écartées aussi comme pistes non réalisées : l'invocation vocale « Hey Meta, … » demande un projet
  dans le Wearables Developer Center et une **version** portant la permission (aucune occurrence de
  `VoiceInvocation` dans cet arbre), et le micro des lunettes comme source audio est une capacité
  beta **non revérifiée** ;
- prérequis d'une recette réelle, imposés mais non testés ici : téléphone **Android 10+** (API 29,
  imposé par les AAR Meta), app **Meta AI ≥ v292**, firmware lunettes **≥ v128**, **Developer Mode**
  activé, une **seule** app tierce à la fois en Developer Mode ;
- recalibrage : le mock (Mock Device Kit) **ne prouve ni** latence Bluetooth, ni optique, ni routage
  audio, ni thermique/batterie ; aucune phrase « ça marche avec les lunettes » n'est écrite ici sans
  une paire réelle.

## Coût & durée

Ce qui est **déjà payé** dans le code (et n'est donc plus une estimation) : le DAT est fondu dans
`:app`, le pont photo → SAM/LLM tourne sur le même chemin JPEG 1024 px / q85 que le téléphone
(`app/…/ImageSource.kt`), et la logique des gestes est testée en JVM dans `:core`. La position
« adaptateur, pas refonte » est un **fait mesuré dans cet arbre**, pas une projection.

Estimations de la page 15 (chat antérieur, hors de cet arbre, **non revérifiées**, reprises telles quelles) :

| Poste | Estimation |
|---|---|
| MVP sans écran (audio + capture) | **7–12 j**, dont compte dev + Developer Center + lunettes + mode dev 0,5–1 j, intégration DAT Android 2–3 j, pont images → SAM/LLM 1–2 j, voix TTS + micro demi-duplex 1–2 j, terrain 2–3 j |
| avec Ray-Ban Display | **~3–4 semaines** (dont 5–8 j de rendu overlay + input) |

Coûts d'exploitation et de CI :

- **CI** : `:glasses-spike` coûte **+18 à 33 s** au job `ci` (A-Z § 5 ter) ; dans cet arbre il a son
  propre job `spike` (`timeout-minutes: 5`, `.github/workflows/ci.yml:215-227`) mesuré à **50 s** en
  CI le 26/10 — sous le budget de 5 min. Règle : jamais de `lintDebug` ni d'`assembleRelease` dans ce
  module, jamais de job ajouté ;
- **backend** : rien à allumer pour cette mission — **0 €**, aucune GPU, aucun `modal deploy`. Le
  projet Baseten de SAM 3.1 (`/home/baptiste/tmp/sam31-baseten`) vise une **A10G à 1,21 $/h**,
  facturée en service seulement, et reste **bloqué par un moyen de paiement et une clé absents** (voir
  la page 15).

## Spéculatif

Tout ce qui suit **n'a pas été mesuré ici**. Un chiffre non mesuré s'écrit « non mesuré », jamais
inventé.

| Affirmation | Provenance | Statut |
|---|---|---|
| `4 600 lignes de Kotlin` pour l'app actuelle | page 15, marqué « à confirmer » | **contredit, ne pas reprendre.** La mesure du maître (§ 5 bis, relevé antérieur, hors de cet arbre) donne **137 `.kt`, 12 643 l, 247 `@Test`**. Mesure prise dans cet arbre le 26/10 : **363 `.kt`, 37 329 l, 1 001 `@Test`** (`:core` 162/16 702, `:app` 180/18 231, `:glasses-spike` 21/2 396) |
| `7–12 j` MVP sans écran, `~3–4 semaines` avec Display | page 15 | estimation, **jamais mesurée** |
| `~350–400 €` Ray-Ban Meta Gen 2, `~800 $` Display | page 15 | prix relevés hors de cet arbre, **non revérifiés** |
| cadences acceptées 2 / 7 / 15 / 24 / 30 i/s et ladder à 15 i/s | doc Meta citée par le maître | donnée externe, **non revérifiée** |
| `A10G` à `1,21 $/h`, facturée en service, scale-to-zero | page 15 | donnée externe |
| 15 i/s permanent = 54 000 frames/h = 69 % d'une A100 = 1 512 $/mois | chat cité par le maître | estimation, **non recalculée sur facture** |
| `VideoFormat.getDefaultFormat()` = H.265, 504×896, 30 i/s | bytecode des AAR 1.0.1 (`javap`, analyse antérieure) | prouvé au bytecode, **non revérifié dans cet arbre** |
| dimensions `MEDIUM` annoncées par le SDK | bytecode | format de flux, pas une mesure optique |
| I420 vs NV12 et padding de ligne réel | — | **non vérifié** ; l'ambiguïté est mesurée (un NV12 serré ≡ un I420 serré) |
| latence, débit, pertes de frames sur le lien Bluetooth | — | **non mesuré** (aucune lunette) |
| routage audio vers les lunettes | code du spike seulement (`GlassesVoice.kt`) | jamais exécuté, **non vérifié** |
| thermique, batterie, optique, mise en pause sur retrait | — | **non mesuré** |
| alternatives matérielles (Brilliant Labs Frame, Even Realities G1, RayNeo/Rokid) | page 15 | liste externe, **non revérifiée** |
| durée d'un build ou d'une session réels sur lunettes | — | **non mesuré** ; les chiffres de CI cités sont des relevés datés du 26/10 |