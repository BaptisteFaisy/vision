package dev.sam3vision.core.mask

import java.lang.reflect.Method

/**
 * Sonde d'allocation de la JVM courante : octets alloués **par le thread courant**, lus sur
 * `com.sun.management.ThreadMXBean`. Tout est par réflexion — `java.lang.management` comme
 * `com.sun.*` sont absents de l'`android.jar` de compilation — donc aucune importation à la
 * compilation, et `null` là où la JVM n'expose pas ce compteur.
 *
 * La soustraction d'une exécution vide (baseline) retire le surcoût fixe de la réflexion ;
 * ce qui reste est l'allocation réelle du bloc mesuré. `-1` quand la sonde est indisponible :
 * les tests l'impriment alors mais ne tiennent pas ce chiffre pour une preuve.
 */
internal object JvmAllocations {

    private val pair: Pair<Any, Method>? = probe()

    val available: Boolean get() = pair != null

    /** Octets alloués depuis le démarrage de la JVM, ou `-1` si la sonde est indisponible. */
    fun bytes(): Long = pair?.let { (bean, method) -> method.invoke(bean) as? Long ?: -1L } ?: -1L

    /**
     * Octets alloués par [block], net du coût de la mesure elle-même. Un bloc qui n'alloue
     * rien rend `0` ; `-1` si la sonde est indisponible.
     */
    fun netAllocated(block: () -> Unit): Long {
        if (!available) return -1L
        return gross(block) - gross {}
    }

    /** Différence du compteur avant/après [block] ; le surcoût de la réflexion est constant. */
    private fun gross(block: () -> Unit): Long {
        val before = bytes()
        block()
        return bytes() - before
    }

    /** Le compteur HotSpot, s'il existe ici : `bean` to `méthode`, sinon `null`. */
    private fun probe(): Pair<Any, Method>? {
        val bean = callStatic("java.lang.management.ManagementFactory", "getThreadMXBean") ?: return null
        val iface = runCatching { Class.forName("com.sun.management.ThreadMXBean") }.getOrNull()
        if (iface == null || !iface.isInstance(bean) || bean == null) return null
        val method = runCatching { iface.getMethod("getCurrentThreadAllocatedBytes") }.getOrNull() ?: return null
        return bean to method
    }

    private fun callStatic(className: String, methodName: String): Any? = runCatching {
        Class.forName(className).getMethod(methodName).invoke(null)
    }.getOrNull()
}