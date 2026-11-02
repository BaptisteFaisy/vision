package dev.sam3vision.app.voicenav

/**
 * Effets Android du premier plan, un par appel, chacun gardé par
 * [GuidanceServiceRunner]. Aucune classe Android ici : la séquence se teste en JVM.
 */
interface ForegroundFx {

    /**
     * Crée le canal principal s'il manque, et dit s'il existe ensuite.
     * `false` = canal indisponible : l'entrée au premier plan passe par le canal de secours.
     */
    fun ensureChannel(): Boolean

    /** Entre au premier plan (canal de secours si [fallback]) ; lève au refus système. */
    fun enterForeground(fallback: Boolean)

    /** Sort du premier plan et arrête le service (notification retirée). */
    fun exitForeground()

    /** Vrai si le service est déjà au premier plan : garde d'idempotence. */
    fun isForeground(): Boolean
}

/**
 * Séquence **légale** du premier plan d'un service long : entrer au premier plan en
 * premier, une seule fois, et ne jamais laisser sortir d'exception.
 *
 * Android tue le processus quand `startForegroundService()` reste cinq secondes sans
 * `startForeground()` (`ForegroundServiceDidNotStartInTimeException`, seul crash applicatif
 * constaté sur le téléphone de recette). D'où : le canal ne retarde jamais l'entrée au
 * premier plan (repli sur le canal de secours), un refus devient une **sortie propre**
 * (notification retirée + `stopSelf`) et non un FATAL, et un arrêt ne dépend pas d'un
 * `stopForeground` qui aurait levé.
 *
 * Ne lève jamais : les incidents sont accumulés dans [incidents] pour que l'appelant les
 * journalise (`Log.w`) sans changer le cours du démarrage. Réutilisé par les trois services
 * longs (guidage vocal, suivi parent, session lunettes).
 */
class GuidanceServiceRunner(private val fx: ForegroundFx) {

    private val past = mutableListOf<String>()

    /** Incidents du dernier appel, dans l'ordre (`<étape>: <TypeDException>`). */
    val incidents: List<String> get() = past.toList()

    /**
     * Tient le premier plan et rend l'état atteint. Démarrage doublé = simple
     * rafraîchissement du texte, jamais une deuxième entrée ni une sortie.
     */
    fun start(): Boolean {
        past.clear()
        if (attempt("état") { fx.isForeground() } == true) {
            guarded("rafraîchissement") { fx.enterForeground(false) }
            return true
        }
        val primary = attempt("canal") { fx.ensureChannel() } == true
        if (guarded("premier plan") { fx.enterForeground(!primary) }) return true
        guarded("sortie après refus") { fx.exitForeground() }
        return false
    }

    /** Sort du premier plan ; `false` si même la sortie a échoué (rien ne sort d'ici). */
    fun stop(): Boolean {
        past.clear()
        return guarded("sortie du premier plan") { fx.exitForeground() }
    }

    /** Effet qui rend une valeur ; `null` = il a levé (incident noté). */
    private inline fun attempt(label: String, block: () -> Boolean): Boolean? =
        try {
            block()
        } catch (t: Throwable) {
            record(label, t)
            null
        }

    /** Effet sans valeur de retour ; `false` = il a levé (incident noté). */
    private inline fun guarded(label: String, block: () -> Unit): Boolean =
        try {
            block()
            true
        } catch (t: Throwable) {
            record(label, t)
            false
        }

    private fun record(label: String, t: Throwable) {
        past += "$label: ${t.javaClass.simpleName}"
    }
}
