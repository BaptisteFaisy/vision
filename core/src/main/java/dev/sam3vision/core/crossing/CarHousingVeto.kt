package dev.sam3vision.core.crossing

/**
 * Veto caisson voiture : une boîte de feu trois fois plus haute que large
 * est un tricolore (ou un profil), pas un feu piéton — les prompts piétons
 * captent aussi les tricolores (mêmes boîtes, scores jusqu'à 0,93,
 * `docs/MESURES-SAM3-FEUX.md` §10) et la porte admet les disques (détection
 * au plus large, 31/10) : sans ce veto, un tricolore vert devient un ordre
 * de traverser (`fr_vichy_red`, 01/11).
 *
 * Mesuré le 01/11 sur 168 boîtes SAM (16 photos, 6 prompts) : piétons
 * 0,49–2,67, tricolores francs 3,75–8,04 — le seuil 3,0 tombe dans le vide
 * (marges 12 % / 25 %). Le veto par wording (`CarLightVeto`) reste inactif
 * (aucun wording ne sépare voiture et piéton, §10) : ici on compare des
 * pixels, pas des mots. Boîte illisible ⇒ pas de veto (l'aval la rejette,
 * même contrat que `CarLightVeto`).
 *
 * Pur Kotlin, testé en JVM (`CrossingStateTest`).
 */
object CarHousingVeto {

    /** Élancement h/w à partir duquel une boîte feu est un caisson voiture. */
    const val RATIO_MIN = 3.0f

    /**
     * Vrai si la boîte [box] (pixels, `x1,y1,x2,y2`) est un caisson voiture :
     * trois fois plus haute que large. Le carré haut-de-poteau (h/w = 1)
     * n'est jamais vetoé.
     */
    fun isVetoed(box: List<Float>): Boolean {
        if (box.size < 4) return false
        val w = box[2] - box[0]
        val h = box[3] - box[1]
        if (!w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f) return false
        return h / w >= RATIO_MIN
    }
}
