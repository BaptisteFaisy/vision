package dev.sam3vision.core.settings

/**
 * Région de la GPU SAM 3.1 interrogée par l'app : « mode US » = carte aux États-Unis.
 *
 * Ce réglage ne fait qu'**élire** l'endpoint appelé : il n'allume aucune carte. La règle
 * Modal reste « une seule GPU à la fois », toutes régions confondues — l'app US doit être
 * déployée et réveillée à la demande (`~/bin/sam3-gpu-us on --reason …`).
 * Bail GPU (la carte dort, la première frame réveille) : [GPU_LEASE_LINE].
 */
enum class SamRegion(
    /** Libellé des Réglages : tient sur une ligne, en pleine largeur. */
    val label: String,
    /** Code court (« EU », « US ») pour les pastilles et les journaux. */
    val code: String,
    /** Endpoint de l'app déployée dans cette région (surchargé par `local.properties`). */
    val presetBaseUrl: String,
) {
    Eu(
        label = "GPU in Europe (eu)",
        code = "EU",
        presetBaseUrl = "https://gaiamelie--sam3-eu-a100-sam3live-web.eu-west.modal.run",
    ),
    Us(
        label = "GPU in the United States (us)",
        code = "US",
        presetBaseUrl = "https://gaiamelie--sam3-us-a100-sam3live-web.us-east.modal.run",
    );

    companion object {
        /**
         * Bail GPU, une ligne à afficher sous le choix de région : la carte dort
         * quand personne ne s'en sert (extinction 60 s après la dernière frame,
         * `POST /release`), la première frame la réveille — à froid 25-38 s, sur
         * restauration snapshot 2-7 s. Seule la chaîne stable vit ici (+ sa doc) ;
         * l'affichage appartient à l'écran région des Réglages (autre agent).
         */
        const val GPU_LEASE_LINE =
            "GPU sleeps when idle - first frame wakes it (cold 25-38 s, warm 2-7 s)."

        /** Nom persisté (`sam_region`) → région ; valeur inconnue ou absente ⇒ [Eu]. */
        fun fromName(name: String?): SamRegion =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Eu

        /**
         * Presets retirés (ancien workspace, avant gaiamelie) : une URL persistée qui n'est
         * que l'un d'eux n'a jamais été un choix manuel — reconnue ici, elle migre vers la
         * région courante au lieu d'appeler l'ancien workspace à vie.
         */
        private val RETIRED_PRESETS = setOf(
            "https://baptiste-faisy--sam3-eu-a100-sam3live-web.eu-west.modal.run",
            "https://baptiste-faisy--sam3-us-a100-sam3live-web.us-east.modal.run",
        )

        /**
         * Vrai si [url] n'est que le preset d'une région, actuel ou retiré : c'est une valeur
         * injectée par le build ou héritée d'une ancienne version, pas un choix manuel — elle
         * ne doit donc pas figer la région.
         */
        fun isPreset(url: String): Boolean {
            val clean = url.trim().trimEnd('/')
            if (clean.isEmpty()) return false
            return entries.any { it.presetBaseUrl.trimEnd('/') == clean } ||
                RETIRED_PRESETS.any { it.trimEnd('/') == clean }
        }

        /**
         * Vrai si [url] est une saisie manuelle qui ne suit aucun preset : elle ne migre
         * jamais d'elle-même (ni changement de région, ni nouveau workspace) et gagne sur
         * tout — une URL périmée ici appelle un backend mort alors qu'une carte tourne
         * ailleurs. Les Réglages la signalent avec un retour au preset en un tap.
         */
        fun isStaleManualUrl(url: String): Boolean {
            val clean = url.trim()
            return clean.isNotEmpty() && !isPreset(clean)
        }

        /**
         * Ce que le diagnostic nomme pour [url] : le code région (« EU », « US ») quand
         * c'est un preset actuel, sinon l'hôte nu (une URL manuelle se reconnaît à son
         * hôte, pas à un code). Vide quand il n'y a rien à nommer.
         */
        fun describeEndpoint(url: String): String {
            val clean = url.trim().trimEnd('/')
            if (clean.isEmpty()) return ""
            entries.firstOrNull { it.presetBaseUrl.trimEnd('/') == clean }?.let { return it.code }
            return clean.substringAfter("://").substringBefore("/").trim()
        }
    }
}

/**
 * Endpoint SAM réellement appelé, dans cet ordre : saisie manuelle (hors preset) →
 * URL injectée au build pour cette région (hors preset elle aussi : un preset cuit
 * au build migre vers le preset vivant) → preset de la région choisie.
 */
fun Settings.resolveSamEndpoint(buildEu: String = "", buildUs: String = ""): String {
    val manual = samBaseUrl.trim()
    if (manual.isNotEmpty() && !SamRegion.isPreset(manual)) return manual
    val injected = (if (samRegion == SamRegion.Us) buildUs else buildEu).trim()
    // Un preset cuit au build (sam.base.url périmé, ex. baptiste-faisy) n'est pas
    // un choix manuel : il migre vers le preset vivant de la région, comme les
    // presets hérités des Réglages (404 « invalid function call » vu le 04/11).
    if (injected.isNotEmpty() && !SamRegion.isPreset(injected)) return injected
    return samRegion.presetBaseUrl
}
