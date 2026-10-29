// Logique pure du suivi : aucune position ni phrase ne vient du téléphone du caregiver.
export const LIVE_MS = 12_000;
export const LOST_MS = 120_000;
const METERS_PER_DEGREE = 111_320;
const RAD = Math.PI / 180;

export function shareIdOf(value) {
  try {
    const url = new URL(value);
    const id = url.searchParams.get('share');
    return /^https?:$/.test(url.protocol) && /^[a-z0-9-]{1,32}$/.test(id || '') ? id : null;
  } catch { return null; }
}

export function stateOf(live, now = Date.now()) {
  if (!live || !Number.isFinite(live.serverAt)) return 'offline';
  const age = Math.max(0, now - live.serverAt);
  return age < LIVE_MS ? 'live' : age < LOST_MS ? 'lost' : 'offline';
}

/**
 * Le relais répond `{ok:true, live:false}` **sans `serverAt`** quand l'identifiant
 * n'a jamais publié, et avec `serverAt` quand le flux existe mais s'est tu. Sans
 * cette distinction, un lien devenu faux (app réinstallée → nouvel identifiant)
 * restait « en attente du proche » indéfiniment. Un flux purgé (24 h) retombe
 * naturellement dans ce cas : c'est exact, il n'existe plus.
 */
export function neverPublished(snapshot) {
  return !!snapshot && snapshot.ok === true && snapshot.live === false
    && !Number.isFinite(snapshot.serverAt);
}

/** Message d'un lien qui ne désigne aucun flux : actionnable, jamais « en attente ». */
export const NO_STREAM_TEXT =
  'Aucun suivi pour ce lien : il a peut-être changé (app réinstallée). Ouvre iSee sur le téléphone de ton proche, puis redemande le lien.';

export function iconFor(label) {
  const name = String(label || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
  if (/personne|homme|femme|enfant|pieton|person|woman|child|pedestrian/.test(name)) return '🚶';
  if (/chien|chat|animal|dog|cat/.test(name)) return '🐕';
  if (/voiture|bus|camion|velo|moto|vehicule|car|truck|bike/.test(name)) return '🚗';
  if (/poteau|grille|barriere|chantier|pole|post|barrier|fence/.test(name)) return '🚧';
  if (/poubelle|bin|trash/.test(name)) return '🗑️';
  if (/porte|door/.test(name)) return '🚪';
  if (/feu|traffic light/.test(name)) return '🚦';
  return '📦';
}

// Un cap 0 pointe vers le nord : les latitudes augmentent, elles ne diminuent pas.
export function destPoint(object, from) {
  const angle = object.angleDeg * RAD;
  return {
    lat: from.lat + object.distM * Math.cos(angle) / METERS_PER_DEGREE,
    lon: from.lon + object.distM * Math.sin(angle) / (METERS_PER_DEGREE * Math.cos(from.lat * RAD)),
  };
}

export function objectsOf(live, status) {
  if (!live || status === 'offline') return [];
  return (live.objects || []).map((object, index) => ({
    ...object, id: `o${index}`, ico: iconFor(object.label), frozen: status === 'lost',
  }));
}

/** Ne pas transformer le bruit GPS, une consigne ou un saut de position en geste accompli. */
export function movementBetween(before, after) {
  if (!before || !after) return '';
  const elapsed = after.atMs - before.atMs;
  if (elapsed <= 0 || elapsed > 30_000 || before.indoor || after.indoor) return '';
  const north = (after.lat - before.lat) * METERS_PER_DEGREE;
  const east = (after.lon - before.lon) * METERS_PER_DEGREE * Math.cos(before.lat * RAD);
  const distance = Math.hypot(north, east);
  const accuracy = before.accM + after.accM;
  if (distance <= Math.max(3, accuracy) || distance / (elapsed / 1000) > 3) return '';
  const meters = Math.round(distance);
  if (!Number.isFinite(before.headingDeg)) return `S’est déplacé d’environ ${meters} m.`;
  const bearing = Math.atan2(east, north) / RAD;
  const relative = ((bearing - before.headingDeg + 540) % 360) - 180;
  if (Math.abs(relative) < 45) return `A avancé d’environ ${meters} m.`;
  if (Math.abs(relative) > 135) return `A reculé d’environ ${meters} m.`;
  return `S’est décalé vers la ${relative > 0 ? 'droite' : 'gauche'} d’environ ${meters} m.`;
}

export function obstacleText(objects) {
  const nearest = [...(objects || [])].sort((a, b) => a.distM - b.distM).slice(0, 2);
  if (!nearest.length) return 'Aucune détection partagée pour le moment.';
  return nearest.map(o => `${o.label} à ${o.distM.toLocaleString('fr-FR', { maximumFractionDigits: 1 })} m`).join(' ; ') + '.';
}

export function narrationOf(live, status, movement = '', hasLink = true, known = true) {
  if (!hasLink) return 'Ouvre le lien de suivi partagé par ton proche.';
  if (!known) return NO_STREAM_TEXT;
  if (status === 'lost') return 'Signal perdu. La dernière position et les objets restent figés.';
  if (status === 'offline') return 'En attente du téléphone de ton proche.';
  const text = live.scene?.text?.trim() || obstacleText(live.objects);
  return [text, movement].filter(Boolean).join(' ');
}
