package dev.sam3vision.app.glasses

/**
 * Ce que l'ordre vocal « iSee » fait de la session : démarrer, arrêter, ou
 * refuser faute de paire liée. Décidé dans [toggleAction], testé en JVM.
 */
enum class SessionToggle { START, STOP, NOT_LINKED }

/**
 * Ordre vocal « iSee » : une session en cours (ou en démarrage) s'arrête ;
 * sinon elle s'ouvre, mais seulement si une paire est liée en Bluetooth —
 * sans lien, l'ordre se refuse au lieu d'attendre 30 s pour finir en rouge.
 * Pur : le SDK n'est lu que par l'adaptateur ([GlassesController.sessionControl]).
 */
fun toggleAction(phase: SessionPhase, linked: Boolean): SessionToggle = when {
    phase == SessionPhase.STARTED || phase == SessionPhase.STARTING -> SessionToggle.STOP
    linked -> SessionToggle.START
    else -> SessionToggle.NOT_LINKED
}

/**
 * Ce que l'ordre vocal « iSee » demande aux lunettes : bascule la session et
 * rend ce qui a été fait, pour la confirmation parlée. Câblé par `AppNav`
 * (le pont lunettes-iSee), qui est le seul à tenir les deux côtés.
 */
fun interface GlassesSessionControl {
    /** Bascule la session et rend l'action effectuée. */
    fun toggle(): SessionToggle
}

/**
 * Adaptateur sur l'API publique du contrôleur : aucune ligne ajoutée à
 * `GlassesController` (garde 500 lignes). Le refus sans lien n'appelle rien —
 * iSee le dit à voix haute, inutile d'ouvrir une session morte.
 */
fun GlassesController.sessionControl(): GlassesSessionControl = GlassesSessionControl {
    val action = toggleAction(state.value.phase, hasLinkedDevice(linkedStates()))
    when (action) {
        SessionToggle.STOP -> disconnect()
        SessionToggle.START -> connect()
        SessionToggle.NOT_LINKED -> Unit
    }
    action
}
