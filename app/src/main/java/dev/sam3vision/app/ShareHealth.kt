package dev.sam3vision.app

import dev.sam3vision.core.share.ShareSend
import dev.sam3vision.core.share.shareSendMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Latest tracking problem, readable in Settings: relay refusal, unreachable
 * relay, or never-received position (refused location). Without this
 * feedback, a failing share is indistinguishable from a loved one with
 * nothing to send — exactly what left the app map empty without a word.
 */
object ShareHealth {

    private val _message = MutableStateFlow<String?>(null)

    /** Current message, `null` when everything left on the last send. */
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Records a send result; only a success clears the alert. */
    fun note(result: ShareSend) {
        when (result) {
            ShareSend.Sent -> clear()
            ShareSend.Skipped -> Unit
            else -> _message.value = shareSendMessage(result)
        }
    }

    /** No position: refused permission, cut GPS or fix not yet taken. */
    fun noFix() {
        _message.value = "Unknown position: allow the phone location to be tracked."
    }

    fun clear() {
        _message.value = null
    }
}
