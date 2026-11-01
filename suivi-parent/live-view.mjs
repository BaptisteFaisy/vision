// Image, repères et trajet du téléphone partagé : aucune donnée n'est inventée côté page.
export const PHOTO_FRESH_MS = 8_000;

/** Repères reconnus affichables : dessins d'une détection qui tourne **maintenant**. */
export const PICTO_FRESH_MS = 8_000;

/**
 * Image affichable : celle d'une caméra qui tourne **maintenant**.
 *
 * Le relais garde la dernière frame jusqu'à deux minutes après la coupure du
 * téléphone : cette image conservée n'est jamais affichée, sinon la page montre
 * une caméra que le proche a déjà éteinte. Il faut donc un flux `live` **et** une
 * frame de moins de [PHOTO_FRESH_MS] ; tout le reste laisse la place au texte.
 * Quand la salle a dit que la caméra est éteinte (`cameraOff`), même une frame
 * encore « fraîche » ne s'affiche plus : l'avis vaut mieux que l'âge.
 */
export function frameOf(live, status, now = Date.now(), cameraOff = false) {
  if (cameraOff) return null;
  if (status !== 'live') return null;
  const photo = live && live.photo;
  if (!photo || typeof photo.jpeg !== 'string' || !photo.jpeg) return null;
  const at = Number(photo.atMs);
  if (!Number.isFinite(at) || now - at > PHOTO_FRESH_MS) return null;
  return { src: 'data:image/jpeg;base64,' + photo.jpeg };
}

/**
 * Repères affichables : flux `live` **et** détection de moins de
 * [PICTO_FRESH_MS] (même règle que la frame : un dessin conservé après
 * coupure ne prouve plus rien). Chaque item rend `{src, label}`.
 */
export function pictosOf(live, status, now = Date.now()) {
  if (status !== 'live') return null;
  const pictos = live && live.pictos;
  if (!pictos || !Array.isArray(pictos.items) || !pictos.items.length) return null;
  const at = Number(pictos.atMs);
  if (!Number.isFinite(at) || now - at > PICTO_FRESH_MS) return null;
  const kept = [];
  for (const item of pictos.items) {
    if (!item || typeof item.label !== 'string' || !item.label.trim()) continue;
    if (typeof item.png !== 'string' || !item.png) continue;
    kept.push({ src: 'data:image/png;base64,' + item.png, label: item.label.trim() });
  }
  return kept.length ? kept : null;
}

/** Trajet guidé en cours, `null` hors guidage ou quand le flux est mort. */
export function tripOf(live, status) {
  const trip = live && live.trip;
  if (!trip || status === 'offline') return null;
  if (typeof trip.destination !== 'string' || !trip.destination.trim()) return null;
  return trip;
}

/**
 * Grâce pendant laquelle la dernière image posée reste figée à la perte du
 * direct, au lieu d'être effacée ou permutée aussitôt : un micro-trou
 * (jitter réseau, reconnect du tuyau, avis caméra sur un stall encodeur)
 * devient invisible, un vrai arrêt efface après la grâce.
 */
export const DIRECT_FREEZE_MS = 2_500;

/**
 * Vrai si l'image posée à `shownAt` peut encore rester affichée : posée
 * récemment (moins de `graceMs`), donc un trou du direct plutôt qu'un arrêt.
 */
export function frozenFrameOf(shownAt, now = Date.now(), graceMs = DIRECT_FREEZE_MS) {
  return Number.isFinite(shownAt) && shownAt > 0 && now - shownAt < graceMs;
}

/**
 * L'image posée survit-elle à ce rendu ? Oui pendant la grâce qui suit une
 * micro-coupure du direct : sans vue, toujours ; avec une photo lente
 * fraîche, seulement si le direct s'affichait — sinon le rafraîchissement
 * normal (toutes les 2 s) serait bloqué.
 */
export function freezeFrameOf(frameSrc, shownAt, showingDirect, view, now = Date.now()) {
  if (!frameSrc || !frozenFrameOf(shownAt, now)) return false;
  return !view || showingDirect;
}

/** Remplissage de la barre, en pourcentage borné 0..100. */
export function tripPercent(trip) {
  const progress = Number(trip && trip.progress);
  if (!Number.isFinite(progress)) return 0;
  return Math.max(0, Math.min(100, progress * 100));
}

/** « 300 m to go · 4 min » : libellé anglais de la page aidant. */
export function tripRemaining(trip) {
  const meters = Number(trip && trip.remainingM);
  if (!Number.isFinite(meters) || meters < 0) return '';
  const distance = meters < 1000
    ? Math.round(meters) + ' m'
    : (Math.round(meters / 100) / 10).toFixed(1).replace(/\.0$/, '') + ' km';
  const seconds = Number(trip && trip.remainingS);
  if (!Number.isFinite(seconds) || seconds <= 0) return distance + ' to go';
  // Même arrondi que `formatDurationFr` (core) : minutes tronquées, demi-minute offerte.
  const minutes = Math.floor((seconds + 30) / 60);
  const duration = seconds < 60 ? 'under a minute'
    : minutes < 60 ? minutes + ' min'
      : Math.floor(minutes / 60) + 'h ' + String(minutes % 60).padStart(2, '0');
  return distance + ' to go · ' + duration;
}
