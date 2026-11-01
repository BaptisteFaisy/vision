package dev.sam3vision.core.share

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Cadence du direct : **15 images par seconde**. La page de l'aidant suit une
 * promenade, pas un film — mais sous 10 im/s, le mouvement saccade et l'aidant
 * perd le fil des gestes du proche. Le rythme réel est mesuré au log `direct:`
 * (médiane des envois), pas supposé de cette constante.
 */
const val STREAM_FRAME_MS = 66L

/**
 * Une frame plus vieille que ça n'est plus poussée : caméra fermée ou figée, le
 * direct s'arrête de lui-même au lieu d'envoyer la même image en boucle. À
 * 15 im/s, une frame de plus de 4 tours n'a plus rien du direct.
 */
const val STREAM_FRAME_MAX_AGE_MS = 300L

/** Durée d'attente avant de rouvrir le direct après une coupure. */
const val STREAM_RETRY_MS = 2_000L

/**
 * Tours de pompe vides consécutifs avant d'annoncer la caméra éteinte : un
 * micro-trou (encodeur lent, GC, executor saturé) ne doit pas effacer l'image
 * des aidants — seul un silence d'environ une seconde envoie l'avis `false`.
 * À 15 im/s, 15 tours ≈ 1 s.
 */
const val STREAM_CAMERA_OFF_TURNS = 15

/**
 * Vrai quand le silence dure assez pour annoncer la caméra éteinte : en
 * dessous du seuil, la page garde sa dernière image et le trou reste invisible.
 */
fun cameraOffReady(emptyTurns: Int): Boolean = emptyTurns >= STREAM_CAMERA_OFF_TURNS

/**
 * URL du direct (`/ws?role=push`) : même origine que le relais, en `wss` quand il
 * est en `https`. La clé d'écriture voyage dans l'URL — c'est le même secret que
 * `/api/share`, et le relais la compare au flux avant d'accepter les frames.
 *
 * `null` si la base n'est pas une URL ou si l'identifiant est invalide : mieux
 * vaut aucun direct qu'un direct sur le flux d'un autre.
 */
fun streamUrlOf(base: String, shareId: String, writeKey: String): String? {
    val url = base.trim().toHttpUrlOrNull() ?: return null
    val id = normalizeShareId(shareId)
    if (!SHARE_ID.matches(id)) return null
    // `HttpUrl` refuse le schéma `wss` : on construit en `https` puis on change
    // le préfixe — l'hôte, le port et l'encodage des paramètres restent les mêmes.
    val plain = url.newBuilder().encodedPath("/ws").query(null).fragment(null)
        .setQueryParameter("share", id)
        .addQueryParameter("role", "push")
        .addQueryParameter("key", writeKey)
        .build().toString()
    return if (url.isHttps) plain.replaceFirst("https://", "wss://")
    else plain.replaceFirst("http://", "ws://")
}

/** Nombre d'aidants annoncé par le relais, `null` si le message n'en est pas un. */
fun audienceCountOf(text: String): Int? =
    AUDIENCE.find(text)?.groupValues?.get(1)?.toIntOrNull()

/**
 * Vrai quand au moins un aidant regarde : le téléphone **n'émet que dans ce cas**.
 * Sans cette règle, la caméra du proche pousserait des images dans le vide à
 * chaque promenade, sur son forfait et sa batterie.
 */
fun audienceWatching(text: String): Boolean? = audienceCountOf(text)?.let { it > 0 }

/** `{"type":"audience","watch":1}` — le seul message texte que le relais envoie au téléphone. */
private val AUDIENCE = Regex("\"watch\"\\s*:\\s*(\\d{1,9})")

/**
 * Annonce de la caméra au relais : `{"type":"camera","on":…}`, même forme que
 * l'avis d'audience. La salle le transmet aux aidants, qui effacent l'image
 * dès `on:false` au lieu d'attendre que la dernière frame périme.
 */
fun cameraNotice(on: Boolean): String = "{\"type\":\"camera\",\"on\":$on}"

/** Seul identifiant accepté par le relais ; même forme que `CaregiverPage`. */
private val SHARE_ID = Regex("[a-z0-9-]{1,32}")
