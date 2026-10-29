// Contrat du relais de suivi iSee : **un état par identifiant de flux**.
// Même corps et même réponse que l'ancien relais du site, pour que la page
// caregiver embarquée dans l'APK n'ait rien à changer.

export const FRESH_MS = 12_000;
export const MAX_OBJECTS = 8;
export const MAX_TEXT = 2_000;
export const MAX_KEY = 64;

/** Frame JPEG base64 de la caméra iSee (320 px ≈ 24 k caractères). */
export const MAX_PHOTO = 120_000;

/** Nom de destination affiché au proche. */
export const MAX_DESTINATION = 80;

/** Repères reconnus gardés au plus par rapport. */
export const MAX_PICTOS = 4;

/** PNG base64 d'un repère : dessin ≈ 3-11 k caractères. */
export const MAX_PICTO_PNG = 30_000;

/** Nom d'un repère affiché au proche. */
export const MAX_PICTO_LABEL = 40;

/** Identifiant de flux : un par proche suivi, glissé dans `?share=`. */
export function validId(id) {
  return typeof id === 'string' && /^[a-z0-9-]{1,32}$/.test(id);
}

function num(v, min, max) {
  return typeof v === 'number' && Number.isFinite(v) && v >= min && v <= max;
}

/** Caractères d'un base64 (JPEG ou PNG) : le préfixe `data:` reste côté page. */
const BASE64 = /^[A-Za-z0-9+/]+={0,2}$/;

/** Phrase courante ; les anciennes versions de l'app ne l'envoient pas. */
function sceneOf(scene) {
  if (!scene || typeof scene.text !== 'string') return null;
  return {
    text: scene.text.slice(0, MAX_TEXT),
    fromUser: scene.fromUser === true,
    speaking: scene.speaking === true,
  };
}

/**
 * Image courante : `{jpeg, atMs}`. `null` = absente (ancien client, ou envoi du
 * service de suivi en arrière-plan, caméra fermée), `false` = présenté mais
 * illisible (le relais répond alors 400). Son `atMs` ne vieillit pas quand
 * l'image manque : la page cache une frame trop ancienne au lieu de la faire
 * passer pour du direct.
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
 * Trajet guidé : `{destination, totalM, remainingM, remainingS, progress}`.
 * `null` = aucun guidage en cours, `false` = trajet illisible (400).
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
 * Repères reconnus : `{atMs, items:[{label, png}]}`. `null` = aucun (paquet
 * sans détection, liste vide, ou ancien client), `false` = présenté mais
 * illisible (400). Suit le paquet comme le trajet : pas de conservation
 * d'un envoi à l'autre.
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
 * Valide un corps `POST /api/share`.
 *
 * `SHARE_WRITE_KEY` (secret du relais) est **facultatif** et sert de verrou global :
 * sans lui, la clé appartient au **flux** (liée au premier envoi, voir l'écriture).
 * Retourne `{ok:true, id, key, doc(now)}` ou `{ok:false, code, error}`.
 */
export function readShare(body, globalKey = '') {
  if (!body || typeof body !== 'object') return fail(400, 'corps JSON attendu');
  if (!validId(body.shareId)) return fail(400, 'shareId invalide');
  const key = typeof body.writeKey === 'string' ? body.writeKey : '';
  if (key.length > MAX_KEY) return fail(400, 'writeKey invalide');
  if (globalKey && key !== globalKey) return fail(403, 'clé invalide');
  // La position est **optionnelle** : un téléphone sans GPS (intérieur, démarrage,
  // permission refusée) publie quand même sa caméra, sa phrase et ses repères.
  // Sans ça, la page caregiver restait OFFLINE alors que la caméra tournait.
  // Une position présentée reste validée comme avant — seule son absence passe.
  const f = body.fix;
  if (f !== undefined && f !== null) {
    if (typeof f !== 'object') return fail(400, 'fix invalide');
    if (!num(f.lat, -90, 90) || !num(f.lon, -180, 180) || !num(f.accM, 0, 500)) {
      return fail(400, 'fix invalide');
    }
  }
  const objects = objectsOf(body.objects);
  if (objects === null) return fail(400, 'objet invalide');
  const scene = sceneOf(body.scene);
  const photo = photoOf(body.photo, Date.now());
  if (photo === false) return fail(400, 'photo invalide');
  const trip = tripOf(body.trip);
  if (trip === false) return fail(400, 'trip invalide');
  const pictos = pictosOf(body.pictos, Date.now());
  if (pictos === false) return fail(400, 'pictos invalides');
  return {
    ok: true,
    id: body.shareId,
    key,
    photo,
    doc: (now) => ({ fix: f == null ? null : fixOf(f, now), objects, scene, photo, trip, pictos }),
  };
}

/** État d'un flux encore jamais publié. */
export function emptyState() {
  return { ok: true, live: false };
}

/** Réponse `GET /api/state` ; `live` ne dure que [FRESH_MS]. */
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
