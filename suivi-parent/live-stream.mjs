// Le direct de la caméra : les frames arrivent par le WebSocket de la salle
// (`/ws`, un Durable Object par flux). Rien n'est stocké — le relais D1 garde la
// position et les phrases, l'image passe par ici et n'existe que le temps d'une
// image à l'écran.

/**
 * Une frame plus vieille que ça n'est plus du direct : on revient à l'état lent.
 * À 10 im/s, 600 ms couvrent 6 frames — assez pour le jitter, assez strict pour
 * voir une chute de cadence (l'hystérésis du chien de garde évite le flapping).
 */
export const STREAM_FRESH_MS = 600;

/** Reconnexion : courte au début (l'aidant regarde), plafonnée ensuite. */
const RETRY_MS = [500, 1_000, 2_000, 4_000, 8_000];

/**
 * URL du flux, **même origine que la page** (`wss` en https) : l'identification
 * du flux est la même que celle de `/api/state`, un identifiant suffit.
 */
export function streamUrl(href) {
  const page = new URL(href);
  const share = page.searchParams.get('share') || '';
  const scheme = page.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${scheme}//${page.host}/ws?share=${encodeURIComponent(share)}&role=watch`;
}

/**
 * État caméra porté par un message texte de la salle, `null` si ce n'en est
 * pas un. Même contrat que `room.js` côté relais : les deux côtés lisent
 * `{"type":"camera","on":…}` avec un booléen strict, le reste est ignoré.
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
 * Blob URL d'une frame reçue, `null` si ce n'est pas une image exploitable. Le
 * socket livre des `Blob` (`binaryType = 'blob'`) : ils passent tels quels, sans
 * la copie `new Blob([arraybuffer])` d'avant ; un `ArrayBuffer` reste accepté.
 */
export function frameSrcOf(data, create = (blob) => URL.createObjectURL(blob)) {
  if (typeof Blob !== 'undefined' && data instanceof Blob) {
    return data.size === 0 ? null : create(data);
  }
  if (!data || typeof data.byteLength !== 'number' || data.byteLength === 0) return null;
  return create(new Blob([data], { type: 'image/jpeg' }));
}

/**
 * Ouvre (et rouvre) le flux. `onFrame(src)` reçoit un blob URL par image, et
 * seuls les deux derniers survivent (l'affiché + celui en décodage) — l'avant-
 * dernier est révoqué à la frame suivante, au lieu d'une douzaine en attente ;
 * `onState('live' | 'idle')` prévient la page. `onFrame` n'est jamais appelé
 * avec `null`. `onCamera(true | false)` prévient quand la caméra du proche
 * s'allume ou s'éteint : la page efface l'image tout de suite, sans attendre
 * que la dernière frame périme.
 *
 * `open` est injectable pour que la logique se teste sans navigateur.
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
    socket.onerror = () => { try { socket.close(); } catch { /* déjà fermé */ } };
  };

  connect();
  return {
    close() {
      closed = true;
      if (timerId) timer.clear(timerId);
      try { socket && socket.close(); } catch { /* déjà fermé */ }
      release();
    },
  };
}
