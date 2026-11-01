package dev.sam3vision.app.sim

/**
 * **Debug uniquement** — une position simulée déjà validée, prête à devenir un
 * `android.location.Location`. Aucune classe Android ici : la lecture des extras d'un
 * broadcast se teste donc en JVM, sans appareil ni émulateur.
 *
 * [bearingDeg] et [speedMps] valent `null` quand l'hôte ne les a pas fournis : la position
 * est alors poussée sans cap ni vitesse, plutôt qu'avec un 0 qui ferait croire à un arrêt.
 * [atMs] à `0` veut dire « maintenant » : l'appelant met l'heure du téléphone.
 */
data class SimFix(
    val lat: Double,
    val lon: Double,
    val accM: Float,
    val bearingDeg: Float? = null,
    val speedMps: Float? = null,
    val atMs: Long = 0L,
) {
    companion object {
        /**
         * Précision par défaut (1σ, mètres) : 8 m est la précision typique d'un GPS de
         * téléphone à ciel ouvert, et reste sous `NavTuning.maxAccM` (25 m) — le fix
         * traverse donc le guidage au lieu d'être jeté.
         */
        const val DEFAULT_ACC_M = 8.0

        /** Plancher admis : `NavFix.isUsable()` exige `accM > 0`, on ne joue pas avec le zéro. */
        const val MIN_ACC_M = 1.0

        /** Plafond admis : au-delà, `NavTuning.maxAccM` (25 m) rejette le fix sans rien dire. */
        const val MAX_ACC_M = 25.0
    }
}

/**
 * Résultat de lecture d'un broadcast : le fix retenu, ou la raison du refus. La raison part
 * en logcat (`iSeeSim`) au lieu d'un rejet muet — c'est précisément ce que `adb emu geo fix`
 * ne sait pas dire.
 */
sealed interface SimParse {
    data class Ok(val fix: SimFix) : SimParse
    data class Rejected(val reason: String) : SimParse
}

/**
 * Lit les extras `lat`, `lon`, `acc`, `bearing`, `speed`, `ms` d'un broadcast de simulation.
 *
 * Bornes : latitude et longitude dans leur domaine, `acc` dans [SimFix.MIN_ACC_M]..
 * [SimFix.MAX_ACC_M] (`acc` absent ⇒ [SimFix.DEFAULT_ACC_M] ; 0 comme 50 seraient rejetés
 * plus loin, par `NavFix.isUsable()` et `NavTuning.maxAccM`, donc on refuse ici, à la source).
 * Un extra présent mais illisible ou hors bornes n'est jamais « corrigé » en silence : le
 * fix entier est rejeté avec sa raison.
 *
 * @param extras extras de l'intent, `null` si le broadcast n'en portait aucun.
 */
fun parseSimFix(extras: Map<String, String?>?): SimParse {
    if (extras == null) return SimParse.Rejected("aucun extra (lat/lon attendus)")
    return try {
        SimParse.Ok(readFix(extras))
    } catch (invalid: IllegalArgumentException) {
        SimParse.Rejected(invalid.message ?: "extras invalides")
    }
}

/** Construit le fix validé ; lève [IllegalArgumentException] avec la raison précise. */
private fun readFix(extras: Map<String, String?>): SimFix {
    val lat = extras.required("lat")
    val lon = extras.required("lon")
    require(lat in -90.0..90.0) { "lat hors [-90, 90] : $lat" }
    require(lon in -180.0..180.0) { "lon hors [-180, 180] : $lon" }
    val acc = extras.optional("acc") ?: SimFix.DEFAULT_ACC_M
    require(acc in SimFix.MIN_ACC_M..SimFix.MAX_ACC_M) {
        "acc hors [${SimFix.MIN_ACC_M}, ${SimFix.MAX_ACC_M}] : $acc"
    }
    val speed = extras.optional("speed")
    require(speed == null || speed >= 0.0) { "speed négative : $speed" }
    val ms = extras.optional("ms")
    require(ms == null || ms >= 0.0) { "ms négatif : $ms" }
    return SimFix(
        lat = lat,
        lon = lon,
        accM = acc.toFloat(),
        bearingDeg = extras.optional("bearing")?.let(::normalizeBearing),
        speedMps = speed?.toFloat(),
        atMs = ms?.toLong() ?: 0L,
    )
}

/** Cap ramené dans `[0, 360)` : `-10` devient `350`, comme le fait un compas. */
private fun normalizeBearing(deg: Double): Float {
    val wrapped = deg % 360.0
    return (if (wrapped < 0.0) wrapped + 360.0 else wrapped).toFloat()
}

/** Extra obligatoire, numérique et fini ; absent, vide ou illisible ⇒ refus nommé. */
private fun Map<String, String?>.required(key: String): Double {
    val raw = this[key]?.trim().orEmpty()
    require(raw.isNotEmpty()) { "$key absent" }
    val parsed = raw.toDoubleOrNull()
    require(parsed != null && parsed.isFinite()) { "$key illisible : $raw" }
    return parsed
}

/** Extra optionnel : absent ou vide ⇒ `null`, mais illisible ⇒ refus nommé. */
private fun Map<String, String?>.optional(key: String): Double? {
    val raw = this[key]?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val parsed = raw.toDoubleOrNull()
    require(parsed != null && parsed.isFinite()) { "$key illisible : $raw" }
    return parsed
}
