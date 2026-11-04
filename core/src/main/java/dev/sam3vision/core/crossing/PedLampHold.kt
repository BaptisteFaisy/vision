package dev.sam3vision.core.crossing

/**
 * Tenue d'un vert voté à travers les frames qui ne disent rien contre lui.
 *
 * Une LED de feu piéton vit sur le secteur : elle s'éteint 100 fois par seconde
 * et un obturateur court (plein jour) la capture éteinte. Mesuré sur la vidéo
 * Samsung `20261104_101521` (60 ips) : salves de ~1,3 s où une frame sur trois
 * est noire, alias exact 100 Hz à 60 ips, alors que le feu reste vert. Une
 * lampe noire n'est pas un rouge : ni elle, ni un rouge lu sur une AUTRE boîte
 * (le tricolore voisin), ni une boîte perdue ne retirent le vert du feu piéton
 * (ordre user du 04/11 : la décision ne se prend que sur le feu piéton).
 *
 * Le vert tombe aussitôt sur preuve :
 *
 * - rouge franc lu sur la lampe tenue (le vrai passage au rouge, sans délai) ;
 * - autre lampe mieux classée que la lampe tenue ([PedLampRank.better]) : un
 *   bonhomme rouge bat un disque vert tenu, miroir sûr ;
 * - lecture sans rang (repli poteau, lecture non mesurée) : rien à comparer.
 *
 * Sinon il tombe à [holdMs] du dernier vert lu : borne **en temps**, pas en
 * frames, pour que la tenue ne s'étire pas quand la cadence baisse.
 *
 * Pur et testé en JVM : l'horodatage est fourni, aucune horloge implicite.
 */
class PedLampHold(private val holdMs: Long = HOLD_MS) {

    private var box: List<Float>? = null
    private var rank: PedLampRank.Ranked? = null
    private var greenAtMs = 0L

    /** Vert voté lu à [atMs] sur [lampBox] : la tenue s'y ancre. Sans rang, rien à ancrer. */
    fun arm(lampBox: List<Float>, lampRank: PedLampRank.Ranked?, atMs: Long) {
        if (lampRank == null) return reset()
        box = lampBox
        rank = lampRank
        greenAtMs = atMs
    }

    /**
     * Vrai si la frame non verte à [atMs] ne dit rien contre le vert tenu.
     * [readBox] nulle = boîte perdue ; [redProven] = rouge franc sur [readBox].
     * Faux lâche l'ancre : le vert devra se reprouver.
     */
    fun holds(readBox: List<Float>?, readRank: PedLampRank.Ranked?, redProven: Boolean, atMs: Long): Boolean {
        val lamp = box ?: return false
        val held = rank ?: return false
        val keep = atMs - greenAtMs <= holdMs && !refuted(lamp, held, readBox, readRank, redProven)
        if (!keep) reset()
        return keep
    }

    private fun refuted(
        lamp: List<Float>,
        held: PedLampRank.Ranked,
        readBox: List<Float>?,
        readRank: PedLampRank.Ranked?,
        redProven: Boolean,
    ): Boolean {
        if (readBox == null) return false
        if (readRank == null) return true
        if (sameLamp(lamp, readBox)) return redProven
        return PedLampRank.better(readRank, held)
    }

    /**
     * Vrai si [read] recouvre [lamp] gonflée d'une fois sa taille (main qui
     * tremble). Trop large est sûr : une boîte voisine prise pour la lampe
     * tenue n'y fait que sortir plus vite sur son rouge.
     */
    private fun sameLamp(lamp: List<Float>, read: List<Float>): Boolean {
        val marginX = lamp[2] - lamp[0]
        val marginY = lamp[3] - lamp[1]
        return read[0] <= lamp[2] + marginX && read[2] >= lamp[0] - marginX &&
            read[1] <= lamp[3] + marginY && read[3] >= lamp[1] - marginY
    }

    /** Oublie l'ancre : nouvelle traversée, ou vert à reprouver. */
    fun reset() {
        box = null
        rank = null
        greenAtMs = 0L
    }

    companion object {
        /** Tenue maximale après le dernier vert lu : une salve LED entière (~1,3 s mesuré). */
        const val HOLD_MS = 1_500L
    }
}
