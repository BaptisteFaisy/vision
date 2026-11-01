// iSee tracking relay contract: **one state per stream id**.
// Same body and answer as the site's old relay, so the caregiver page
// embedded in the APK has nothing to change.

export const FRESH_MS = 12_000;
export const MAX_OBJECTS = 8;
export const MAX_TEXT = 2_000;
export const MAX_KEY = 64;

/** Base64 JPEG frame of the iSee camera (320 px ≈ 24k chars). */
export const MAX_PHOTO = 120_000;

/** Destination name shown to the loved one. */
export const MAX_DESTINATION = 80;

/** Recognized landmarks kept at most per report. */
export const MAX_PICTOS = 4;

/** Base64 PNG of a landmark: drawing ≈ 3-11k chars. */
export const MAX_PICTO_PNG = 30_000;

/** Name of a landmark shown to the loved one. */
export const MAX_PICTO_LABEL = 40;

/** Stream id: one per tracked loved one, carried in `?share=`. */
export function validId(id) {
  return typeof id === 'string' && /^[a-z0-9-]{1,32}$/.test(id);
}

function num(v, min, max) {
  return typeof v === 'number' && Number.isFinite(v) && v >= min && v <= max;
}

/** Chars of a base64 (JPEG or PNG): the `data:` prefix stays page-side. */
const BASE64 = /^[A-Za-z0-9+/]+={0,2}$/;

/** Current sentence; older app versions never send it. */
function sceneOf(scene) {
  if (!scene || typeof scene.text !== 'string') return null;
  return {
    text: scene.text.slice(0, MAX_TEXT),
    fromUser: scene.fromUser === true,
    speaking: scene.speaking === true,
  };
}

/**
 * Current picture: `{jpeg, atMs}`. `null` = missing (old client, or send from
 * the background tracking service with a closed camera), `false` = present
 * but unreadable (the relay then answers 400). Its `atMs` never ages when
 * the picture is missing: the page hides too old a frame instead of passing
 * it off as live.
 */
function photoOf(photo, now) {
  if (photo === undefined || photo === null) return null;
  if (typeof photo !== 'object' || typeof photo.jpeg !== 'string') return false;
  const jpeg = photo.jpeg.trim();
  if (!jpeg || jpeg.length > MAX_PHOTO || !BASE64.test(jpeg)) return false;
  return {
    jpeg,
    atMs: num(photo.atMs, 0, 9e15) ? photo.atMs : now,
  };
}

/**
 * Guided trip: `{destination, totalM, remainingM, remainingS, progress}`.
 * `null` = no guidance in progress, `false` = unreadable trip (400).
 */
function tripOf(trip) {
  if (trip === undefined || trip === null) return null;
  if (typeof trip !== 'object') return false;
  const destination = typeof trip.destination === 'string' ? trip.destination.trim() : '';
  if (!destination) return false;
  if (!num(trip.totalM, 0, 5e6) || !num(trip.remainingM, 0, 5e6)) return false;
  if (!num(trip.remainingS, 0, 1e6)) return false;
  if (!num(trip.progress, 0, 1)) return false;
  return {
    destination: destination.slice(0, MAX_DESTINATION),
    totalM: trip.totalM,
    remainingM: trip.remainingM,
    remainingS: trip.remainingS,
    progress: trip.progress,
  };
}

/**
 * Recognized landmarks: `{atMs, items:[{label, png}]}`. `null` = none (packet
 * without detection, empty list, or old client), `false` = present but
 * unreadable (400). Follows the packet like the trip: nothing kept from
 * one send to the next.
 */
