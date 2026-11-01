// The camera live stream: frames arrive over the room WebSocket
// (`/ws`, one Durable Object per stream). Nothing is stored — the D1 relay keeps
// the position and sentences, the picture passes through here and only lives
// as long as one frame on screen.

/**
 * A frame older than this is no longer live: fall back to the slow state.
 * At 10 fps, 600 ms cover 6 frames — enough for jitter, strict enough to
 * notice a rate drop (the watchdog hysteresis avoids flapping).
 */
export const STREAM_FRESH_MS = 600;

/** Reconnect: short at first (the caregiver is watching), capped afterwards. */
const RETRY_MS = [500, 1_000, 2_000, 4_000, 8_000];

/**
 * Stream URL, **same origin as the page** (`wss` over https): stream
 * identification matches `/api/state`, one id is enough.
 */
export function streamUrl(href) {
  const page = new URL(href);
  const share = page.searchParams.get('share') || '';
  const scheme = page.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${scheme}//${page.host}/ws?share=${encodeURIComponent(share)}&role=watch`;
}

/**
 * Camera state carried by a room text message, `null` when it is not one.
 * Same contract as the relay-side `room.js`: both sides read
 * `{"type":"camera","on":…}` with a strict boolean, the rest is ignored.
 */
export function cameraOnOf(data) {
  if (typeof data !== 'string') return null;
  let parsed = null;
  try {
    parsed = JSON.parse(data);
  } catch {
    return null;
  }
  if (!parsed || parsed.type !== 'camera') return null;
  if (parsed.on === true) return true;
  if (parsed.on === false) return false;
  return null;
}

/**
 * Blob URL of a received frame, `null` when it is not a usable picture. The
 * socket delivers `Blob`s (`binaryType = 'blob'`): they pass through as-is, with
 * no `new Blob([arraybuffer])` copy as before; an `ArrayBuffer` is still accepted.
 */
export function frameSrcOf(data, create = (blob) => URL.createObjectURL(blob)) {
  if (typeof Blob !== 'undefined' && data instanceof Blob) {
    return data.size === 0 ? null : create(data);
  }
  if (!data || typeof data.byteLength !== 'number' || data.byteLength === 0) return null;
  return create(new Blob([data], { type: 'image/jpeg' }));
}

/**
 * Opens (and reopens) the stream. `onFrame(src)` gets one blob URL per frame, and
 * only the last two survive (the shown one + the decoding one) — the one before
 * is revoked at the next frame, instead of a dozen pending ones;
 * `onState('live' | 'idle')` notifies the page. `onFrame` is never called
 * with `null`. `onCamera(true | false)` fires when the loved one's camera
 * turns on or off: the page clears the picture at once, without waiting
 * for the last frame to expire.
 *
 * `open` is injectable so the logic tests without a browser.
 */
export function watchStream({
  href,
  onFrame,
  onState = () => {},
  onCamera = () => {},
  open = (url) => new WebSocket(url),
  create = (blob) => URL.createObjectURL(blob),
  revoke = (src) => URL.revokeObjectURL(src),
  retry = RETRY_MS,
  timer = { set: (fn, ms) => setTimeout(fn, ms), clear: (id) => clearTimeout(id) },
} = {}) {
  let socket = null;
  let previous = null;
  let older = null;
  let attempt = 0;
  let timerId = null;
  let closed = false;

  const release = () => {
    if (previous) { revoke(previous); previous = null; }
    if (older) { revoke(older); older = null; }
    onState('idle');
  };

  const connect = () => {
    if (closed) return;
    const url = streamUrl(href);
    if (!url.includes('share=') || url.endsWith('share=&role=watch')) return;
    socket = open(url);
    socket.binaryType = 'blob';
    socket.onopen = () => { attempt = 0; onState('live'); };
    socket.onmessage = (event) => {
      if (typeof event.data === 'string') {
        const on = cameraOnOf(event.data);
        if (on !== null) onCamera(on);
        return;
      }
      const src = frameSrcOf(event.data, create);
      if (!src) return;
      if (older) revoke(older);
      older = previous;
      previous = src;
      onFrame(src);
    };
    socket.onclose = () => {
      socket = null;
      release();
      if (closed) return;
      const wait = retry[Math.min(attempt, retry.length - 1)];
      attempt += 1;
      timerId = timer.set(connect, wait);
    };
    socket.onerror = () => { try { socket.close(); } catch { /* already closed */ } };
  };

  connect();
  return {
    close() {
      closed = true;
      if (timerId) timer.clear(timerId);
      try { socket && socket.close(); } catch { /* already closed */ }
      release();
    },
  };
}
