package dev.sam3vision.app.ihear

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.media.CacheCleanerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fin de session iSee : purge totale à la fermeture (option A) et minuteur
 * d'inactivité d'arrière-plan (option C).
 *
 * La purge a sa **propre portée** : à `onDestroy`, le `viewModelScope` est annulé juste
 * après l'appel, si bien que la coroutine lancée dedans était tuée avant d'avoir écrit —
 * des messages survivaient alors à la fermeture. L'écran est vidé tout de suite, puis la
 * table Room et le cache suivent, indépendamment de la vie du ViewModel.
 */
class IhearSession(
    private val cleaner: CacheCleanerManager,
    private val player: SpeechPlayer,
    private val update: ((IhearUiState) -> IhearUiState) -> Unit,
) {

    private val purgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fermeture complète : plus rien à l'écran, puis plus rien sur le téléphone. */
    fun onClosed() {
        player.stop()
        update {
            it.copy(
                entries = emptyList(),
                selected = INTRO_SELECTION,
                speakingEntryId = null,
                photoPath = null,
            )
        }
        purgeScope.launch { cleaner.purgeAll() }
    }

    /** L'app part derrière : minuteur d'inactivité de 30 min armé. */
    fun onBackground() = cleaner.onPause()

    /** L'app revient au premier plan : minuteur annulé. */
    fun onForeground() = cleaner.onResume()
}
