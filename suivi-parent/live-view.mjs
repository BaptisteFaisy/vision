// Picture, landmarks and trip of the shared phone: the page never invents data.
export const PHOTO_FRESH_MS = 8_000;

/** Showable recognized landmarks: drawings of a detection running **right now**. */
export const PICTO_FRESH_MS = 8_000;

/**
 * Showable picture: one from a camera running **right now**.
 *
 * The relay keeps the last frame up to two minutes after the phone drops:
 * that kept picture is never shown, or the page would show a camera the
 * loved one already turned off. So a `live` stream **and** a frame under
 * [PHOTO_FRESH_MS] are both required; everything else yields to text.
 * Once the room said the camera is off (`cameraOff`), even a still "fresh"
 * frame no longer shows: the notice beats the age.
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
 * Showable landmarks: a `live` stream **and** a detection under
 * [PICTO_FRESH_MS] (same rule as the frame: a drawing kept after a drop
 * proves nothing). Each item renders `{src, label}`.
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

/** Guided trip in progress, `null` outside guidance or when the stream is dead. */
export function tripOf(live, status) {
  const trip = live && live.trip;
  if (!trip || status === 'offline') return null;
  if (typeof trip.destination !== 'string' || !trip.destination.trim()) return null;
  return trip;
}

/**
 * Grace during which the last painted frame stays frozen when the live
 * stream drops, instead of being cleared or swapped at once: a micro-gap
 * (network jitter, pipe reconnect, camera notice on an encoder stall)
 * turns invisible, a real stop clears after the grace.
 */
export const DIRECT_FREEZE_MS = 2_500;

/**
 * True when the frame painted at `shownAt` may stay on screen: painted
 * recently (under `graceMs`), so a live gap rather than a stop.
 */
export function frozenFrameOf(shownAt, now = Date.now(), graceMs = DIRECT_FREEZE_MS) {
  return Number.isFinite(shownAt) && shownAt > 0 && now - shownAt < graceMs;
}

/**
 * Does the painted frame survive this render? Yes during the grace after a
 * live micro-drop: always without a view; with a fresh slow photo, only
 * when the live stream was showing — otherwise the normal refresh
 * (every 2 s) would be blocked.
 */
export function freezeFrameOf(frameSrc, shownAt, showingDirect, view, now = Date.now()) {
  if (!frameSrc || !frozenFrameOf(shownAt, now)) return false;
  return !view || showingDirect;
}

/** Bar fill, as a percentage clamped to 0..100. */
export function tripPercent(trip) {
  const progress = Number(trip && trip.progress);
  if (!Number.isFinite(progress)) return 0;
  return Math.max(0, Math.min(100, progress * 100));
}

/** "300 m to go · 4 min": English wording of the caregiver page. */
export function tripRemaining(trip) {
  const meters = Number(trip && trip.remainingM);
  if (!Number.isFinite(meters) || meters < 0) return '';
  const distance = meters < 1000
    ? Math.round(meters) + ' m'
    : (Math.round(meters / 100) / 10).toFixed(1).replace(/\.0$/, '') + ' km';
  const seconds = Number(trip && trip.remainingS);
  if (!Number.isFinite(seconds) || seconds <= 0) return distance + ' to go';
  // Same rounding as `formatDurationFr` (core): truncated minutes, half a minute free.
  const minutes = Math.floor((seconds + 30) / 60);
  const duration = seconds < 60 ? 'under a minute'
    : minutes < 60 ? minutes + ' min'
      : Math.floor(minutes / 60) + 'h ' + String(minutes % 60).padStart(2, '0');
  return distance + ' to go · ' + duration;
}
