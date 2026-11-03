package dev.sam3vision.app.glasses

import android.media.MediaCodec

/**
 * Pannes `MediaCodec` du direct lunettes : la décision de survie du codec et les
 * trois appels gardés qui l'appliquent (`GlassesLiveDecoder`).
 *
 * Une [MediaCodec.CodecException] transitoire demande de rejouer le même appel, le
 * codec restant sain : le jeter coûterait 100 à 500 ms de gel pour le recréer, pour
 * une erreur qui passe toute seule — la frame suivante rejoue, sans gel. Tout le
 * reste (codec mort, erreur irrécupérable) jette le codec : la frame suivante le
 * recrée proprement. Seules les `Exception` sont contenues ici ; une `Error` remonte
 * au `runCatching` de `render`, qui jette le codec comme avant.
 */

/**
 * Faut-il jeter le codec ? Non pour une erreur transitoire (codec sain, appel à
 * rejouer), oui pour tout le reste. Pur, testé en JVM (`GlassesCodecErrorsTest`).
 */
internal fun shouldDropCodec(transientError: Boolean): Boolean = !transientError

/**
 * Vrai si l'erreur est une `CodecException` transitoire (même appel à rejouer).
 * Le test JVM ne couvre que le chemin hors codec (`as?` seul, aucune méthode du
 * stub Android appelée) : une `CodecException` ne s'instancie pas hors Android.
 */
internal fun isTransientCodecError(error: Throwable): Boolean =
    (error as? MediaCodec.CodecException)?.isTransient == true

/**
 * `dequeueOutputBuffer` gardé : l'index (`>= 0`, `INFO_*`, `TRY_AGAIN`) ou `null`
 * si le codec lâche. `onDeadCodec` (jeter le codec) seulement si l'erreur n'est
 * pas transitoire ; sinon le codec est conservé et la frame suivante rejoue.
 */
internal fun dequeueOutputGuarded(
    decoder: MediaCodec,
    info: MediaCodec.BufferInfo,
    timeoutUs: Long,
    onDeadCodec: () -> Unit,
): Int? = try {
    decoder.dequeueOutputBuffer(info, timeoutUs)
} catch (e: Exception) {
    if (shouldDropCodec(isTransientCodecError(e))) onDeadCodec()
    null
}

/**
 * `releaseOutputBuffer` gardé vers la surface : `true` si l'image est rendue.
 * `onDeadCodec` (jeter le codec) seulement si l'erreur n'est pas transitoire.
 */
internal fun releaseOutputGuarded(
    decoder: MediaCodec,
    index: Int,
    onDeadCodec: () -> Unit,
): Boolean = try {
    decoder.releaseOutputBuffer(index, true)
    true
} catch (e: Exception) {
    if (shouldDropCodec(isTransientCodecError(e))) onDeadCodec()
    false
}

/**
 * `queueInputBuffer` gardé : `true` si l'accès est envoyé au décodeur.
 * `onDeadCodec` (jeter le codec) seulement si l'erreur n'est pas transitoire.
 */
internal fun queueInputGuarded(
    decoder: MediaCodec,
    index: Int,
    size: Int,
    presentationTimeUs: Long,
    onDeadCodec: () -> Unit,
): Boolean = try {
    decoder.queueInputBuffer(index, 0, size, maxOf(0L, presentationTimeUs), 0)
    true
} catch (e: Exception) {
    if (shouldDropCodec(isTransientCodecError(e))) onDeadCodec()
    false
}
