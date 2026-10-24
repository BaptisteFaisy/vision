# SPEC LIVE — flux vidéo analysé par SAM 3.1 (Modal A100) + questions InferHub

Objectif : le téléphone **filme en continu**, chaque frame est segmentée par SAM 3.1 hébergé sur Modal
(**A100**), et l'utilisateur pose des questions auxquelles répond le LLM vision d'**InferHub**.
Contrainte : pas de SAM 3.1 vidéo officiel (facebook/sam3-video et sam3.1-video renvoient 401/gated,
aucun miroir non gated) ⇒ analyse **frame par frame** avec `Sam3Model` (le même poids que `/segment`),
cadencée côté client. C'est un choix assumé, pas un échec : la détection par concept fonctionne, seuls
les identités temporelles persistantes (track IDs) manquent.

## Backend Modal — app `sam3-live`, classe `Sam3Live`

GPU : `gpu="A100"` (40 Go ; Modal peut donner une 80 Go SXM, même contrat). `scaledown_window=30`
pour ne pas facturer à vide. Concurrence 1 (Modal interdit `max_inputs>1` sur les classes).
Image : mêmes dépendances que `backend/requirements.txt` + `torchvision` (déjà requis).

### Contrat (ajout de `/live`, `/health` et `/segment` inchangés — voir docs/SPEC.md)

`POST /live`
- corps : `{"image_b64": str, "prompts": [str] | null, "max_objects": int | null, "work_edge": int | null}`
- `work_edge` : résolution de travail (défaut 768, borné 256..1024) — plus petit = plus rapide.
- réponse : `{"width": int, "height": int, "elapsed_ms": int, "objects": [{"label": str, "score": float, "box": [x1,y1,x2,y2], "mask_b64": str}]}`
  (mêmes `mask_b64` PNG gris 8 bits que `/segment`), tronquée à `max_objects` (défaut 12).
- erreurs : 400 payload invalide (echo borné à 300 car.), 500 interne borné pareil.

`GET /health` → `{"ok": true, "model": "sam3.1", "device": "cuda", "gpu": "<nom nvidia-smi>"}`

Objectif de performance mesuré : **< 600 ms par frame** à 768 px avec 4 prompts sur A100. Si dépassé,
descendre `work_edge` côté client avant de toucher au modèle.

## Kotlin — `:core` (signatures exactes, `:app` code dessus)

```kotlin
package dev.sam3vision.core.net

/** Flux live : une frame à la fois, jamais de file d'attente. */
interface LiveClient {
    suspend fun live(jpegB64: String, prompts: List<String>?, workEdge: Int, maxObjects: Int): Result<LiveFrame>
    suspend fun health(): Result<LiveHealth>
}

package dev.sam3vision.core.model

data class LiveFrame(val width: Int, val height: Int, val elapsedMs: Long, val objects: List<SegObject>)
data class LiveHealth(val ok: Boolean, val model: String, val device: String, val gpu: String)

package dev.sam3vision.core.live

/** Régulateur : goutte-à-goutte sans file — si une frame est en vol, la nouvelle est jetée. */
class FrameGate {
    fun tryStart(): Boolean          // false = frame à jeter
    fun finish()                     // libère le créneau
    val inFlight: Boolean
}

/** Statistiques glissantes du direct (fps réel, latence moyenne et p95 sur 20 mesures). */
class LiveStats(maxSamples: Int = 20) {
    fun record(latencyMs: Long)
    val averageMs: Long
    val p95Ms: Long
    val samples: Int
}

/** Prompts du direct : liste courte, modifiable depuis l'UI. */
object LivePrompts {
    val Default: List<String>   // ["person", "car", "dog", "chair"]
    const val MAX = 8
    fun normalize(prompts: List<String>): List<String>   // vide -> Default, borne à MAX, bornes de longueur
}
```

Implémentations : `HttpLiveClient(baseUrl)` (OkHttp, timeout **10 s**, réutilise le client HTTP unique
de `:core`), parsing via `Json.kt` (pas de second parseur). `LiveSession` (use case) orchestre :
frame → gate → `LiveClient.live` → stats → callbacks UI, et **ne bloque jamais la caméra**.

## Kotlin — `:app` : mode Direct

- Bouton « Direct » sur l'écran Capture → `LiveScreen`.
- CameraX `ImageAnalysis` : `STRATEGY_KEEP_ONLY_LATEST`, rotation appliquée, **redimensionné à 640 px**
  côté client, JPEG q70, base64 → `LiveSession`. Intervalle cible 500 ms (adaptatif : si latence > 900 ms,
  on passe à 900 ms ; si < 350 ms, on descend à 350 ms).
- Rendu : aperçu caméra + overlay du dernier masque reçu (fondu 200 ms), pastilles de labels, et une
  barre d'état : `X ips · Y ms · GPU <nom>` ou l'erreur en rouge.
- « Poser une question » : envoie la **dernière frame net** (JPEG 1024 px, quality 85) + les labels suivis
  au LLM InferHub avec l'historique, comme dans le mode photo. Le direct continue pendant la réponse.
- Stop : ferme l'analyseur, vide les overlays ; retour caméra normal ; arrêt du direct ne doit PAS
  laisser la caméra liée.
- Réglages : champ `liveBaseUrl` (défaut = `samBaseUrl`) + case « utiliser le direct ».

## Fichiers et règles

`docs/LIVE-SPEC.md` (ce fichier), `backend/live_app.py` (+ `backend/segmentation.py` partagé, pas de
duplication), `core/.../live/` et `core/.../net/HttpLiveClient.kt`, `app/.../LiveScreen.kt` +
`LiveViewModel.kt`. Fichier < 500 lignes, fonction < 50 lignes, `check-kotlin-limits.py` et
`check-limits.py` doivent rester verts.