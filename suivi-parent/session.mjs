// Pure tracking logic: no position or sentence ever comes from the caregiver's phone.
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
 * The relay answers `{ok:true, live:false}` **without `serverAt`** when the id
 * never published, and with `serverAt` when the stream exists but went quiet.
 * Without this distinction, a link gone stale (reinstalled app → new id)
 * stayed "waiting for the loved one" forever. A pruned stream (24 h) falls
 * naturally into this case: rightly so, it no longer exists.
 */
export function neverPublished(snapshot) {
  return !!snapshot && snapshot.ok === true && snapshot.live === false
    && !Number.isFinite(snapshot.serverAt);
}

/** Message for a link with no stream: actionable, never "waiting". */
export const NO_STREAM_TEXT =
  "No tracking for this link: it may have changed (app reinstalled). Open iSee on your loved one's phone, then ask for the link again.";

/** Never turn GPS noise, an instruction or a position jump into a completed move. */
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
  if (!Number.isFinite(before.headingDeg)) return `Moved about ${meters} m.`;
  const bearing = Math.atan2(east, north) / RAD;
  const relative = ((bearing - before.headingDeg + 540) % 360) - 180;
  if (Math.abs(relative) < 45) return `Moved forward about ${meters} m.`;
  if (Math.abs(relative) > 135) return `Moved back about ${meters} m.`;
  return `Shifted to the ${relative > 0 ? 'right' : 'left'} by about ${meters} m.`;
}

/**
 * Reference for the next move: the current fix when there is none yet, when a
 * move was just measured or when the anchor grew old — and `null` when the
 * position is unknown (packet without GPS: camera and sentence still pass,
 * without inventing movement).
 */
export function anchorAfter(anchor, movement, fix) {
  if (!anchor || !fix || movement) return fix || null;
  return fix.atMs - anchor.atMs > 30_000 ? fix : anchor;
}

export function obstacleText(objects) {
  const nearest = [...(objects || [])].sort((a, b) => a.distM - b.distM).slice(0, 2);
  if (!nearest.length) return 'No shared detections yet.';
  return nearest.map(o => `${o.label} at ${o.distM.toLocaleString('en-US', { maximumFractionDigits: 1 })} m`).join('; ') + '.';
}

export function narrationOf(live, status, movement = '', hasLink = true, known = true) {
  if (!hasLink) return 'Open the tracking link shared by your loved one.';
  if (!known) return NO_STREAM_TEXT;
  if (status === 'lost') return 'Signal lost. The last position and objects stay frozen.';
  if (status === 'offline') return "Waiting for your loved one's phone.";
  const text = live.scene?.text?.trim() || obstacleText(live.objects);
  return [text, movement].filter(Boolean).join(' ');
}
