package dev.sam3vision.app

import dev.sam3vision.core.live.TimelineText
import dev.sam3vision.core.model.TimelineSummary
import dev.sam3vision.core.net.BackendRoute
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Textes et règles pures du mode Direct : messages d'état, libellés du transport et
 * contexte du journal pour le LLM. Extrait de `LiveViewModel` pour rester sous la
 * limite de 500 lignes et pour que tout soit testable sans Android.
 */

/** Direct sans détecteur : l'écran reste utilisable pour les questions sur l'image. */
const val SAM_ABSENT_LINE = "SAM detector not configured — image questions only"

/** Direct sans détecteur joignable : même repli, avec la raison exacte. */
fun samUnreachableLine(reason: String): String =
    "SAM detector unreachable ($reason) — image questions only"

/** Direct en WebSocket : une frame par message, sans le surcoût HTTP d'un POST. */
const val TRANSPORT_WS: String = "WebSocket"

/** Socket tombé sans repli HTTP (jamais replié, hors ligne, fermé) : à signaler franchement. */
const val TRANSPORT_WS_KO: String = "WebSocket down"

/** Direct en WebSocket via le cellulaire imposé (session lunettes, Wi-Fi lunettes sans internet). */
const val TRANSPORT_WS_CELL: String = "WebSocket (cell)"

/** Repli HTTP : le socket est tombé, les frames partent en `POST /live`. */
const val TRANSPORT_HTTP: String = "HTTP"

/**
 * Direct sur le **tracker** (`/session` + `/frame`) : les ids d'objets sont stables d'une
 * frame à l'autre, une frame en vol dans un `FrameGate`.
 */
const val TRANSPORT_TRACK: String = "Tracker"

/** Aucune clé LLM : rien ne peut fonctionner, ni la vision ni les questions. */
const val LLM_ABSENT_LINE = "LLM key missing — enter it in Settings"

/**
 * Libellé du transport : le tracker prime, sinon HTTP si le repli colle, sinon
 * WebSocket ou KO selon le socket. [cellular] se lit seul en session lunettes
 * ([BackendRoute.active]) ; injecté pour rester testable en JVM sans état global.
 */
fun transportLabel(
    trackerEnabled: Boolean,
    wsConnected: Boolean,
    cellular: Boolean = BackendRoute.active,
    httpFallback: Boolean = false,
): String = when {
    trackerEnabled -> TRANSPORT_TRACK
    httpFallback -> TRANSPORT_HTTP
    !wsConnected -> TRANSPORT_WS_KO
    cellular -> TRANSPORT_WS_CELL
    else -> TRANSPORT_WS
}

/** Question posée avant la première frame gardée : on le dit clairement, sans bloquer. */
const val NO_FRAME_LINE = "No image yet — reframe and retry in a second"

/**
 * Contexte du journal pour le LLM, `null` s'il n'y a **aucune piste** : mieux vaut
 * pas de contexte du tout que « rien de visible » alors que l'image est fournie.
 * Fonction pure, testée en JVM.
 */
fun timelineContextOrNull(
    global: TimelineSummary,
    focus: TimelineSummary?,
    label: String?,
    language: SpeechLanguage = SpeechLanguage.DEFAULT,
): String? {
    if (global.empty && (focus == null || focus.empty)) return null
    return TimelineText.context(global, focus, label, language)
}
