package dev.sam3vision.core.live

/**
 * Étage du trajet d'une frame, du capteur à l'affichage, en **microsecondes**.
 * L'ordre de déclaration **est** l'ordre du trajet : capt → enc → file → http → déc →
 * comp → aff.
 *
 * À ne pas confondre avec [LiveStage], la chronologie en **millisecondes** de l'écran
 * Direct/iHear ([LiveTimings], huit postes de transport) : ici, l'unité est la microseconde,
 * chaque étage couvre une portion disjointe du trajet et le cycle complet donne la
 * **latence perçue**. `label` est le nom court des lignes de journal.
 */
enum class LatencyStage(val label: String) {
    /** Capteur → callback : exposition et livraison de la frame. */
    CAPTURE("capt"),
    /** JPEG prêt : mise à l'échelle, rotation, compression. */
    ENCODE("enc"),
    /** JPEG prêt → départ de la requête : attente du créneau (cadence « latest-wins »). */
    QUEUE("file"),
    /** Aller-retour complet, serveur compris : c'est le seul étage qui dépend du réseau. */
    HTTP("http"),
    /** Réponse → masques décodés : parsing JSON et décodage. */
    DECODE("déc"),
    /** Masques → composition : superposition prête à dessiner. */
    COMPOSE("comp"),
    /** Composition → premier pixel affiché : attente de la vsync. */
    DISPLAY("aff"),
}

/**
 * Chronologie figée d'une frame, en **microsecondes** (`-1` = non mesuré, jamais un zéro
 * inventé). Chaque étage couvre une portion disjointe du trajet, dans l'ordre de
 * [LatencyStage].
 *
 * La **latence perçue** est le cycle complet capteur → premier pixel ([perceivedUs]) ; elle
 * n'est pas l'aller-retour ([rttUs] = étage [LatencyStage.HTTP] seul). Ne jamais confondre
 * les deux : l'écart vient du rafraîchissement et de la file, pas du réseau.
 */
class LatencyFrame private constructor(
    /** Un étage par [LatencyStage], dans l'ordre de l'énumération, `-1` si non mesuré. */
    private val stages: LongArray,
    /** Durée serveur annoncée par le backend (`elapsed_ms` × 1000), `-1` si inconnue. */
    val serverUs: Long,
) {

    /** Durée d'un étage en microsecondes ; `-1` quand il n'a pas pu être mesuré. */
    fun stageUs(stage: LatencyStage): Long = stages[stage.ordinal]

    /** Aller-retour = étage [LatencyStage.HTTP] seul : la part qui dépend du réseau. */
    val rttUs: Long get() = stageUs(LatencyStage.HTTP)

    /** Part réseau = A/R − serveur ; `-1` si l'un des deux manque (pas de partage inventé). */
    val networkUs: Long get() = if (rttUs >= 0L && serverUs >= 0L) rttUs - serverUs else -1L

    /** Somme des étages mesurés : lecture partielle d'une frame qui n'a pas atteint l'affichage. */
    val measuredUs: Long
        get() {
            var total = 0L
            var seen = 0
            var i = 0
            while (i < stages.size) {
                if (stages[i] >= 0L) {
                    total += stages[i]
                    seen++
                }
                i++
            }
            return if (seen == 0) -1L else total
        }

    /** Les sept étages mesurés : la frame est allée jusqu'au premier pixel. */
    val complete: Boolean
        get() {
            var i = 0
            while (i < stages.size) {
                if (stages[i] < 0L) return false
                i++
            }
            return true
        }

    /** Latence perçue : capteur → premier pixel, `-1` tant que le cycle n'est pas complet. */
    val perceivedUs: Long get() = if (complete) measuredUs else -1L

    /** Au moins une mesure : il y a une chronologie à montrer. */
    val known: Boolean get() = measuredUs >= 0L || serverUs >= 0L

    /** Rien de mesuré : le profil n'affiche pas de zéros. */
    val isEmpty: Boolean get() = !known

    companion object {
        /**
         * Frame à partir de ses sept étages (µs, `-1` = non mesuré), dans l'ordre de
         * [LatencyStage]. Le tableau est **copié** : l'appelant peut le réutiliser d'une
         * frame à l'autre sans corrompre cette trace. Les cases manquantes restent `-1`.
         */
        fun of(stages: LongArray, serverUs: Long = -1L): LatencyFrame {
            val copy = LongArray(LatencyStage.entries.size) { -1L }
            var i = 0
            while (i < copy.size && i < stages.size) {
                copy[i] = stages[i]
                i++
            }
            return LatencyFrame(copy, serverUs)
        }
    }
}

/**
 * Marques de la frame en cours, en **nanosecondes monotones** : un poste par [LatencyStage],
 * plus le point de départ (horodatage capteur). L'instantané [snapshot] rend une
 * [LatencyFrame] en microsecondes — la seule unité où les étages de tête (capteur, encodage)
 * ne tombent pas à zéro.
 *
 * Un étage n'est mesuré que si **ses deux** bornes existent : la fin de l'étage précédent
 * (ou [start] pour [LatencyStage.CAPTURE]) et la sienne. Une marque absente ou un ordre
 * impossible donne `-1`, jamais une valeur inventée.
 *
 * Sûre entre threads : le capteur, le réseau et l'UI posent les marques, le profil lit.
 */
class LatencyTrace(private val clock: () -> Long = System::nanoTime) {

    /** Un poste par étage, plus la case [START] (départ au capteur) en tête. */
    private val marks = LongArray(LatencyStage.entries.size + 1) { UNSET }

    /** Début de la frame : horodatage du capteur, base de tous les écarts. */
    @Synchronized
    fun start(atNs: Long = clock()) {
        marks[START] = atNs
    }

    /** Fin d'un étage ; [atNs] sert quand l'horodatage est déjà connu (capteur, vsync). */
    @Synchronized
    fun mark(stage: LatencyStage, atNs: Long = clock()) {
        marks[stage.ordinal + 1] = atNs
    }

    /** Nouvelle frame, nouvelle séance, sortie d'écran : plus aucune marque. */
    @Synchronized
    fun reset() {
        marks.fill(UNSET)
    }

    /** Instantané immuable en microsecondes, avec la durée serveur quand elle est connue. */
    @Synchronized
    fun snapshot(serverUs: Long = -1L): LatencyFrame {
        val stages = LongArray(LatencyStage.entries.size)
        var previous = marks[START]
        var i = 0
        while (i < stages.size) {
            val end = marks[i + 1]
            stages[i] = betweenUs(previous, end)
            previous = end
            i++
        }
        return LatencyFrame.of(stages, serverUs)
    }

    /** Microsecondes entre deux marques ; `-1` si l'une manque ou si l'ordre est impossible. */
    private fun betweenUs(from: Long, to: Long): Long {
        if (from == UNSET || to == UNSET || to < from) return -1L
        return (to - from + NS_PER_US / 2L) / NS_PER_US
    }

    private companion object {
        /** Case du départ (capteur) dans [marks] ; les fins d'étage suivent dans l'ordre. */
        const val START = 0

        /** Marque absente : tout horodatage réel lui est supérieur. */
        const val UNSET = Long.MIN_VALUE

        /** Nanosecondes par microseconde. */
        const val NS_PER_US = 1_000L
    }
}