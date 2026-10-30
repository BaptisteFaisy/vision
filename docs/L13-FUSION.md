# L13 · contrat de fusion — keyframe + propagation locale

Ce document est le plan de fusion de la lane **L13** (mission M57, agent F-integration),
ramené aux **ancres réelles de cet arbre**. Les sept points de fusion viennent du rapport
d'unité `u13` (vague 2, écrit contre un autre dépôt : `LivePump` de `u01`, compositeur de
`u03`, `OverlayLayers`) ; aucun de ces trois symboles n'existe ici, donc chaque point est
retraduit sur le fichier et la ligne qui jouent le même rôle dans **ce** dépôt.

> État au 2026-10-26 : la fusion n'est **pas applicable**. Aucun des livrables dont elle
> dépend — A→B→C→D (M52-M55), budget E (M56), décodage L11 (M46) — n'est présent dans
> l'arbre. Ce document fige
> le contrat pour que les branches s'emboîtent sans conflit ; il ne remplace aucune mesure.

## 1. Ce qui manque, vérifié dans l'arbre

```text
$ ls core/src/main/java/dev/sam3vision/core/         # motion/ keyframe/ local/ absents
audio distance gesture glasses iseedesign live mask model net predict settings speech text usecase
$ grep -rl "MotionSource\|MaskPropagator\|KeyframeScheduler\|LocalOverlayEngine\|WarpPlan\|errorThreshold" --include=*.kt .
(rien)
$ ls docs/MASK-DECODE.md app/src/main/java/dev/sam3vision/app/LiveMaskTransport.kt
Aucun fichier de ce type
```

| dépendance | mission | artefact attendu | état au 27/10 |
|---|---|---|---|
| A motion (`u10`) | M52 | `core/…/motion/` + `MotionSource`/`NoMotion` | **présent, non branché** (`BlockMotionSource`, testé, jamais appelé en prod) |
| B warp (`u11`) | M53 | `core/…/mask/{MaskMotion,WarpPlan,MaskPropagator}` | **branché en :core** (`LocalPropagator` : décision + warp, banc < 8 ms ; câblage app documenté, non posé — M4) |
| C scheduler (`u12`) | M54 | `core/…/keyframe/` (`KeyframeScheduler`, `KeyframeConfig`) | **présent** |
| D calque (`u13`) | M55 | `core/…/local/` + `LiveLocalOverlay` + câblage app | **présent**, inerte (`NoMotion` par défaut) |
| E budget (`u09`) | M56 | `docs/LATENCE-BUDGET.md` + `scripts/budget_latence.py` | **présent** |
| L11 décodage | M46 | `core/…/mask/` RLE/poly + `docs/MASK-DECODE.md` | **présent** |

Sans A et B, il n'y a **pas d'erreur de reprojection réelle** : le recalage de
`errorThreshold` (§4) ne peut pas être fait et la valeur reste celle de `u12`.

## 2. Les sept points de fusion, sur les ancres de ce dépôt

### 2.1 `onFrame` — le tick local passe **avant** toute décision réseau

Ancre : `app/src/main/java/dev/sam3vision/app/LiveViewModel.kt:232`
(`fun onFrame(jpegB64: String, captureAtNs: Long = 0L)`).

Ordre imposé, dans cet ordre exact :

1. `narration.narrate(jpegB64)` (ligne 234) — inchangé ;
2. **`localTick(luma, captureAtNs)`** — nouveau, avant toute sortie réseau ;
3. `keepFrame` (237/243), `cadence.admits` (240), `active.runFrame` (252) — inchangés.

Il n'y a **pas** de `LivePump` à préserver ici : la cadence montante est tenue par
`cadence.admits` et le « une requête en vol » par `FrameGate`
(`core/…/live/FrameGate.kt`, 93 lignes). Ne pas réintroduire `LivePump`, ne pas retirer
`FrameGate`.

Conséquence de signature : `onFrame` reçoit la **luminance** de la frame
(`luma: ByteArray?`, plan de référence 256×103) en plus du JPEG. Son **seul** appelant est
`LiveScreen.kt:292` (`viewModel.onFrame(bitmapToJpegBase64(upright, …), proxy.imageInfo.timestamp)`) :
le bitmap `upright` est déjà là, la luminance s'en dérive sans seconde conversion et
**déjà tournée** — c'est le chemin qui évite le piège du plan Y NV21 (§7). Un appelant qui
n'a pas de luminance passe `null`, et le calque reste inerte (`LocalReason.NO_ESTIMATOR`) :
le contrat est rétro-compatible.

### 2.2 `publish` — un seul jeu d'overlays, le keyframe se pose ici

Ancre : `LiveViewModel.kt:289` (`private suspend fun publish(frame: LiveFrame)`) et
`LiveViewModel.kt:302` (`val overlays = masks.decode(frame)`).

Le keyframe local retient **les bitmaps déjà coloriés** produits par `masks.decode`, plus
la luminance de la frame : pas de deuxième décodage, pas de deuxième coloriage. Le calque
se contente de **redessiner ces bitmaps** avec la matrice de mouvement (ping-pong, zéro
allocation par frame).

