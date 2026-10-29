// Image et trajet du téléphone partagé : aucune donnée n'est inventée côté page.
export const PHOTO_FRESH_MS = 8_000;

/** Image affichable : data URL du JPEG reçu, et fraîcheur (frame encore du direct). */
export function frameOf(live, status, now = Date.now()) {
  const photo = live && live.photo;
  if (!photo || typeof photo.jpeg !== 'string' || !photo.jpeg) return null;
  if (status === 'offline') return null;
  const at = Number(photo.atMs);
  return {
    src: 'data:image/jpeg;base64,' + photo.jpeg,
    // Une frame n'est « du direct » que si le flux est vivant ET la frame récente.
    fresh: status === 'live' && Number.isFinite(at) && now - at <= PHOTO_FRESH_MS,
  };
}

/** Trajet guidé en cours, `null` hors guidage ou quand le flux est mort. */
export function tripOf(live, status) {
  const trip = live && live.trip;
  if (!trip || status === 'offline') return null;
  if (typeof trip.destination !== 'string' || !trip.destination.trim()) return null;
  return trip;
}

/** Remplissage de la barre, en pourcentage borné 0..100. */
export function tripPercent(trip) {
  const progress = Number(trip && trip.progress);
  if (!Number.isFinite(progress)) return 0;
  return Math.max(0, Math.min(100, progress * 100));
}

/** « Encore 300 m · 4 min » : même français que l'écran de guidage. */
export function tripRemaining(trip) {
  const meters = Number(trip && trip.remainingM);
  if (!Number.isFinite(meters) || meters < 0) return '';
  const distance = meters < 1000
    ? Math.round(meters) + ' m'
    : (Math.round(meters / 100) / 10).toFixed(1).replace('.', ',').replace(',0', '') + ' km';
  const seconds = Number(trip && trip.remainingS);
  if (!Number.isFinite(seconds) || seconds <= 0) return 'Encore ' + distance;
  // Même arrondi que `formatDurationFr` (core) : minutes tronquées, demi-minute offerte.
  const minutes = Math.floor((seconds + 30) / 60);
  const duration = seconds < 60 ? "moins d'une minute"
    : minutes < 60 ? minutes + ' min'
      : Math.floor(minutes / 60) + ' h ' + String(minutes % 60).padStart(2, '0');
  return 'Encore ' + distance + ' · ' + duration;
}
