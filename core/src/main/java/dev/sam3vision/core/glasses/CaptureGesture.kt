package dev.sam3vision.core.glasses

/** Appui reçu du bouton capture des lunettes (miroir de `CapturePressType` du SDK Meta). */
enum class CapturePress { SHORT, HOLD, DOUBLE }

/** Ce que l'app doit déclencher une fois l'appui confirmé. */
enum class CaptureAction {
    /** Photo → segmentation → description lue à voix haute. */
    PHOTO,

    /** Photo → chat ouvert, la question se pose sur le téléphone. */
    ASK,

    /** Mode Direct : pas encore porté sur lunettes. */
    LIVE,
}

/**
 * Lisse les appuis du bouton capture des Ray-Ban Meta.
 *
 * En beta, le SDK émet `SHORT_PRESS` **puis** `DOUBLE_PRESS` pour un double-appui
 * (bug documenté dans les known issues). Un appui court isolé est donc retenu
 * pendant [graceMs] avant d'être confirmé, et jeté si un double-appui arrive avant
 * l'échéance. Pur et sans horloge interne : l'appelant fournit les timestamps.
 */
class CaptureGestureBuffer(private val graceMs: Long = DEFAULT_GRACE_MS) {

    private var pendingShortAt: Long? = null

    /** Vrai tant qu'un appui court attend sa confirmation. */
    val hasPending: Boolean get() = pendingShortAt != null

    /**
     * @return l'action immédiate (`ASK` pour un appui long, `LIVE` pour un double),
     *         ou `null` quand l'appui court est mis en attente.
     */
    fun onPress(press: CapturePress, atMs: Long): CaptureAction? = when (press) {
        CapturePress.HOLD -> {
            pendingShortAt = null
            CaptureAction.ASK
        }
        CapturePress.DOUBLE -> {
            pendingShortAt = null
            CaptureAction.LIVE
        }
        CapturePress.SHORT -> {
            pendingShortAt = atMs
            null
        }
    }

    /**
     * À appeler régulièrement : rend `PHOTO` quand un appui court a survécu au délai
     * de grâce, `null` sinon.
     */
    fun flush(nowMs: Long): CaptureAction? {
        val at = pendingShortAt ?: return null
        if (nowMs - at < graceMs) return null
        pendingShortAt = null
        return CaptureAction.PHOTO
    }

    /** Oublie l'appui en attente (arrêt du flux, perte de session). */
    fun reset() {
        pendingShortAt = null
    }

    companion object {
        /** Délai de grâce : assez long pour laisser arriver le `DOUBLE_PRESS`. */
        const val DEFAULT_GRACE_MS = 350L
    }
}