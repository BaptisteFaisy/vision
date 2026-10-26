package dev.sam3vision.glasses

/**
 * Décision de lancement du spike, sans Android : ce que les extras demandent et
 * dans quel ORDRE l'appliquer.
 *
 * `MainActivity` se contente de lire l'`Intent` (via [SpikeLaunch.fromExtras])
 * puis d'exécuter les étapes ; la décision et sa séquence vivent ici, donc elles
 * sont prouvées sur JVM par `SpikeLaunchTest` sans instancier d'`Intent`.
 *
 * Ordre imposé : [LaunchStep.ARM_ANALYZE] passe toujours avant
 * [LaunchStep.START_SESSION]. Le flux DAT est asynchrone, mais rien ne garantit
 * qu'aucune frame n'arrive dès la session ouverte : armée après, la première
 * frame repartirait sans analyse — c'est ce que faisait le lancement `mock` seul.
 */

/** Extra `--ez mock true` : lunettes simulées + caméra du téléphone en flux. */
internal const val EXTRA_MOCK = "mock"

/** Extra `--ez analyze true` : envoi réseau (SAM puis LLM) armé au démarrage. */
internal const val EXTRA_ANALYZE = "analyze"

/** Ligne de journal commune à l'armement automatique et à la bascule manuelle. */
internal const val ANALYZE_ARMED_MESSAGE = "analyse réseau ACTIVÉE (1 frame / 1,5 s)"

/** Une étape du démarrage, exécutée dans l'ordre rendu par [SpikeLaunch.plan]. */
internal enum class LaunchStep {
    /** Autorise l'envoi réseau : à faire avant toute frame. */
    ARM_ANALYZE,

    /** Active Mock Device Kit et apparié un appareil simulé. */
    ENABLE_MOCK,

    /** Alimente la caméra simulée avec celle du téléphone. */
    PHONE_CAMERA_FEED,

    /** Ouvre la session (mock ou vraies lunettes) et laisse les frames arriver. */
    START_SESSION,
}

internal object SpikeLaunch {

    /** Ce qu'un lancement demande. */
    data class Options(val mock: Boolean = false, val analyze: Boolean = false) {

        /**
         * Le spike ne s'auto-démarre qu'en mode mock : en mode réel il faut une
         * paire appairée, donc un tap — l'`Intent` ne fait qu'armer l'analyse.
         */
        val autoLaunch: Boolean get() = mock
    }

    /**
     * Traduit les deux extras lus de l'`Intent`, `null` quand ils sont absents
     * (`getBooleanExtra` rend alors le défaut). Pur : l'adaptateur Android passe
     * les booléens, cette fonction ne connaît pas l'`Intent`.
     */
    fun fromExtras(mock: Boolean?, analyze: Boolean?): Options =
        Options(mock = mock == true, analyze = analyze == true)

    /**
     * Étapes à exécuter, dans l'ordre. L'armement de l'analyse précède l'ouverture
     * de session ; sans `mock`, aucune session n'est ouverte depuis l'`Intent` et
     * l'analyse n'est que pré-armée.
     */
    fun plan(options: Options): List<LaunchStep> = buildList {
        if (options.analyze) add(LaunchStep.ARM_ANALYZE)
        if (options.mock) {
            add(LaunchStep.ENABLE_MOCK)
            add(LaunchStep.PHONE_CAMERA_FEED)
            add(LaunchStep.START_SESSION)
        }
    }
}