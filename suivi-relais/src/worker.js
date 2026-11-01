import { readShare, stateOf, emptyState, validId } from './contrat.js';
import { d1Store } from './store.js';

// The realtime room must be **exported by the entrypoint**: without it `wrangler dev`
// and `wrangler deploy` refuse the binding ("not exported in your entrypoint").
export { RelayRoom } from './durable.js';

/** Cleanup of abandoned streams, one write in [PRUNE_EVERY] (bounded base). */
const PRUNE_EVERY = 200;
const KEEP_MS = 86_400_000;

let writes = 0;

/**
 * iSee tracking relay (Cloudflare Worker + D1).
 *
 * - `POST /api/share`: the accompanied phone drops its state (`{shareId, writeKey,
 *   fix?, objects, scene, photo, trip, pictos}`), every 2 s during iSee or
 *   Direct. One stream per id: two loved ones never share the same row.
 * - `GET /api/state?share=<id>`: the caregiver page reads that stream alone.
 * - `GET /health`: probe.
 *
 * The write key is **bound to the stream** on first send: a guessed id is not
 * enough to inject a fake position into someone else's tracking. Without
 * `SHARE_WRITE_KEY` on the Worker (default), no key is required at install.
 */
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    try {
      if (url.pathname === '/api/share') return await share(request, env);
      if (url.pathname === '/api/state') return await state(url, env);
      if (url.pathname === '/ws') return await stream(request, url, env);
      if (url.pathname === '/health') return json({ ok: true }, 200);
      return json({ ok: false, error: 'unknown route' }, 404);
    } catch {
      // A base failure must never look like "waiting for the loved one".
      return json({ ok: false, error: 'relay unavailable' }, 503);
    }
  },
};

async function share(request, env) {
  if (request.method !== 'POST') return json({ ok: false, error: 'POST expected' }, 405);
  const body = await request.json().catch(() => null);
  const parsed = readShare(body, env.SHARE_WRITE_KEY || '');
  if (!parsed.ok) return json({ ok: false, error: parsed.error }, parsed.code);
  const store = d1Store(env.DB);
  const existing = await store.read(parsed.id);
  if (existing && existing.key !== parsed.key) return json({ ok: false, error: 'invalid key' }, 403);
  const now = Date.now();
  // `write` refuses when another key bound the stream between read and write.
  if (!(await store.write(parsed.id, withPreviousPhoto(parsed, existing), parsed.key, now))) {
    return json({ ok: false, error: 'invalid key' }, 403);
  }
  writes += 1;
  if (writes % PRUNE_EVERY === 0) await store.prune(now - KEEP_MS);
  return json({ ok: true }, 200);
}

/**
 * Document to write: the previous packet's picture is **kept** when this send
 * carries none (screen-off tracking service, closed camera) — its `atMs`
 * stays old, so the page hides it instead of passing it off as live. The
 * trip follows the packet: no more guidance = no more bar.
 */
function withPreviousPhoto(parsed, existing) {
  const doc = parsed.doc(Date.now());
  if (!doc.photo && existing && existing.photo) doc.photo = existing.photo;
  return doc;
}

async function state(url, env) {
  if (url.searchParams.get('share') === null) return json({ ok: false, error: 'invalid share' }, 400);
  const id = url.searchParams.get('share');
  if (!validId(id)) return json({ ok: false, error: 'invalid share' }, 400);
  const row = await d1Store(env.DB).read(id);
  return json(row ? stateOf(row, Date.now()) : emptyState(), 200);
}

/**
 * Realtime broadcast (`/ws?share=<id>&role=push|watch`): one room per stream,
 * carried by a Durable Object. The Worker only routes — frames touch neither
 * D1 nor the worker, they pass from the loved one's camera to the connected
 * caregivers (`room.js` describes the contract, `durable.js` the room).
 */
async function stream(request, url, env) {
  const id = url.searchParams.get('share');
  if (!validId(id)) return json({ ok: false, error: 'invalid share' }, 400);
  // Deploy without a binding: say so, instead of breaking the page that has a fallback.
  if (!env.RELAY_ROOM) return json({ ok: false, error: 'streaming not deployed' }, 503);
  const room = env.RELAY_ROOM.get(env.RELAY_ROOM.idFromName(id));
  return room.fetch(request);
}

function json(body, code) {
  return new Response(JSON.stringify(body), {
    status: code,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
  });
}