function pictosOf(pictos, now) {
  if (pictos === undefined || pictos === null) return null;
  if (typeof pictos !== 'object' || !Array.isArray(pictos.items)) return false;
  const kept = [];
  for (const item of pictos.items.slice(0, MAX_PICTOS)) {
    if (!item || typeof item !== 'object') return false;
    const label = typeof item.label === 'string' ? item.label.trim() : '';
    const png = typeof item.png === 'string' ? item.png : '';
    if (!label || !png || png.length > MAX_PICTO_PNG) return false;
    if (!BASE64.test(png)) return false;
    kept.push({ label: label.slice(0, MAX_PICTO_LABEL), png });
  }
  if (!kept.length) return null;
  return { atMs: num(pictos.atMs, 0, 9e15) ? pictos.atMs : now, items: kept };
}

function fixOf(f, now) {
  return {
    lat: f.lat,
    lon: f.lon,
    accM: f.accM,
    headingDeg: num(f.headingDeg, 0, 360) ? f.headingDeg : null,
    atMs: num(f.atMs, 0, 9e15) ? f.atMs : now,
    indoor: f.indoor === true,
  };
}

function objectsOf(list) {
  if (!Array.isArray(list)) return [];
  const kept = [];
  for (const o of list.slice(0, MAX_OBJECTS)) {
    if (typeof o.label !== 'string') return null;
    if (!num(o.distM, 0, 60) || !num(o.angleDeg, -180, 180)) return null;
    kept.push({ label: o.label.slice(0, 40), distM: o.distM, angleDeg: o.angleDeg });
  }
  return kept;
}

/**
 * Validates a `POST /api/share` body.
 *
 * `SHARE_WRITE_KEY` (the relay secret) is **optional** and acts as a global
 * lock: without it, the key belongs to the **stream** (bound on first send,
 * see the write). Returns `{ok:true, id, key, doc(now)}` or `{ok:false, code, error}`.
 */
export function readShare(body, globalKey = '') {
  if (!body || typeof body !== 'object') return fail(400, 'JSON body expected');
  if (!validId(body.shareId)) return fail(400, 'invalid shareId');
  const key = typeof body.writeKey === 'string' ? body.writeKey : '';
  if (key.length > MAX_KEY) return fail(400, 'invalid writeKey');
  if (globalKey && key !== globalKey) return fail(403, 'invalid key');
  // The position is **optional**: a phone without GPS (indoors, starting up,
  // refused permission) still publishes its camera, sentence and landmarks.
  // Without this, the caregiver page stayed OFFLINE while the camera ran.
  // A present position is still validated as before — only its absence passes.
  const f = body.fix;
  if (f !== undefined && f !== null) {
    if (typeof f !== 'object') return fail(400, 'invalid fix');
    if (!num(f.lat, -90, 90) || !num(f.lon, -180, 180) || !num(f.accM, 0, 500)) {
      return fail(400, 'invalid fix');
    }
  }
  const objects = objectsOf(body.objects);
  if (objects === null) return fail(400, 'invalid object');
  const scene = sceneOf(body.scene);
  const photo = photoOf(body.photo, Date.now());
  if (photo === false) return fail(400, 'invalid photo');
  const trip = tripOf(body.trip);
  if (trip === false) return fail(400, 'invalid trip');
  const pictos = pictosOf(body.pictos, Date.now());
  if (pictos === false) return fail(400, 'invalid pictos');
  return {
    ok: true,
    id: body.shareId,
    key,
    photo,
    doc: (now) => ({ fix: f == null ? null : fixOf(f, now), objects, scene, photo, trip, pictos }),
  };
}

/** State of a stream never published yet. */
export function emptyState() {
  return { ok: true, live: false };
}

/** `GET /api/state` answer; `live` only lasts [FRESH_MS]. */
export function stateOf(row, now) {
  const serverAt = row.serverAt;
  const ageMs = Math.max(0, now - serverAt);
  return {
    ok: true,
    live: ageMs < FRESH_MS,
    ageMs,
    fix: row.fix || null,
    objects: row.objects || [],
    scene: row.scene || null,
    photo: row.photo || null,
    trip: row.trip || null,
    pictos: row.pictos || null,
    serverAt,
  };
}

function fail(code, error) {
  return { ok: false, code, error };
}
