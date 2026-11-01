package dev.sam3vision.app.isee.vm

/**
 * Voix de la dictée (SPEC v4, câblée par IseeViewModel) : chaque ordre
 * « mode … » appelle son exécutant — modes dehors/dedans/find, Traversée,
 * GPS, lunettes. Le texte non-ordre ne passe jamais ici ([IseeDictationRouter]
 * le rend au LLM).
 */
internal class IseeDictationActions(
    private val modes: IseeModeOrders,
    private val runCrossing: suspend (String) -> Unit,
    private val onCrossingChanged: () -> Unit,
    private val confirmFindStopped: suspend () -> Unit,
    private val gpsGo: suspend (String) -> Unit,
    private val gpsOff: suspend () -> Unit,
    private val glasses: suspend () -> Unit,
) : DictationActions {
    override suspend fun onOutdoor() {
        modes.run("mode dehors")
    }

    override suspend fun onIndoor() {
        modes.run("mode dedans")
    }

    override suspend fun onCrossing(active: Boolean) {
        runCrossing(if (active) "crossing mode on" else "crossing mode off")
        onCrossingChanged()
    }

    override suspend fun onFind(label: String) {
        modes.run("find the " + label)
    }

    override suspend fun onFindOff() {
        confirmFindStopped()
    }

    override suspend fun onGpsGo(destination: String) {
        gpsGo(destination)
    }

    override suspend fun onGpsOff() {
        gpsOff()
    }

    override suspend fun onGlasses(on: Boolean) {
        glasses()
    }
}
