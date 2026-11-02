package dev.sam3vision.core.live

/**
 * Ce qu'une panne du direct mérite de dire, du premier hoquet à l'arrêt conseillé.
 *
 * L'ordre est l'escalade : un [LOST] n'informe que du hoquet, un [STOP] propose de
 * s'arrêter. Aucun palier ne se répète dans un même épisode, et deux épisodes proches
 * ne bavardent pas (voir [OutageWatch]).
 */
enum class OutageStep {
    /** La vision ne répond plus : ça réessaie (6 s). */
    LOST,

    /** La vision est coupée, le guidage GPS continue (15 s). */
    DEGRADED_GPS,

    /** La vision est coupée et il n'y a rien d'autre à faire (15 s). */
    DEGRADED_NONE,

    /** Il faut s'arrêter et réessayer (60 s). */
    STOP,

    /** C'est revenu (retour après un [LOST] ou plus grave). */
    BACK,
}

/**
 * Veille de panne du direct : transforme un flux de ticks « sain / pas sain » en **au plus
 * une annonce par palier et par épisode**.
 *
 * Pourquoi une machine à états et pas un simple seuil : l'app doit se taire quand tout va
 * bien, dire une fois quand ça casse, et ne pas harceler quand ça dure. Le silence total
 * d'aujourd'hui est le pire des échecs — on marche avec des masques figés en croyant
 * qu'ils sont vivants.
 *
 * Règles, dans l'ordre :
 * 1. **Un palier par tick** : si deux seuils sont franchis d'un coup (tick espacé), on ne
 *    rend que le plus grave, les paliers sautés sont marqués comme dits.
 * 2. **Anti-bavardage** : deux annonces d'épisodes différents sont séparées par
 *    [episodeGapMs] (30 s) — un Wi-Fi qui clignote ne produit pas de phrase. [BACK] est
 *    exempté : il confirme une reprise déjà annoncée.
 * 3. **Démarrage à froid** : avant la première frame, le conteneur GPU met 30-50 s à
 *    démarrer ; les seuils sont donc décalés ([coldDegradedMs], [coldStopMs]) pour ne pas
 *    crier pendant un réveil normal, et [LOST] n'existe pas dans cet état.
 * 4. **Anti-flap du retour** : un retour plus court que [backMinMs] ne se dit pas.
 *
 * Pur, sans horloge : l'appelant fournit `nowMs` (horloge monotone) à chaque tick.
 */
