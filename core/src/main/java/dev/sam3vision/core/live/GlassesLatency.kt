package dev.sam3vision.core.live

/**
 * Décalage entre l'horloge média des lunettes (le `presentationTimeUs` des frames DAT,
 * d'origine inconnue) et l'horloge monotone du téléphone (l'instant d'arrivée).
 *
 * Le SDK n'expose aucun canal aller-retour ni horloge murale côté lunettes : aucune
 * synchronisation NTP n'est possible. Le minimum observé de `arrivée − pts` sert donc
 * d'estimé du décalage, et chaque observation rend le **retard variable** (le jitter du
 * lien), exact au biais près — le transit minimal inconnu, que seul un étalon externe
 * (flash LED + photodiode, recette physique) lèverait.
 *
 * Pur JVM (aucune horloge lue ici : l'appelant fournit les deux instants), sûr entre
 * threads (observations depuis la pompe du flux, lecture depuis l'UI).
 */
class GlassesClockOffset {

    private var minOffsetNs = Long.MAX_VALUE
    private var observations = 0L

    /**
     * Observe une frame ([arrivalNs] monotone téléphone, [presentationTimeUs] horloge
     * média) : met à jour le décalage estimé et rend le retard variable (ns, ≥ 0),
     * ou `null` si le pts est absent ou inexploitable (≤ 0, débordement).
     */
    @Synchronized
    fun observe(arrivalNs: Long, presentationTimeUs: Long): Long? {
        if (presentationTimeUs <= 0L || presentationTimeUs > Long.MAX_VALUE / 1_000L) return null
        val offset = arrivalNs - presentationTimeUs * 1_000L
        if (offset < minOffsetNs) minOffsetNs = offset
        observations += 1
        return (offset - minOffsetNs).coerceAtLeast(0L)
    }

    /** Décalage estimé (ns), `null` sans observation. Sa dérive trahit les horloges. */
    val offsetNs: Long? get() = synchronized(this) { if (observations == 0L) null else minOffsetNs }

    /** Nombre d'observations retenues (les pts invalides ne comptent pas). */
    val samples: Long get() = synchronized(this) { observations }

    /** Oublie le décalage (nouvelle session : les deux horloges ont redémarré). */
    @Synchronized
    fun reset() {
        minOffsetNs = Long.MAX_VALUE
        observations = 0L
    }
}

/** Cause d'abandon d'une frame du lien lunettes, pour les compteurs. */
enum class LinkDrop {
    /** Étranglement temporel (collecteur 1,5 s, banc) : la frame arrive trop tôt. */
    THROTTLE,

    /** Conflation (canal/état conflé) : une frame plus fraîche l'a remplacée. */
    CONFLATED,

    /** Configuration codec : elle prépare le décodeur, sans image. */
    CODEC_CONFIG,

    /** Dimensions nulles : rien n'est dimensionnable. */
    DIMENSIONS,

    /** Le bitstream n'a pas donné d'image (décodeur muet). */
    DECODE_FAIL,

    /** L'image n'a pas donné de JPEG d'envoi. */
    ENCODE_FAIL,
}

/**
 * Statistiques glissantes du lien lunettes → téléphone (µs) : le retard variable du
 * lien ([GlassesClockOffset]), le décodage, l'affichage, et le perçu (les trois
 * additionnés), en p50/p95/p99 sur les [maxSamples] dernières frames.
 *
 * Fenêtre de 100 par défaut (contre 20 ailleurs) : le p99 y est la 99e mesure, pas le
 * max. Adaptateur de [RollingStat] (miroir de [LiveStats]) : l'anneau et les
 * percentiles n'existent qu'une fois ; les mesures négatives sont ramenées à zéro.
 * Sûre entre threads (enregistrement depuis la pompe, lecture depuis l'UI).
 */
class GlassesLinkStats(maxSamples: Int = LINK_WINDOW) {

    private val link = RollingStat(maxSamples)
    private val decode = RollingStat(maxSamples)
    private val display = RollingStat(maxSamples)
    private val perceived = RollingStat(maxSamples)
    private val drops = LongArray(LinkDrop.entries.size)
    private var frames = 0L

    /**
     * Enregistre une frame rendue : [linkUs] le retard variable du lien,
     * [decodeUs] l'entrée-sortie du rendu, [displayUs] la sortie vers le pixel.
     */
    @Synchronized
    fun record(linkUs: Long, decodeUs: Long, displayUs: Long) {
        val l = linkUs.coerceAtLeast(0L)
        val d = decodeUs.coerceAtLeast(0L)
        val r = displayUs.coerceAtLeast(0L)
        link.record(l)
        decode.record(d)
        display.record(r)
        perceived.record(l + d + r)
        frames += 1
    }

    /** Compte une frame abandonnée ([cause] = où elle s'est perdue). */
    @Synchronized
    fun drop(cause: LinkDrop) {
        drops[cause.ordinal] += 1
    }

    /** Frames enregistrées (les abandons ne comptent pas). */
    val samples: Long get() = synchronized(this) { frames }

    /** p50 du perçu (µs), 0 sans mesure. */
    val perceivedP50Us: Long get() = synchronized(this) { perceived.percentile(Percentiles.P50) }

    /** p95 du perçu (µs), 0 sans mesure. */
    val perceivedP95Us: Long get() = synchronized(this) { perceived.percentile(Percentiles.P95) }

    /** p99 du perçu (µs), 0 sans mesure. */
    val perceivedP99Us: Long get() = synchronized(this) { perceived.percentile(Percentiles.P99) }

    /** p95 du seul lien (µs) : le jitter radio, hors téléphone. */
    val linkP95Us: Long get() = synchronized(this) { link.percentile(Percentiles.P95) }

    /** Abandons par cause, dans l'ordre de [LinkDrop]. */
    fun dropCounts(): List<Long> = synchronized(this) { drops.toList() }

    /** Total des abandons. */
    val dropped: Long get() = synchronized(this) { drops.sum() }

    /** Part des abandons parmi les frames vues (0.0 sans frame). */
    val dropRate: Double get() = synchronized(this) {
        val seen = frames + drops.sum()
        if (seen == 0L) 0.0 else drops.sum().toDouble() / seen
    }

    /** Ligne de résumé logcat (`GlassesLink e2e p50/p95/p99 …`), à 1 Hz au plus. */
    fun line(): String = synchronized(this) {
        "GlassesLink e2e p50/p95/p99 ${perceived.percentile(Percentiles.P50)}/" +
            "${perceived.percentile(Percentiles.P95)}/${perceived.percentile(Percentiles.P99)} us" +
            " (lien p95 ${link.percentile(Percentiles.P95)} us)" +
            " drops $frames+${drops.sum()}"
    }

    /** Oublie les mesures de la séance précédente. */
    @Synchronized
    fun reset() {
        link.reset()
        decode.reset()
        display.reset()
        perceived.reset()
        drops.fill(0L)
        frames = 0L
    }

    companion object {
        /** Fenêtre du lien : 200 mesures (~7 s à 30 i/s), pour un p99 significatif. */
        const val LINK_WINDOW = 200
    }
}
