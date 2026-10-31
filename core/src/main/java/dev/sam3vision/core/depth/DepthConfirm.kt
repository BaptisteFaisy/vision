package dev.sam3vision.core.depth

/**
 * Confirmation d'approche par la profondeur (PISTE2) : une boîte qui grossit
 * n'est une approche que si sa proximité augmente avec sa taille — une
 * croissance à profondeur constante, c'est un pan/zoom caméra, pas un objet
 * qui vient vers le téléphone.
 *
 * La profondeur a un droit de veto, pas un pouvoir d'exigence : sans mesure
 * (ni embarquée ni serveur), le verdict géométrique est gardé tel quel —
 * on ne tait que sur preuve contraire. Pur JVM, sans état.
 */
object DepthConfirm {

    /** Hausse absolue minimale de proximité pour conclure au rapprochement (échelle 0..1). */
    const val MIN_RISE_ABS = 0.005f

    /**
     * Hausse relative minimale (2 % de la proximité de départ) : la carte
     * brute embarquée n'est pas normalisée, un seuil absolu seul n'y suffit pas.
     */
    const val MIN_RISE_REL = 0.02f

    /**
     * Proximité retenue pour une boîte : [embedded] ([DepthReadings.boxCloseness])
     * prioritaire, [server] ([dev.sam3vision.core.model.SegObject.depth]) en
     * repli ; `null` si rien de fini — une valeur absente ne mesure rien.
     */
    fun resolve(embedded: Float?, server: Float?): Float? =
        embedded?.takeIf { it.isFinite() } ?: server?.takeIf { it.isFinite() }

    /**
     * La proximité a-t-elle augmenté entre [first] et [last] (même source,
     * même piste) ? Hausse = `last − first` au-delà du plancher
     * `max([minRiseAbs], first × [minRiseRel])`.
     *
     * `null` = indécidable (mesure manquante, non finie, ou plancher
     * incalculable) : l'appelant garde le verdict géométrique.
     */
    fun rises(
        first: Float?,
        last: Float?,
        minRiseAbs: Float = MIN_RISE_ABS,
        minRiseRel: Float = MIN_RISE_REL,
    ): Boolean? {
        if (first == null || last == null) return null
        if (!first.isFinite() || !last.isFinite()) return null
        val floor = maxOf(minRiseAbs, first * minRiseRel)
        if (!floor.isFinite()) return null
        return last - first >= floor
    }

    /**
     * Vrai quand la profondeur contredit la croissance (les deux proximités
     * mesurées, pas de hausse) : l'approche géométrique doit se taire.
     * Faux sans mesure — le doute ne tait jamais.
     */
    fun contradicts(
        first: Float?,
        last: Float?,
        minRiseAbs: Float = MIN_RISE_ABS,
        minRiseRel: Float = MIN_RISE_REL,
    ): Boolean = rises(first, last, minRiseAbs, minRiseRel) == false

    /**
     * Vrai quand la croissance tient malgré la profondeur : hausse prouvée,
     * ou mesure manquante (repli géométrie — comportement d'avant).
     */
    fun confirmsGrowth(
        first: Float?,
        last: Float?,
        minRiseAbs: Float = MIN_RISE_ABS,
        minRiseRel: Float = MIN_RISE_REL,
    ): Boolean = rises(first, last, minRiseAbs, minRiseRel) != false
}