class OutageWatch(
    /** Seuil du premier mot : la vision ne répond plus. */
    private val lostMs: Long = LOST_MS,
    /** Seuil du constat durable : la vision est coupée. */
    private val degradedMs: Long = DEGRADED_MS,
    /** Seuil de la proposition d'arrêt. */
    private val stopMs: Long = STOP_MS,
    /** Seuil « vision coupée » avant la première frame (réveil GPU). */
    private val coldDegradedMs: Long = COLD_DEGRADED_MS,
    /** Seuil « arrêtez » avant la première frame. */
    private val coldStopMs: Long = COLD_STOP_MS,
    /** Retour trop court pour être annoncé. */
    private val backMinMs: Long = BACK_MIN_MS,
    /** Silence minimum entre deux annonces d'épisodes différents. */
    private val episodeGapMs: Long = EPISODE_GAP_MS,
) {

    /** Début de l'épisode courant ; `null` = tout va bien. */
    private var sinceMs: Long? = null

    /**
     * Épisode démarré juste après une annonce ([episodeGapMs]) : son premier palier
     * ([OutageStep.LOST]) est tu, les suivants parlent. C'est ce qui empêche un Wi-Fi qui
     * clignote de répéter « Guidance unavailable. » toutes les dix secondes, sans pour
     * autant rendre muette une vraie coupure qui s'installe.
     */
    private var muteFirstWord = false

    /** Paliers déjà dits dans cet épisode (un épisode ne se répète jamais). */
    private var lostSaid = false
    private var degradedSaid = false
    private var stopSaid = false

    /** Dernière annonce, tous épisodes confondus (anti-bavardage). */
    private var lastSaidAtMs: Long? = null

    /** Un palier a-t-il été annoncé dans l'épisode courant ? (condition du [BACK]) */
    private val announced: Boolean get() = lostSaid || degradedSaid || stopSaid

    /**
     * Un tick de veille : rend le palier à annoncer maintenant, ou `null` (rien à dire).
     *
     * [warmed] dit qu'une frame a déjà abouti dans la séance : c'est ce qui distingue une
     * coupure d'un démarrage de conteneur. [degradedAvailable] dit qu'un guidage GPS tourne
     * encore, donc que la coupure de vision n'est pas une impasse.
     */
    fun onTick(nowMs: Long, healthy: Boolean, warmed: Boolean, degradedAvailable: Boolean): OutageStep? {
        if (healthy) return heal(nowMs)
        val start = sinceMs ?: nowMs.also {
            sinceMs = it
            val last = lastSaidAtMs
            muteFirstWord = last != null && nowMs - last < episodeGapMs
        }
        val elapsed = (nowMs - start).coerceAtLeast(0L)
        return escalate(nowMs, elapsed, warmed, degradedAvailable)
    }

    /** Fin de séance (arrêt du direct, mise en poche) : plus rien à dire sur l'ancienne. */
    fun reset() {
        clearEpisode()
        lastSaidAtMs = null
    }

    /** Un épisode se termine : [BACK] s'il avait parlé et s'il a duré, rien sinon. */
    private fun heal(nowMs: Long): OutageStep? {
        val start = sinceMs ?: return null
        val elapsed = (nowMs - start).coerceAtLeast(0L)
        val spoke = announced
        clearEpisode()
        if (!spoke || elapsed < backMinMs) return null
        // Le retour est une annonce : il compte pour l'anti-bavardage du suivant.
        lastSaidAtMs = nowMs
        return OutageStep.BACK
    }

    /** Clôt l'épisode sans toucher à la mémoire d'anti-bavardage. */
    private fun clearEpisode() {
        sinceMs = null
        lostSaid = false
        degradedSaid = false
        stopSaid = false
        muteFirstWord = false
    }

    /** Franchissement de seuil : le plus grave non encore dit, sinon rien. */
    private fun escalate(
        nowMs: Long,
        elapsed: Long,
        warmed: Boolean,
        degradedAvailable: Boolean,
    ): OutageStep? {
        val degradedAt = if (warmed) degradedMs else coldDegradedMs
        val stopAt = if (warmed) stopMs else coldStopMs
        if (elapsed >= stopAt) {
            if (stopSaid) return null
            lostSaid = true
            degradedSaid = true
            stopSaid = true
            return say(nowMs, OutageStep.STOP)
        }
        if (elapsed >= degradedAt) {
            if (degradedSaid) return null
            lostSaid = true
            degradedSaid = true
            return say(nowMs, if (degradedAvailable) OutageStep.DEGRADED_GPS else OutageStep.DEGRADED_NONE)
        }
        if (!warmed && elapsed < degradedAt) return null
        if (elapsed < lostMs || lostSaid) return null
        lostSaid = true
        // Seul le premier mot d'un épisode rapproché est tu : l'escalade, elle, parle.
        return if (muteFirstWord) null else say(nowMs, OutageStep.LOST)
    }

    /** Enregistre l'annonce et rend le palier (une annonce en avance le compteur). */
    private fun say(nowMs: Long, step: OutageStep): OutageStep {
        lastSaidAtMs = nowMs
        return step
    }

    private companion object {
        /** Première frame non aboutie : 6 s, au-delà d'un hoquet réseau ordinaire. */
        const val LOST_MS = 6_000L

        /** Constat durable : la vision est coupée. */
        const val DEGRADED_MS = 15_000L

        /** Proposition d'arrêt. */
        const val STOP_MS = 60_000L

        /** Avant la première frame : le GPU met 30-50 s, on attend donc une minute. */
        const val COLD_DEGRADED_MS = 60_000L

        /** Avant la première frame : deux minutes de réveil raté justifient l'arrêt. */
        const val COLD_STOP_MS = 120_000L

        /** Un retour plus court qu'une seconde n'est pas un événement. */
        const val BACK_MIN_MS = 1_000L

        /** Deux épisodes qui parlent à moins de 30 s d'intervalle font du bavardage. */
        const val EPISODE_GAP_MS = 30_000L
    }
}