`_state.update { … overlays = overlays … }` (306-318) garde la clé `overlays` : c'est le
seul poste d'affichage, il reste alimenté par le serveur quand un keyframe arrive.

### 2.3 `LiveScreen` — la surface unique existe déjà, ne pas en ajouter une

Ancre : `app/src/main/java/dev/sam3vision/app/LiveScreen.kt:193-196`
(`AndroidView { CameraSurface(...) }`, `update = { surface -> surface.setMasks(state.overlays) }`).

Le piège du plan (« `OverlayLayers(state, localFrame)`, jamais de double dessin ») est
**déjà satisfait par construction** ici : les masques vivent dans une surface native
(`CameraSurface`, `app/…/CameraSurface.kt:24`) posée sous le Compose. La fusion ajoute donc :

- rien de nouveau à l'écran : `state.overlays` porte **soit** l'overlay serveur, **soit**
  l'image du calque local — jamais les deux ;
- aucune seconde `AndroidView`, aucun `Box` de bitmaps par-dessus.

### 2.4 `LiveState` — quatre champs `local*`, et le flux d'image séparé

Ancre : `app/src/main/java/dev/sam3vision/app/LiveState.kt:57` (`val overlays: List<Bitmap>`).

À ajouter à `LiveUiState` : `localEnabled`, `localActive`, `localFps`, `localNote`, plus une
part de ligne d'état (`localPart`) composée dans `statusLine` (L89-94). `formatLiveStatus`
**ne change pas** : son contrat est testé (`LiveStateTest`) et la ligne se compose par
concaténation, comme le fait déjà `chronologie` (L87).

L'image du calque à 30 fps ne doit **pas** passer par `_state` (les barres se
recomposeraient 30 fois par seconde) : elle part dans un `StateFlow` séparé, collecté par le
seul `LiveCameraStage`.

### 2.5 `LiveBars` — la pastille suit le patron de la pastille SAM

Ancre : `app/src/main/java/dev/sam3vision/app/LiveBars.kt:103` (`SamModeChip`) et
`LiveTopBar` (L27). La pastille « Local ON/OFF » est une copie de structure de
`SamModeChip` (même bandeau, même style) ; aucune nouvelle primitive de dessin.

### 2.6 Réglages — le point le plus coûteux **dans ce dépôt**

Cinq fichiers, pas un. Un champ de `Settings` non déclaré dans les **deux** stores casse
`SettingsStoreParityTest` (« une clé `KEY_` par champ ») :

| fichier | ce qu'il faut y mettre |
|---|---|
| `core/…/settings/Settings.kt` | `localOverlayEnabled: Boolean = true` + `DEFAULT` + `toString()` (L149) |
| `core/…/settings/EncryptedSettingsStore.kt` | `KEY_LOCAL_OVERLAY`, lecture (L31-61) **et** écriture (L67+) |
| `core/…/settings/PrivateFileSettingsStore.kt` | champ dans le codec fichier (`write` L51, `encode` L112) |
| `app/build.gradle.kts` | `buildConfigField("String", "LOCAL_OVERLAY", "\"${localProp("live.local.overlay")}\"")` — le drapeau de build prime sur le réglage |
| `app/…/` (section Réglages) | bascule, sur le patron de `ObjectLimitSettings.kt` / `DistanceSettings.kt` |

Il n'y a **pas** de `SettingsScreen.kt` monolithique ici : les bascules vivent dans des
fichiers de section dédiés. La bascule du calque suit ce découpage.

### 2.7 `core/local/*` est une couture, pas un doublon

Aucun doublon à fusionner : `core/…/motion/*` (A), `core/…/mask/*` (B) et
`core/…/keyframe/*` (C) sont des moteurs ; `core/…/local/*` (D) ne fait **aucune**
estimation, il décide seulement *quand* propager et *quoi* dessiner. La règle de couche de
cet arbre s'applique : pur JVM dans `:core`, Android (`Bitmap`, `Canvas`) dans `:app` —
c'est déjà la séparation `GrayMask` (`core/…/mask/MaskCore.kt`) / `Masks.kt`.

## 3. Coutures attendues de A, B, C, D

Signatures que la fusion consommera, telles que fixées par la lane (à respecter côté
branches, sinon la fusion devient une réécriture) :

| producteur | contrat minimal |
|---|---|
| A | `MotionSource.estimateSince(prevLuma, curLuma): MotionSample?`, `object NoMotion : MotionSource` |
| B | `MaskPropagator.warpInto(src: GrayMask, plan: WarpPlan, dst: GrayMask)`, `WarpPlan` à tables réallouées |
| C | `KeyframeScheduler.onKeyframeResult(atMs)`, `decision.atMs`, `requestAtMs = decision.atMs` pour une réponse tardive |
| D | `LocalOverlayEngine.tick(frameAtMs, luma, keyframeAgeMs, confidence): LocalTick`, `LocalReason` (dont `NO_ESTIMATOR`), `setMotion(MotionSource)` — aujourd'hui `core.local.MotionSource`, à faire consommer la couture A à la fusion |

