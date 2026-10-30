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

    /** Tampon de fusion réutilisé : recréé seulement quand la taille de frame change. */
    private var mergeBuffer: Bitmap? = null

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
     * le téléphone : photo après vidéo, puis vidéo après photo).
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(::attachPreview)
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
        mergeWorker.execute {
            val fused = merge(masks)
            maskView.post { maskView.setImageBitmap(fused) }
        }
    }

    /**
     * Fusionne les masques colorisés dans le tampon réutilisé ; `null` = rien à afficher.
     * Un seul fil l'appelle : jamais deux fusions à la fois sur le tampon.
     */
    private fun merge(masks: List<Bitmap>): Bitmap? {
        val first = masks.firstOrNull() ?: return null
        if (masks.size == 1) return first
        var buffer = mergeBuffer
        if (buffer == null || buffer.width != first.width || buffer.height != first.height) {
            buffer = Bitmap.createBitmap(first.width, first.height, Bitmap.Config.ARGB_8888)
            mergeBuffer = buffer
        }
        buffer.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(buffer)
        masks.forEach { mask -> canvas.drawBitmap(mask, 0f, 0f, mergePaint) }
        return buffer
    }
}
