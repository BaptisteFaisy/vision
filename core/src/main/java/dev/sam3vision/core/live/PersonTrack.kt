package dev.sam3vision.core.live

import kotlin.math.abs

/**
 * Étiquette suivie : la mesure de la frame + son identité et sa dynamique.
 *
 * [label] est la copie de l'étiquette d'entrée, avec [PlacedLabel.trackId] et
 * [PlacedLabel.closingMps] renseignés : le nom, le cadran et les bords restent
 * ceux de la mesure brute, sans retouche.
 */
data class TrackedLabel(
    /** Identité de la piste : stable d'une frame à l'autre tant qu'elle vit. */
    val trackId: Int,
    /** L'étiquette d'entrée, recopiée avec [PlacedLabel.trackId] et [PlacedLabel.closingMps]. */
    val label: PlacedLabel,
    /** Mesure de **cette** frame (mètres) ; `null` si le label n'en porte pas. */
    val meters: Float?,
    /** Vitesse de fermeture lissée (m/s) : `> 0` = ça se rapproche, `null` = inconnue. */
    val closingMps: Float?,
    /** Temps de contact ([Timing.ttcSeconds]) ; `null` sans distance ou sans approche. */
    val ttcS: Float?,
    /**
     * Nombre de frames traitées depuis la première vue de la piste, **trous
     * compris** (1 à la frame d'apparition) : une identité neuve n'a pas
     * d'historique, un revenant en a un.
     */
    val ageFrames: Int,
)

/**
 * Apparie les étiquettes d'une frame aux pistes précédentes : ce qui n'a pas
 * d'identité ne peut pas dire « il vient vers vous ».
 *
 * Depuis la suppression de YOLO (`cad4c0bc`), rien ne suit un objet d'une frame
 * à l'autre : `SegObject` n'a pas d'id et les boîtes n'ont pas de `y0`/`y1`.
 * L'appariement se fait donc sur **ce qu'on a** — le centre horizontal, normalisé,
 * et la distance estimée quand elle existe — et les deux mesures se départagent :
 * deux personnes au même `centerX` mais à des distances opposées (un croisement)
 * ne s'échangent pas leurs ids.
 *
 * Trois services, tous purs et testés en JVM (l'horloge est un paramètre, jamais
 * `System.currentTimeMillis()`) :
 * - [onFrame] : la frame de labels → une [TrackedLabel] par label, dans l'ordre reçu ;
 * - la fermeture (**[TrackedLabel.closingMps]**) : pente des mesures datées de la
 *   piste sur 1,5 s, lissée, forcée à 0 sous [Timing.MIN_CLOSING_MPS] ;
 * - l'âge, pour distinguer une identité neuve d'une identité confirmée.
 *
 * Une piste non appariée **n'est pas** morte tout de suite : elle attend
 * [maxGapMs] (une occultation, un pas de côté, une frame sans mesure), puis
 * disparaît. Un retour plus tard est une **autre** personne.
 *
 * Les ids ne sont jamais réutilisés, pas même après [reset] : un consommateur qui
 * mémorise « déjà prévenu pour la piste 3 » ne prévient pas quelqu'un d'autre à
 * tort. Une instance vit le temps d'une séance ([reset] entre deux).
 */
