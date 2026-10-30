package dev.sam3vision.app.live

import dev.sam3vision.core.live.TrackRunner
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.model.TrackFrame

/**
 * Passerelle tracker → direct, extraite de `LiveViewModel` (limite de 500 lignes) :
 * le rendu du direct attend un [LiveFrame], le tracker a ses propres objets.
 *
 * La **première** frame ouvre la session (le backend analyse cette image, ses objets
 * deviennent la frame d'ouverture) ; les suivantes partent à `/frame`, et les `id` ne
 * changent plus tant que la session vit.
 */
internal suspend fun TrackRunner.runAsFrame(
    prompts: List<String>,
    jpegB64: String,
): Result<LiveFrame>? =
    if (sessionId == null) {
        start(prompts, jpegB64).map { it.asLiveFrame() }
    } else {
        submit(jpegB64)?.map { it.asLiveFrame() }
    }

/**
 * Le tracker a ses propres objets ; le rendu du direct attend un [LiveFrame].
 * L'encodage servi (`rle`/256 compris) est propagé tel quel : le pont ne retombe plus
 * sur le défaut `png` de [SegObject], le décodeur reçoit ce que le backend a envoyé.
 */
internal fun TrackFrame.asLiveFrame(): LiveFrame = LiveFrame(
    width = width,
    height = height,
    // La latence affichée vient du pipeline, mesurée côté téléphone (pas de durée serveur).
    elapsedMs = 0L,
    objects = objects.map {
        SegObject(
            label = it.label,
            score = it.score,
            box = it.box,
            maskPngB64 = it.maskPngB64,
            maskEncoding = it.maskEncoding,
            maskScale = it.maskScale,
            maskPoly = it.maskPoly,
        )
    },
)