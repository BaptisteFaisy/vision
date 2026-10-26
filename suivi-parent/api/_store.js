// Stockage de l'état de suivi : Upstash Redis (prod, multi-instances) ou
// mémoire (dev / préview mono-instance). Aucune dépendance : REST via fetch.
const mem = globalThis.__suiviStore || (globalThis.__suiviStore = new Map());

function upstash() {
  // Deux jeux de noms selon l'installation : Upstash direct (UPSTASH_*) ou
  // l'intégration Vercel KV (KV_REST_API_*).
  const url = process.env.UPSTASH_REDIS_REST_URL || process.env.KV_REST_API_URL;
  const token = process.env.UPSTASH_REDIS_REST_TOKEN || process.env.KV_REST_API_TOKEN;
  return url && token ? { url: String(url).replace(/\/$/, ''), token } : null;
}

async function upGet(key) {
  const u = upstash();
  if (!u) return null;
  const r = await fetch(u.url + '/get/' + encodeURIComponent(key), {
    headers: { Authorization: 'Bearer ' + u.token },
  });
  if (!r.ok) return null;
  const j = await r.json().catch(() => null);
  if (!j || j.result == null) return null;
  try {
    return JSON.parse(j.result);
  } catch {
    return null;
  }
}

async function upSet(key, value, ttlSeconds) {
  const u = upstash();
  if (!u) return false;
  const r = await fetch(
    u.url + '/set/' + encodeURIComponent(key) + '/' + encodeURIComponent(JSON.stringify(value)) +
      '/EX/' + ttlSeconds,
    { headers: { Authorization: 'Bearer ' + u.token } },
  );
  return r.ok;
}

function memGet(key) {
  const e = mem.get(key);
  if (!e || e.exp < Date.now()) {
    mem.delete(key);
    return null;
  }
  return e.value;
}

function memSet(key, value, ttlSeconds) {
  mem.set(key, { value, exp: Date.now() + ttlSeconds * 1000 });
}

async function storeGet(key) {
  return upstash() ? upGet(key) : memGet(key);
}

async function storeSet(key, value, ttlSeconds = 120) {
  if (upstash()) return upSet(key, value, ttlSeconds);
  memSet(key, value, ttlSeconds);
  return true;
}

/** Identifiant de flux : un par proche suivi, glissé dans `?share=`. */
function validId(id) {
  return typeof id === 'string' && /^[a-z0-9-]{1,32}$/.test(id);
}

module.exports = { storeGet, storeSet, validId };
