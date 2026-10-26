package dev.sam3vision.app.live

import dev.sam3vision.core.live.TrackRunner
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.model.TrackFrame

/**
 * Envoi d'une frame au **tracker** (`/session` puis `/frame`) et conversion de sa
 * réponse : le transport du tracker vit à côté du ViewModel, qui n'a plus qu'à
 * l'appeler — sinon `LiveViewModel` franchit la garde des 500 lignes.
 */

/**
 * La **première** frame ouvre la session (le backend analyse cette image, ses objets
 * deviennent la frame d'ouverture) ; les suivantes partent à `/frame`, et les `id` ne
 * changent plus tant que la session vit. `null` = créneau pris, la frame est jetée.
 */
internal suspend fun TrackRunner.sendFrame(prompts: List<String>, jpegB64: String): Result<LiveFrame>? =
    if (sessionId == null) {
        start(prompts, jpegB64).map { it.asLiveFrame() }
    } else {
        submit(jpegB64)?.map { it.asLiveFrame() }
    }

/**
 * Le tracker a ses propres objets (ids stables, perdus ici : le rendu du direct attend
 * un [LiveFrame] sans id — c'est l'association locale de [LivePrediction] qui les rend).
 */
internal fun TrackFrame.asLiveFrame(): LiveFrame = LiveFrame(
    width = width,
    height = height,
    // La latence affichée vient du pipeline, mesurée côté téléphone (pas de durée serveur).
    elapsedMs = 0L,
    objects = objects.map { SegObject(it.label, it.score, it.box, it.maskPngB64) },
)