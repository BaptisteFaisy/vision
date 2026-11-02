package dev.sam3vision.core.live

import dev.sam3vision.core.speech.IseeClipsEn

/**
 * Ce qu'un palier de panne écrit et prononce.
 *
 * Deux canaux, un seul contrat : [line] est la ligne de statut (toujours disponible, même
 * sans audio), [clipId] le clip du pack voix à jouer s'il en existe un. Les paliers sans
 * [clipId] ne parlent pas — c'est voulu : le pack voix n'a pas d'audio pour eux, et
 * ajouter un id sans MP3 casse la garde `check-voicepack` de la CI.
 */
data class OutageWord(
    /** Phrase prononcée (vide = pas de voix). */
    val text: String,
    /** Ligne de statut, courte, lisible d'un coup d'œil. */
    val line: String,
    /** Clip du pack voix, `null` = visuel seul. */
    val clipId: String?,
)

/**
 * Traduction d'un palier en mot : les clips réutilisés disent déjà la vérité
 * (« Guidance unavailable. » quand la vision ne répond plus, « Guidance stopped. » quand
 * l'arrêt est proposé), et deux paliers restent muets pour ne pas bavarder.
 */
fun OutageStep.word(): OutageWord = when (this) {
    OutageStep.LOST -> OutageWord(
        text = "Guidance unavailable.",
        line = "No connection — retrying…",
        clipId = IseeClipsEn.GPS_UNAVAILABLE,
    )
    OutageStep.DEGRADED_GPS -> OutageWord(
        text = "",
        line = "Vision off — GPS guidance only",
        clipId = null,
    )
    OutageStep.DEGRADED_NONE -> OutageWord(
        text = "",
        line = "Vision off — no guidance",
        clipId = null,
    )
    OutageStep.STOP -> OutageWord(
        text = "Guidance stopped.",
        line = "Offline — stop and try again",
        clipId = IseeClipsEn.GPS_OFF,
    )
    OutageStep.BACK -> OutageWord(
        text = "",
        line = "Back online",
        clipId = null,
    )
}
