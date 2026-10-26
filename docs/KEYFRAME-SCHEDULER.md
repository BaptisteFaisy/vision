# KEYFRAME-SCHEDULER — quand redemander un SAM complet (u12)

Direct en mode local : la propagation (u10 mouvement → u11 warp de masque) porte 30 fps, et un
**keyframe SAM complet** ne sert qu'à recaler les masques. Ce document fixe le contrat du
`KeyframeScheduler` (`:core`, paquet `dev.sam3vision.core.keyframe`) pour u10, u11, u13 et u07.

Mesures de référence (WAVE2 §0, à ne pas réécrire) : A/R réseau **93 ms** filaire / **150–250 ms**
téléphone ; `/live` A100 **294–328 ms** serveur (A/R téléphone mesuré **1 220–1 260 ms**) ;
B200 1 concept **41 ms**. Un A/R HTTP cloud ne descend pas sous ~140 ms : la fraîcheur des masques
ne peut donc pas venir de keyframes plus fréquents que ce que le réseau permet.

Fichiers :

| fichier | rôle |
|---|---|
| `core/.../keyframe/KeyframeTypes.kt` | `FrameSignal`, `KeyframeDecision`, `Trigger` (6), `HoldReason` (5), `Trigger.isUrgent` |
| `core/.../keyframe/KeyframeConfig.kt` | tous les réglages + validation (`require`) au montage |
| `core/.../keyframe/RequestBudget.kt` | seau à jetons 3/s rafale 2 + **jeton d'urgence** à découvert |
| `core/.../keyframe/KeyframeScheduler.kt` | la machine à états : décide, tient le repli, le timeout, les compteurs |
| `core/.../keyframe/KeyframeStats.kt` | compteurs + p95 de latence des keyframes (via `LiveStats`) |

## 1. Entrées : ce que l'appelant mesure (jamais des pixels)

```kotlin
FrameSignal(
    reprojectionError = <0..1>,   // u11 : désaccord masque warpé / image courante
    sceneChange       = <0..1>,   // u10 ou histogramme : rupture vs le keyframe en cours
    objectsPresent    = true,     // false = plus aucun masque à afficher
)
```

- **Contrat d'erreur de reprojection** (à produire par u11) : résidu photométrique **moyen sur
  les pixels du masque** (après warp), divisé par l'amplitude typique du fond, borné à `1`.
  `0` = masque aligné, `1` = masque sans rapport. Le scheduler en fait une **EMA** d'alpha
  `0,40` : il réagit à une tendance, pas à une frame.
- Valeurs `NaN` ou hors `[0,1]` : neutralisées (`FrameSignal.sanitized`), un estimateur cassé
  ne bloque jamais les keyframes.

## 2. Sortie : une décision par frame

```kotlin
when (val decision = scheduler.onFrame(signal)) {
    is KeyframeDecision.Request -> {
        // envoyer la frame clé (LiveSession/HttpLiveClient), puis :
        launch { scheduler.onKeyframeResult(true, mesure, residualError = 0.02f, requestAtMs = decision.atMs) }
    }
    is KeyframeDecision.Hold -> Unit // la propagation locale suffit
}
```

`Request.trigger` ∈ `STARTUP | SCENE_CHANGE | LOST | REPROJECTION | EXPIRED | RETRY` (comptés
dans `stats.triggerCount`). `Hold.reason` ∈ `IN_FLIGHT | MIN_INTERVAL | BACKOFF | BUDGET | STABLE`
avec `retryInMs` (affichable dans le statut, u07). `Request.atMs` est l'instant de la **décision**,
pas de l'envoi : c'est lui le repère du keyframe produit (les masques décrivent la scène à cet
instant-là, et arrivent `A/R` plus tard, déjà vieux de `A/R`).

**Toujours appeler `onKeyframeResult`** : c'est ce qui libère le créneau. Un oubli est réparé
au bout de `inFlightTimeoutMs` (12 s) — requête déclarée perdue, échec compté, repli armé.

## 3. Règles et garde-fous

| règle | valeur | pourquoi |
|---|---|---|
| erreur lissée > `errorThreshold` sur `errorFrames` frames | 0,12 pendant 2 frames | antiblocage : pas de clignotement |
| coupure de scène / masque perdu | `sceneThreshold` 0,35 | prioritaire, **outre** le délai minimal et le repli |
| âge du keyframe | plafond 500 ms, étirable à 1 000 ms si `allowStaticSlack` | fraîcheur garantie |
| délai minimal entre demandes | 200 ms (sauf urgences) | borne le débit et le coût |
| budget | 3 req/s, rafale 2, **+1 jeton d'urgence** | lien montant téléphone + GPU (0,237 $/1000 frames) |
| requêtes simultanées | 1 (`FrameGate` réutilisé de `:core/live`) | la file GPU a déjà coûté 600 ms |
| échecs | repli 0,5 → 1 → 2 → 4 → 8 s, remis à zéro au succès | pas de boucle sur un backend mort |

Le jeton d'urgence (découvert d'un jeton, remboursé par la reconstitution) garantit une réponse
immédiate à une coupure de scène même budget saturé : sur toute fenêtre de 1 s, la borne reste
`rafale + débit + 1` (= 6, `KeyframeConfig.maxRequestsPerSecond`).

## 4. Intégration (u13) — sans casser l'existant

- La décision se prend **par frame caméra**, avant tout réseau ; elle ne bloque jamais : c'est
  une fonction pure de l'horloge et des mesures.
- Le scheduler n'envoie rien lui-même : réutiliser `LiveSession.runFrame` pour la frame clé, en
  ne l'appelant **que** sur `Request`.
- `LiveStats` de `LiveSession` reste la latence perçue ; `KeyframeStats` a sa **propre** fenêtre
  et ne compte que les keyframes. `stats.keyframeRatio` (keyframes reçus / frames) mesure
  l'efficacité de la propagation : c'est le chiffre à afficher.
- Thread-sûreté : `@Synchronized` / `@Volatile` côté scheduler — appelable depuis le thread
  caméra et le thread IO.
- Câblage attendu : `LocalOverlayEngine.localTick(luma)` **avant** `pump.offer(jpegB64)`, une
  seule surface `OverlayLayers(state, localFrame)` (jamais de double dessin).

## 5. Simulation (banc reproductible, hors ligne)

`core/src/test/java/.../keyframe/` : `KeyframeScene` (vérité terrain synthétique, horloge
virtuelle), `KeyframeSim` (les 8 scénarios, les 4 politiques, le tableau), `KeyframeSimTest`.
Les politiques « fixe N ms » sont le **même** scheduler avec des seuils inatteignables et un
signal neutre : aucune duplication de politique.

Violations comptées (des **fautes de décision**, pas les limites physiques) : fenêtre d'1 s
au-dessus du budget ; demande acceptée pendant qu'une autre est en vol ; attente `STABLE` alors
que le plafond d'âge était dû. Le tableau est écrit dans `core/build/keyframe-sim-u12.txt`
(répertoire de build, non versionné).

```bash
./gradlew :core:testDebugUnitTest --console=plain      # puis :
cat core/build/keyframe-sim-u12.txt                    # 32 lignes, 0 violation partout
```

**Tous ces chiffres sont calculés** (horloge virtuelle, A/R 150 / 1 200 ms, modèle d'erreur
synthétique) : aucun téléphone, aucun GPU, aucun appel réseau. Le seuil `errorThreshold = 0,12`
est **à recaler** sur l'erreur réelle rendue par u11 (fraction de désaccord de masque, pas des
pixels), et la veille statique reste **off par défaut** (elle viole la bande 200–500 ms du plan).