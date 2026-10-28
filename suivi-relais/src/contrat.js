// Contrat du relais de suivi iSee : **un état par identifiant de flux**.
// Même corps et même réponse que l'ancien relais du site, pour que la page
// caregiver embarquée dans l'APK n'ait rien à changer.

export const FRESH_MS = 12_000;
export const MAX_OBJECTS = 8;
export const MAX_TEXT = 2_000;
export const MAX_KEY = 64;

/** Identifiant de flux : un par proche suivi, glissé dans `?share=`. */
export function validId(id) {
  return typeof id === 'string' && /^[a-z0-9-]{1,32}$/.test(id);
}

function num(v, min, max) {
  return typeof v === 'number' && Number.isFinite(v) && v >= min && v <= max;
}

/** Phrase courante ; les anciennes versions de l'app ne l'envoient pas. */
function sceneOf(scene) {
  if (!scene || typeof scene.text !== 'string') return null;
  return {
    text: scene.text.slice(0, MAX_TEXT),
    fromUser: scene.fromUser === true,
    speaking: scene.speaking === true,
  };
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
  const f = body.fix;
  if (!f || typeof f !== 'object') return fail(400, 'fix invalide');
  if (!num(f.lat, -90, 90) || !num(f.lon, -180, 180) || !num(f.accM, 0, 500)) {
    return fail(400, 'fix invalide');
  }
  const objects = objectsOf(body.objects);
  if (objects === null) return fail(400, 'objet invalide');
  const scene = sceneOf(body.scene);
  return {
    ok: true,
    id: body.shareId,
    key,
    doc: (now) => ({ fix: fixOf(f, now), objects, scene }),
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
    fix: row.fix,
    objects: row.objects || [],
    scene: row.scene || null,
    serverAt,
  };
}

function fail(code, error) {
  return { ok: false, code, error };
}
