package dev.sam3vision.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.camera.view.PreviewView
import androidx.compose.ui.graphics.toArgb
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Aperçu caméra **et** masques dans la même surface native.
 *
 * Pourquoi : un `AndroidView` est une vraie `View`, dessinée par le `ViewGroup` après le
 * canevas Compose — un masque composé en Compose au-dessus de l'aperçu passe donc
 * *derrière* lui (invisible). Ici la `PreviewView` et la couche de masques sont deux
 * enfants d'un même [FrameLayout], dans l'ordre voulu : l'aperçu, puis les masques.
 *
 * Les masques arrivent par [setMasks] (liste de bitmaps déjà colorisés, une par objet,
 * toutes à la taille de la frame) ; ils sont fusionnés en une seule image affichée en
 * `CENTER_CROP`, exactement le cadrage de la `PreviewView` (`FILL_CENTER`).
 */
class CameraSurface(context: Context, private val preview: PreviewView) : FrameLayout(context) {

    companion object {
        /** Fusions hors thread UI : un seul fil partagé, l'ordre des frames est gardé. */
        private val mergeWorker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "CameraSurface-merge").apply { isDaemon = true }
        }
    }

    private val maskView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    private var shown: List<Bitmap> = emptyList()

    /**
     * Fusion sans filtre bilinéaire : les masques sont dessinés 1:1 (même taille que la
     * frame), le filtre ne changerait aucun pixel — il ne coûterait que du CPU.
     */
    private val mergePaint = Paint()

    /**
     * Tampons de fusion alternés (ping-pong, motif `MaskPainter`) : recréés seulement
     * quand la taille de frame change, et on ne repeint jamais celui qui s'affiche.
     */
    private val mergeBuffers: Array<Bitmap?> = arrayOfNulls(2)
    private var mergeFront = 0

    /** Conflation des fusions : sous rafale, seule la dernière liste est fusionnée. */
    private val conflated = ConflatedJobs<List<Bitmap>>()

    init {
        // Fond sombre : sans flux ni masque, aucun blanc système ne transparaît.
        setBackgroundColor(AppColors.background.toArgb())
        // Les masques d'abord ; l'aperçu vient **dessous**, posé à l'attachement.
        addView(maskView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * L'aperçu est attaché **un tour de boucle plus tard**, jamais dans le constructeur :
     * Compose insère la nouvelle surface **avant** de retirer l'ancienne dans la même
     * frame, si bien qu'au moment du constructeur la `PreviewView` est encore parentée —
     * et `addView` lève « The specified child already has a parent » (crash mesuré sur
     * le téléphone : photo après vidéo, puis vidéo après photo). Orphelin (sans parent),
     * il est posé tout de suite : un tour de boucle gagné à chaque recréation.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (preview.parent == null) attachPreview() else post(::attachPreview)
    }

    /** La surface quitte l'écran : on rend l'aperçu à qui voudra (Live, Capture). */
    override fun onDetachedFromWindow() {
        if (preview.parent === this) removeView(preview)
        super.onDetachedFromWindow()
    }

    /** Détache l'aperçu de son ancienne surface puis le pose sous les masques. */
    private fun attachPreview() {
        if (!isAttachedToWindow || preview.parent === this) return
        (preview.parent as? ViewGroup)?.removeView(preview)
        addView(preview, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * Remplace les masques affichés ; sans changement de liste, rien n'est redessiné.
     * La fusion (plusieurs masques) tourne hors thread UI, le résultat est posé au retour.
     */
    fun setMasks(masks: List<Bitmap>) {
        if (masks == shown) return
        shown = masks
        if (masks.size <= 1) {
            maskView.setImageBitmap(masks.firstOrNull())
            return
        }
        if (conflated.offer(masks)) drainMerges()
    }

    /** Vide les fusions retenues : une seule boucle à la fois (voir [ConflatedJobs]). */
    private fun drainMerges() {
        mergeWorker.execute {
            do {
                var masks = conflated.take()
                while (masks != null) {
                    val fused = merge(masks)
                    // Fusion périmée (une plus fraîche attend) : jetée, la suivante suit.
                    maskView.post { if (!conflated.hasPending()) maskView.setImageBitmap(fused) }
                    masks = conflated.take()
                }
            } while (conflated.release())
        }
    }

    /**
     * Fusionne les masques colorisés dans le tampon caché ; `null` = rien à afficher.
     * Un seul fil l'appelle (le travailleur) : jamais deux fusions à la fois, et le
     * front renvoyé s'affiche pendant que le prochain se peint sur l'autre tampon.
     */
    private fun merge(masks: List<Bitmap>): Bitmap? {
        val first = masks.firstOrNull() ?: return null
        if (masks.size == 1) return first
        mergeFront = 1 - mergeFront
        var buffer = mergeBuffers[mergeFront]
        if (buffer == null || buffer.width != first.width || buffer.height != first.height) {
            buffer = Bitmap.createBitmap(first.width, first.height, Bitmap.Config.ARGB_8888)
            mergeBuffers[mergeFront] = buffer
        }
        buffer.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(buffer)
        masks.forEach { mask -> canvas.drawBitmap(mask, 0f, 0f, mergePaint) }
        return buffer
    }
}

/**
 * Conflation « dernier gagne » pour un travail mono-fil (motif `GlassesVideoLink.push`) :
 * [offer] retient la valeur et dit s'il faut démarrer le travailleur (`true` = aucun en
 * vol) ; [take] prend la dernière retenue (`null` = rien à faire) ; [release] libère le
 * travailleur et dit s'il doit reprendre (`true` = une valeur est arrivée pendant qu'il
 * finissait — sans ça, l'offre croirait un travailleur en vol et la valeur dormirait).
 * Pur Kotlin, testé en JVM.
 */
internal class ConflatedJobs<T> {
    private val pending = AtomicReference<T?>(null)
    private val inFlight = AtomicBoolean(false)

    /** Retient [value] (écrase la précédente) ; `true` = démarrer le travailleur. */
    fun offer(value: T): Boolean {
        pending.set(value)
        return inFlight.compareAndSet(false, true)
    }

    /** Dernière valeur retenue, `null` si aucune. */
    fun take(): T? = pending.getAndSet(null)

    /** Vrai si une valeur attend (une fusion affichée est alors périmée). */
    fun hasPending(): Boolean = pending.get() != null

    /**
     * Libère le travailleur ; `true` = reprendre aussitôt (une valeur attend et aucun
     * autre travailleur ne l'a prise). Le `compareAndSet` départage le travailleur qui
     * finit d'une offre concurrente : un seul des deux reprend le travail.
     */
    fun release(): Boolean {
        inFlight.set(false)
        return pending.get() != null && inFlight.compareAndSet(false, true)
    }
}
