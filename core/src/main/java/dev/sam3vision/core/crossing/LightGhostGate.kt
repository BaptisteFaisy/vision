package dev.sam3vision.core.crossing

/**
 * Feux fantômes : SAM sort « pedestrian traffic light » sur des morceaux de rue
 * où il n'y a aucun feu, une ou deux frames de suite, et une seule de ces frames
 * disait « Don't cross. » puis relançait 8 frames de doute ([LightEvidenceGate]).
 *
 * Mesuré sur les vidéos Samsung : sur les passages sans feu, ces boîtes tiennent
 * 2 frames au plus (scores 0,36 à 0,67), alors qu'un vrai feu est là sur toutes
 * les frames (93 frames sur 93). Une boîte sans lampe lue doit donc tenir
 * [CONFIRM_FRAMES] frames de suite avant de compter.
 *
 * Une lampe lue compte tout de suite : un rouge vu reste un rouge. Le repli
 * haut-de-poteau passe ici comme les autres boîtes, avec sa vraie lecture : sur
 * le terrain du 04/11, 9 rafales de doute sur 10 venaient de lui, sans aucune
 * boîte SAM. Un feu confirmé garde l'asymétrie sûre : toute boîte qui revient reparle en une frame,
 * jusqu'à [FORGET_FRAMES] frames sans boîte ([LightEvidenceGate.ABSENT_REARM_FRAMES]).
 *
 * Pur Kotlin testé en JVM, aucune horloge.
 */
class LightGhostGate {

    private var streak = 0
    private var silentFrames = 0
    private var confirmed = false

    /**
     * Avance d'une frame : vrai si la boîte de cette frame compte comme un feu.
     * [sawBox] dit si une boîte de feu a été lue, [lampRead] si elle porte une
     * lampe lue sur ses pixels (rouge franc ou vert).
     */
    fun admits(sawBox: Boolean, lampRead: Boolean): Boolean {
        if (!sawBox) {
            onSilence()
            return false
        }
        streak += 1
        silentFrames = 0
        if (lampRead || streak >= CONFIRM_FRAMES) confirmed = true
        return confirmed
    }

    fun reset() {
        streak = 0
        silentFrames = 0
        confirmed = false
    }

    private fun onSilence() {
        streak = 0
        silentFrames += 1
        if (silentFrames >= FORGET_FRAMES) confirmed = false
    }

    companion object {
        /** Frames de suite exigées d'une boîte sans lampe : les fantômes n'ont jamais dépassé 2. */
        const val CONFIRM_FRAMES = 3

        /** Sans boîte depuis ce nombre de frames, le feu confirmé est oublié. */
        const val FORGET_FRAMES = LightEvidenceGate.ABSENT_REARM_FRAMES
    }
}
