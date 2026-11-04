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
 * Une lampe lue compte tout de suite : un rouge vu reste un rouge. Un feu
 * confirmé garde l'asymétrie sûre : toute boîte qui revient reparle en une frame,
 * jusqu'à [FORGET_FRAMES] frames sans boîte ([LightEvidenceGate.ABSENT_REARM_FRAMES]).
 *
 * Le repli haut-de-poteau est à part : sans boîte de feu SAM, 2 % de pixels rouges
 * dans le carré suffisent à y lire un rouge (feu arrière derrière le poteau). Sur
 * le terrain du 04/11, sans aucun feu, il a tenu 9 frames de suite : seul, il doit
 * en tenir [POLE_CONFIRM_FRAMES]. Une vraie lampe sur un poteau reste là et bloque.
 * En attendant, [polePending] interdit de dire « Cross. » : devant un vrai rouge que
 * SAM ne voit pas, l'app se tait au lieu de dire de traverser.
 *
 * Pur Kotlin testé en JVM, aucune horloge.
 */
class LightGhostGate {

    private var streak = 0
    private var silentFrames = 0
    private var confirmed = false
    private var poleAge = NO_POLE

    /** Vrai si un rouge de poteau non tranché est vu, ou l'était il y a [POLE_BRIDGE_FRAMES] frames au plus. */
    val polePending: Boolean get() = !confirmed && poleAge <= POLE_BRIDGE_FRAMES

    /**
     * Avance d'une frame : vrai si la boîte de cette frame compte comme un feu.
     * [sawBox] dit si une boîte de feu a été lue, [lampRead] si elle porte une
     * lampe lue sur ses pixels (rouge franc ou vert), [poleOnly] si elle vient du
     * seul repli haut-de-poteau : sa lampe lue ne vaut pas preuve.
     */
    fun admits(sawBox: Boolean, lampRead: Boolean, poleOnly: Boolean = false): Boolean {
        poleAge = if (sawBox && poleOnly) 0 else minOf(poleAge + 1, NO_POLE)
        if (!sawBox) {
            onSilence()
            return false
        }
        streak += 1
        silentFrames = 0
        val proven = if (poleOnly) streak >= POLE_CONFIRM_FRAMES else lampRead || streak >= CONFIRM_FRAMES
        if (proven) confirmed = true
        return confirmed
    }

    fun reset() {
        streak = 0
        silentFrames = 0
        confirmed = false
        poleAge = NO_POLE
    }

    private fun onSilence() {
        streak = 0
        silentFrames += 1
        if (silentFrames >= FORGET_FRAMES) confirmed = false
    }

    companion object {
        /** Frames de suite exigées d'une boîte sans lampe : les fantômes n'ont jamais dépassé 2. */
        const val CONFIRM_FRAMES = 3

        /** Frames de suite exigées du seul repli haut-de-poteau : le faux rouge du terrain a tenu 9 frames. */
        const val POLE_CONFIRM_FRAMES = 15

        /** Frames tenues après un rouge de poteau : une lampe qui scintille ne dit pas « Cross. » entre deux lectures. */
        const val POLE_BRIDGE_FRAMES = 2

        private const val NO_POLE = 1_000

        /** Sans boîte depuis ce nombre de frames, le feu confirmé est oublié. */
        const val FORGET_FRAMES = LightEvidenceGate.ABSENT_REARM_FRAMES
    }
}
