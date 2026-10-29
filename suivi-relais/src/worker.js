import { readShare, stateOf, emptyState, validId } from './contrat.js';
import { d1Store } from './store.js';

/** Nettoyage des flux abandonnés, une écriture sur [PRUNE_EVERY] (base bornée). */
const PRUNE_EVERY = 200;
const KEEP_MS = 86_400_000;

let writes = 0;

/**
 * Relais du suivi iSee (Worker Cloudflare + D1).
 *
 * - `POST /api/share` : le téléphone accompagné dépose son état (`{shareId, writeKey,
 *   fix, objects, scene, photo, trip}`), toutes les 2 s pendant iSee ou Direct. Un
 *   flux par identifiant : deux proches ne partagent jamais la même ligne.
 * - `GET /api/state?share=<id>` : la page caregiver lit ce seul flux.
 * - `GET /health` : sonde.
 *
 * La clé d'écriture est **liée au flux** au premier envoi : un identifiant deviné
 * ne suffit pas à injecter une fausse position dans le suivi d'un autre. Sans
 * `SHARE_WRITE_KEY` côté Worker (défaut), aucune clé n'est exigée à l'installation.
 */
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    try {
      if (url.pathname === '/api/share') return await share(request, env);
      if (url.pathname === '/api/state') return await state(url, env);
      if (url.pathname === '/health') return json({ ok: true }, 200);
      return json({ ok: false, error: 'route inconnue' }, 404);
    } catch {
      // Une panne de base ne doit jamais ressembler à « en attente du proche ».
      return json({ ok: false, error: 'relais indisponible' }, 503);
    }
  },
};

async function share(request, env) {
  if (request.method !== 'POST') return json({ ok: false, error: 'POST attendu' }, 405);
  const body = await request.json().catch(() => null);
  const parsed = readShare(body, env.SHARE_WRITE_KEY || '');
  if (!parsed.ok) return json({ ok: false, error: parsed.error }, parsed.code);
  const store = d1Store(env.DB);
  const existing = await store.read(parsed.id);
  if (existing && existing.key !== parsed.key) return json({ ok: false, error: 'clé invalide' }, 403);
  const now = Date.now();
  // `write` refuse si une autre clé a lié le flux entre la lecture et l'écriture.
  if (!(await store.write(parsed.id, withPreviousPhoto(parsed, existing), parsed.key, now))) {
    return json({ ok: false, error: 'clé invalide' }, 403);
  }
  writes += 1;
  if (writes % PRUNE_EVERY === 0) await store.prune(now - KEEP_MS);
  return json({ ok: true }, 200);
}

/**
 * Document à écrire : l'image du paquet précédent est **conservée** quand cet
 * envoi n'en porte pas (service de suivi écran éteint, caméra fermée) — son
 * `atMs` reste ancien, donc la page la cache au lieu de la présenter comme du
 * direct. Le trajet, lui, suit le paquet : plus de guidage = plus de barre.
 */
function withPreviousPhoto(parsed, existing) {
  const doc = parsed.doc(Date.now());
  if (!doc.photo && existing && existing.photo) doc.photo = existing.photo;
  return doc;
}

async function state(url, env) {
  if (url.searchParams.get('share') === null) return json({ ok: false, error: 'share invalide' }, 400);
  const id = url.searchParams.get('share');
  if (!validId(id)) return json({ ok: false, error: 'share invalide' }, 400);
  const row = await d1Store(env.DB).read(id);
  return json(row ? stateOf(row, Date.now()) : emptyState(), 200);
}

function json(body, code) {
  return new Response(JSON.stringify(body), {
    status: code,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
  });
}
