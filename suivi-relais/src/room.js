// Realtime room of a stream: the phone **pushes** frames, caregivers
// **watch**. Nothing is stored here — D1 keeps the slow state (position,
// sentence, trip), the room only relays bytes. A picture lingering in base
// would be refused by the page anyway (freshness), so keeping it would
// only pay for writes.

/** Roles accepted on `/ws`. */
export const ROLES = ['push', 'watch'];

/** A frame past 512 KB is not a 480 px frame: refuse it. */
export const MAX_FRAME_BYTES = 512 * 1024;

/** Role carried by the URL (`?role=watch`), `null` when missing or unknown. */
export function roleOf(url) {
  const role = url.searchParams.get('role');
  return ROLES.includes(role) ? role : null;
}

/** Stream write key, as on `/api/share` (bound to the stream on first send). */
export function keyOf(url) {
  const key = url.searchParams.get('key');
  return typeof key === 'string' ? key : '';
}

/** Stream id carried by the URL; the worker already validated it. */
export function shareOf(url) {
  return url.searchParams.get('share') || '';
}

/**
 * What the phone gets when the audience changes: it only emits **while
 * watched**. Without this, the loved one's camera would push frames into the
 * void on every walk, off their mobile plan and battery.
 */
export function audienceNotice(count) {
  return JSON.stringify({ type: 'audience', watch: count });
}

/**
 * What caregivers get when the loved one's camera turns on or off: without
 * this notice, a connected page would keep the last picture up to 8 s after
 * the drop (slow photo still "fresh") while the camera is already off.
 * Emitted by the room (phone leaving) or forwarded from it (closed camera,
 * pipe still open).
 */
export function cameraNotice(on) {
  return JSON.stringify({ type: 'camera', on: on === true });
}

/**
 * Camera state carried by a text message, `null` when it is not one. Only
 * a strict boolean counts: a binary frame decoded as text must never turn
 * off every caregiver's picture.
 */
export function cameraOnOf(text) {
  if (typeof text !== 'string') return null;
  let parsed = null;
  try {
    parsed = JSON.parse(text);
  } catch {
    return null;
  }
  if (!parsed || parsed.type !== 'camera') return null;
  if (parsed.on === true) return true;
  if (parsed.on === false) return false;
  return null;
}

/**
 * A frame goes to watchers, **never** back to its sender: the phone has
 * nothing to send itself, and a loop would cost data for nothing.
 */
export function frameTargets(sockets, sender) {
  return sockets.filter((socket) => socket !== sender);
}

/** Relayable frame: non-empty and bounded. */
export function frameOk(bytes) {
  return Number.isFinite(bytes) && bytes > 0 && bytes <= MAX_FRAME_BYTES;
}
