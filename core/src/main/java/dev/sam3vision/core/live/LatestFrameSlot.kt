package dev.sam3vision.core.live

/**
 * Contenu d'une frame : son JPEG en base64 et les deux horodatages qui la décrivent.
 *
 * Objet **réutilisé** : la réserve n'alloue rien par frame — la `String` du JPEG est
 * partagée (jamais recopiée), seuls les trois champs sont réaffectés.
 */
class FramePayload {
    /** JPEG encodé en base64, **sans saut de ligne** (une ligne casserait le décodage). */
    var jpegB64: String = ""

    /** Exposition du capteur (ns monotones) : le poste « capture » de la chronologie. */
    var captureAtNs: Long = 0L

    /** Fin de l'encodage JPEG (ns monotones) : le poste « jpeg prêt ». */
    var encodedAtNs: Long = 0L

    /** Vide le contenu (sans garder de référence sur le JPEG). */
    fun clear() {
        jpegB64 = ""
        captureAtNs = 0L
        encodedAtNs = 0L
    }
}

/**
 * Réserve d'**une seule** frame : celle qui part dès que le créneau se libère.
 *
 * Règle du produit, appliquée ici : **au plus une frame en réserve, jamais une file**.
 * Une frame qui arrive alors que la réserve est pleine **remplace** l'ancienne (la plus
 * récente gagne : la scène a changé, une vieille image n'apprend rien) et l'ancienne est
 * comptée dans [dropped]. Il n'y a donc jamais deux frames en attente : la cadence ne se
 * règle pas en empilant des frames, et le nombre de JPEGEncodeurs lancés par cycle
 * reste borné à deux (un en vol, un en réserve).
 *
 * Sûre entre threads : la caméra dépose depuis son thread d'analyse, la sonde retire
 * depuis son exécuteur. Le verrou n'est presque jamais contenté (une écriture par
 * frame, une lecture par réponse) ; avant, les trois champs vivaient dans un champ
 * **non synchronisé**, lu et écrit depuis deux threads différents.
 *
 * Zéro allocation par frame : [take] recopie les trois champs dans un [FramePayload]
 * fourni par l'appelant, au lieu de construire un objet par frame.
 */
class LatestFrameSlot {

    private val lock = Any()

    /** JPEG en réserve, `null` si la réserve est vide. */
    private var jpegB64: String? = null
    private var captureAtNs = 0L
    private var encodedAtNs = 0L
    private var dropped = 0L

    /** Une frame attend-elle ? (la caméra peut éviter de convertir une image inutile). */
    val pending: Boolean get() = synchronized(lock) { jpegB64 != null }

    /** Frames **jetées** parce que la réserve était déjà pleine (jamais mises en file). */
    val droppedFrames: Long get() = synchronized(lock) { dropped }

    /**
     * Dépose une frame en réserve. La plus récente gagne : l'ancienne est jetée et
     * comptée dans [droppedFrames]. Renvoie `true` si une frame attend désormais.
     */
    fun offer(jpegB64: String, captureAtNs: Long, encodedAtNs: Long): Boolean {
        synchronized(lock) {
            if (this.jpegB64 != null) dropped += 1
            this.jpegB64 = jpegB64
            this.captureAtNs = captureAtNs
            this.encodedAtNs = encodedAtNs
            return true
        }
    }

    /**
     * Retire la frame en réserve et la recopie dans [out] (vide la réserve).
     * `false` si la réserve était vide ; [out] n'est alors pas modifié.
     */
    fun take(out: FramePayload): Boolean {
        synchronized(lock) {
            val current = jpegB64 ?: return false
            out.jpegB64 = current
            out.captureAtNs = captureAtNs
            out.encodedAtNs = encodedAtNs
            jpegB64 = null
            captureAtNs = 0L
            encodedAtNs = 0L
            return true
        }
    }

    /** Vide la réserve (arrêt de l'enregistrement, changement de session). */
    fun clear() {
        synchronized(lock) {
            jpegB64 = null
            captureAtNs = 0L
            encodedAtNs = 0L
        }
    }
}