Tous les types traversant la couture sont **purs JVM**. `LiveStats`
(`core/…/live/LiveStats.kt:17`) est réutilisé pour la mesure de peinture (`notePaint`) et
`FrameGate` pour le « un en vol » : aucune deuxième implémentation.

## 4. Recalage de `errorThreshold` (item propre à F)

`errorThreshold = 0,12` vient du modèle de reprojection **inventé** de `u12` (fraction de
pixels, pas de masque). Il doit être recalé sur l'erreur **réelle** rendue par A+B.

**Métrique** — fraction de désaccord de masque, sur masque binaire seuillé à 128, hors
zones non estimées (confiance < `minConfidence = 0,35`) :

```text
d(t) = |M_warp(t) XOR M_sam(t)| / |M_warp(t) OR M_sam(t)|        (0 = parfait, 1 = disjoint)
```

**Protocole** :

1. fixtures de A/B : keyframe connu + frame à `t` (même scène, translation/échelle connues) ;
2. calculer `d(t)` pour chaque `t` de la fenêtre keyframe (33 ms de pas) ;
3. `errorThreshold = p90(d)`, arrondi au centième supérieur ;
4. déclenchement : `d > errorThreshold` sur **deux frames consécutives**, lissé par
   l'EMA 0,40 de C ;
5. écrire la valeur retenue dans **`KeyframeConfig` uniquement** (une constante, un endroit).

**Bornes à ne pas franchir** : plancher de keyframe 200 ms, plafond 500 ms (1 000 ms
seulement si `allowStaticSlack` est explicitement activé — **off par défaut**, il viole la
bande 200-500 ms du plan) ; débit 3 req/s, rafale 2 ; 1 demande en vol ; `inFlightTimeoutMs`
12 s.

Tant que A et B ne sont pas posés, la valeur **reste 0,12**, étiquetée *calculée, non
recalée*. Aucun chiffre inventé : « non mesuré » s'écrit « non mesuré ».

## 5. Tableau de simulation 41/245/422/655 ms

Colonnes obligatoires (une ligne par latence serveur, en-tête tel quel) :

```text
latence | keyframes/s | ips sans calque | ips avec calque | âge moyen sans | âge moyen avec | étiquette
```

Valeurs de **référence** (source `u13`, mesurées en simulation JVM sur un autre arbre —
à **reproduire** ici, pas à recopier comme mesure) :

| latence | keyframes/s | ips sans | ips avec | âge sans | âge avec |
|---|---|---|---|---|---|
| 41 ms | 24,20 | 24,20 | 30,00 | 20 ms | 20 ms |
| 245 ms | 4,00 | 4,00 | 28,80 | 114 ms | 31 ms |
| 422 ms | 2,20 | 2,20 | 27,80 | 190 ms | 30 ms |
| 655 ms | 1,40 | 1,40 | 26,40 | 274 ms | 29 ms |

Deux étiquettes seulement sont admises : *calculé par simulation* (horloge virtuelle 33 ms,
requête 150/1 200/1 200 ms) et *non mesuré*. Le gain à 41 ms est **nul** : le réseau y est
déjà plus rapide que la caméra, ne pas le présenter comme un gain.

## 6. Porte de sortie de F (à rejouer après fusion)

```bash
python3 tools/check-kotlin-limits.py .                     # 500/50
python3 .github/ci-budget/check_timeouts.py                # timeouts ≤ 5 min
bash scripts/test_scripts.sh
python3 -m unittest tools/test_check_kotlin_limits.py
bash /home/baptiste/tmp/vision-campaign/build.sh <arbre> :core:testDebugUnitTest
bash /home/baptiste/tmp/vision-campaign/build.sh <arbre> :app:assembleDebug
```

**Outillage cassé au 2026-10-26** : `build.sh` ajoute `--console=plain` alors que
`build-serveur` l'injecte déjà → `Multiple arguments were provided for command-line option
'--console'`, aucune compilation n'a lieu (constat de M11, reproduit). Tant que ce n'est pas
corrigé, toute PR de la lane écrit **« non vérifié (compile) »** et la CI GitHub est le juge.
`build.sh` n'est pas exécutable en direct : l'appeler par `bash`.

## 7. Ce que la fusion ne fait pas

- pas de nouvelle dépendance (versions figées dans `gradle/libs.versions.toml`) ;
- pas de réseau, pas de GPU, pas de téléphone dans F : les chiffres de terrain restent
  « non mesurés » (mission G / M58) ;
- pas de `WarpFilter.BILINEAR` sur un masque binaire : frange d'un demi-pixel = 127, sous le
  seuil 128 → `NEAREST`, ou seuillage à 127 ;
- pas de luminance depuis le plan Y du NV21 : il n'est **pas tourné**, convertir exige de
  permuter `dx`/`dy` selon `rotationDegrees` — garder le passage par `Bitmap` (non fait) ;
- pas de deuxième décodeur de masque : `LiveMaskDecoder`
  (`app/…/live/LiveMaskDecoder.kt`) et `MaskPainter` (`app/…/isee/MaskPainter.kt`) sont les
  deux consommateurs existants ; L11 étend `decodeMaskAny`, il n'en crée pas un troisième.