class PersonTracker(
    /** Au-delà de cet écart entre deux vues, la piste est morte (ms). */
    private val maxGapMs: Long = 1_500L,
    /** Écart maximal de `centerX` (normalisé) pour apparier un label à une piste. */
    private val dxMax: Float = 0.14f,
    /** Écart maximal de distance (m) pour apparier un label à une piste. */
    private val dmetersMax: Float = 1.2f,
) {

    private val tracks = ArrayList<Track>()
    private var frameIndex = 0L
    private var nextId = FIRST_ID

    /**
     * Les étiquettes de la frame datée [atMs], chacune avec son identité.
     *
     * Rend une [TrackedLabel] par label d'entrée, **dans l'ordre reçu** : une
     * frame de trois personnes en rend trois, jamais deux sur la même piste
     * (la seconde devient neuve).
     *
     * Les pistes trop vieilles (`atMs - dernière vue > maxGapMs`) sont
     * enterrées d'abord ; les autres survivent à une frame vide.
     */
    fun onFrame(atMs: Long, labels: List<PlacedLabel>): List<TrackedLabel> {
        frameIndex++
        sweep(atMs)
        val owners = ownersOf(labels)
        val out = ArrayList<TrackedLabel>(labels.size)
        for (i in labels.indices) {
            val label = labels[i]
            val index = owners[i]
            val track = if (index != NO_TRACK) {
                tracks[index].also { observe(it, label, atMs) }
            } else {
                born(label, atMs)
            }
            out.add(read(track, label))
        }
        return out
    }

    /**
     * Nouvelle séance : toutes les pistes sont oubliées, et les ids continuent
     * d'avancer — un id d'avant [reset] ne désigne jamais quelqu'un d'après.
     */
    fun reset() {
        tracks.clear()
        frameIndex = 0L
    }

    /**
     * Propriétaire de chaque label, par **coût croissant** : la meilleure paire
     * d'abord, sans réutiliser ni piste ni label (glouton). Les paires
     * incomparables n'existent pas : hors des deux portes ([dxMax],
     * [dmetersMax]), ce n'est pas la même personne.
     */
    private fun ownersOf(labels: List<PlacedLabel>): IntArray {
        val owners = IntArray(labels.size) { NO_TRACK }
        if (tracks.isEmpty() || labels.isEmpty()) return owners
        val matches = ArrayList<Match>(tracks.size * labels.size)
        for (t in tracks.indices) {
            for (l in labels.indices) {
                matches.add(Match(t, l, costOf(tracks[t], labels[l]) ?: continue))
            }
        }
        matches.sortWith(MATCH_ORDER)
        val taken = BooleanArray(tracks.size)
        for (match in matches) {
            if (taken[match.track] || owners[match.label] != NO_TRACK) continue
            taken[match.track] = true
            owners[match.label] = match.track
        }
        return owners
    }

    /**
     * Coût d'appariement (sans unité, les deux écarts ramenés à leur porte) :
     * `|ΔcenterX| / dxMax + |Δmeters| / dmetersMax`. `null` = incomparable.
     *
     * Une distance inconnue d'un côté (ou des deux) ne coûte rien : l'identité
     * tient alors au seul `centerX`, ce qui vaut mieux que pas d'identité.
     */
    private fun costOf(track: Track, label: PlacedLabel): Float? {
        val dx = abs(label.centerX - track.centerX)
        if (!(dx <= dxMax)) return null
        var cost = dx / dxMax
        val before = track.lastMeters
        val now = label.meters?.takeIf { it.isFinite() }
        if (before != null && now != null) {
            val dm = abs(now - before)
            if (!(dm <= dmetersMax)) return null
            cost += dm / dmetersMax
        }
        return cost
    }

    /** Une piste meurt quand sa dernière vue dépasse [maxGapMs] (horloge qui recule : on garde). */
    private fun sweep(atMs: Long) {
        tracks.removeAll { atMs - it.lastMs > maxGapMs }
    }

    /** Nouvelle piste : l'id suivant, jamais revu, et sa première mesure. */
    private fun born(label: PlacedLabel, atMs: Long): Track {
        val track = Track(nextId, frameIndex)
        nextId++
        tracks.add(track)
        observe(track, label, atMs)
        return track
    }

    /**
     * Une piste revoit un label : position de cette frame, mesure datée ajoutée
     * à la fenêtre, fermeture réestimée. La fenêtre glisse ([WINDOW_MS]) : une
     * approche ancienne ne pèse plus sur la vitesse d'aujourd'hui.
     */
    private fun observe(track: Track, label: PlacedLabel, atMs: Long) {
        track.lastMs = atMs
        track.centerX = label.centerX
        val meters = label.meters?.takeIf { it.isFinite() } ?: return
        track.lastMeters = meters
        track.samples.add(Sample(atMs, meters))
        track.samples.removeAll { it.atMs < atMs - WINDOW_MS }
        track.closing = smoothed(track)
    }

    /**
     * Fermeture lissée de [track] : pente des mesures de la fenêtre (moindres
     * carrés, `null` avant 2 points), inversée en vitesse de rapprochement,
     * moyennée en exponentiel ([ALPHA]) puis forcée à 0 sous le bruit
     * ([Timing.MIN_CLOSING_MPS]). Une piste sans second point garde sa valeur
     * précédente (ou `null`).
     */
    private fun smoothed(track: Track): Float? {
        val slope = slopeOf(track.samples) ?: return track.closing
        val raw = -slope
        val previous = track.closing
        val value = if (previous == null) raw else ALPHA * raw + (1f - ALPHA) * previous
        return if (abs(value) < Timing.MIN_CLOSING_MPS) 0f else value
    }

    /**
     * Pente des mesures datées de [samples] (m/s), par moindres carrés, `null`
     * s'il n'y a pas deux points écartés dans le temps.
     *
     * Le temps est converti en secondes et centré sur sa moyenne : les
     * horodatages de séance ne doivent pas manger la précision du calcul, et la
     * pente doit se lire directement en mètres par seconde.
     */
    private fun slopeOf(samples: List<Sample>): Float? {
        if (samples.size < 2) return null
        var sumT = 0.0
        var sumM = 0.0
        for (sample in samples) {
            sumT += sample.atMs / 1_000.0
            sumM += sample.meters
        }
        val meanT = sumT / samples.size
        val meanM = sumM / samples.size
        var num = 0.0
        var den = 0.0
        for (sample in samples) {
            val dt = sample.atMs / 1_000.0 - meanT
            num += dt * (sample.meters - meanM)
            den += dt * dt
        }
        if (den <= 1e-9) return null
        return (num / den).toFloat()
    }

    /** Le label rendu : copie identifiée, mesure de la frame, dynamique de la piste. */
    private fun read(track: Track, label: PlacedLabel): TrackedLabel {
        val meters = label.meters?.takeIf { it.isFinite() }
        val closing = track.closing
        return TrackedLabel(
            trackId = track.id,
            label = label.copy(trackId = track.id, closingMps = closing),
            meters = meters,
            closingMps = closing,
            ttcS = Timing.ttcSeconds(meters, closing),
            ageFrames = (frameIndex - track.firstFrame + 1L).toInt(),
        )
    }

    /** Piste vivante : ce qu'il faut pour apparier la frame suivante et la dater. */
    private class Track(val id: Int, val firstFrame: Long) {
        var lastMs: Long = 0L
        var centerX: Float = 0f

        /** Dernière distance connue de la piste (les frames sans mesure ne l'effacent pas). */
        var lastMeters: Float? = null

        /** Fermeture lissée, `null` tant que la piste n'a pas deux mesures. */
        var closing: Float? = null

        /** Mesures datées de la fenêtre glissante : `(atMs, mètres)`, la plus vieille en tête. */
        val samples = ArrayList<Sample>(4)
    }

    /** Une mesure datée : la seule matière dont on tire une vitesse. */
    private data class Sample(val atMs: Long, val meters: Float)

    /** Une paire candidate (indices de piste et de label) et son coût. */
    private data class Match(val track: Int, val label: Int, val cost: Float)

    private companion object {
        /** Premier id distribué : les ids restent `> 0` (le `-1` des « non suivi » est libre). */
        const val FIRST_ID = 1

        /** Marqueur « ce label n'appartient à aucune piste » dans [ownersOf]. */
        const val NO_TRACK = -1

        /** Fenêtre d'estimation de la fermeture (ms) : 1,5 s, comme la veille des pistes. */
        const val WINDOW_MS = 1_500L

        /** Mémoire du lissage exponentiel de la fermeture : moitié du neuf, moitié de l'acquis. */
        const val ALPHA = 0.5f

        /** Ordre glouton : coût croissant, puis indices (déterministe, donc testable). */
        val MATCH_ORDER: Comparator<Match> =
            compareBy({ it.cost }, { it.track }, { it.label })
    }
}
