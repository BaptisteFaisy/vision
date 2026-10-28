import test from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/worker.js';
import { stateOf, readShare, FRESH_MS } from '../src/contrat.js';

/** Base D1 en mémoire : ne rejoue que les quatre requêtes de `store.js`. */
function baseFactice() {
  const rows = new Map();
  return {
    rows,
    prepare(sql) {
      const stmt = {
        args: [],
        bind(...args) { stmt.args = args; return stmt; },
        async first() {
          const row = rows.get(stmt.args[0]);
          return row ? { ...row } : null;
        },
        async run() {
          if (sql.startsWith('CREATE')) return { success: true, meta: { changes: 0 } };
          if (sql.startsWith('SELECT')) return { success: true, meta: { changes: 0 } };
          if (sql.startsWith('DELETE')) {
            const before = stmt.args[0];
            let changes = 0;
            for (const [id, row] of rows) if (row.updated_at < before) { rows.delete(id); changes += 1; }
            return { success: true, meta: { changes } };
          }
          if (sql.startsWith('INSERT')) {
            const [id, doc, at, key] = stmt.args;
            const row = rows.get(id);
            if (row && row.write_key !== key) return { success: true, meta: { changes: 0 } };
            rows.set(id, { doc, updated_at: at, write_key: key });
            return { success: true, meta: { changes: 1 } };
          }
          throw new Error('SQL inattendu : ' + sql);
        },
      };
      return stmt;
    },
  };
}

const env = (extra = {}) => ({ DB: baseFactice(), ...extra });

async function call(handlerEnv, path, init = {}) {
  const response = await worker.fetch(new Request('https://relais.test' + path, init), handlerEnv);
  return { code: response.status, body: await response.json() };
}

const post = (body) => ({ method: 'POST', body: JSON.stringify(body) });

const payload = (id, extra = {}) => ({
  shareId: id,
  writeKey: '',
  fix: { lat: 48.85, lon: 2.35, accM: 6, headingDeg: 90, atMs: Date.now(), indoor: false },
  objects: [{ label: 'porte', distM: 2, angleDeg: 10 }],
  scene: { text: 'Un obstacle à droite.', speaking: true, fromUser: false },
  ...extra,
});

test('deux identifiants de flux restent totalement séparés', async () => {
  const e = env();
  assert.equal((await call(e, '/api/share', post(payload('camille-a')))).code, 200);
  assert.equal((await call(e, '/api/share', post({
    ...payload('camille-b'),
    objects: [],
    scene: { text: 'Autre trajet.' },
  }))).code, 200);
  const a = await call(e, '/api/state?share=camille-a');
  const b = await call(e, '/api/state?share=camille-b');
  assert.equal(a.body.live, true);
  assert.equal(a.body.fix.lat, 48.85);
  assert.equal(a.body.objects[0].label, 'porte');
  assert.equal(a.body.scene.text, 'Un obstacle à droite.');
  assert.deepEqual(b.body.objects, []);
  assert.equal(b.body.scene.text, 'Autre trajet.');
  assert.equal(e.DB.rows.size, 2);
});

test('une installation neuve écrit sans aucune clé à saisir', async () => {
  const e = env();
  assert.equal((await call(e, '/api/share', post(payload('sans-cle')))).code, 200);
  const state = await call(e, '/api/state?share=sans-cle');
  assert.equal(state.body.live, true);
  assert.equal(state.body.fix.accM, 6);
});

test('la clé appartient au flux : la première écriture la fixe', async () => {
  const e = env();
  assert.equal((await call(e, '/api/share', post(payload('cle-a', { writeKey: 'k1' })))).code, 200);
  const autre = await call(e, '/api/share', post(payload('cle-a', { writeKey: 'k2' })));
  assert.equal(autre.code, 403);
  assert.equal(autre.body.error, 'clé invalide');
  assert.equal((await call(e, '/api/share', post(payload('cle-a', { writeKey: 'k1' })))).code, 200);
  // Un flux voisin garde la sienne : aucune clé globale n'a été imposée.
  assert.equal((await call(e, '/api/share', post(payload('cle-b', { writeKey: 'k9' })))).code, 200);
});

test('SHARE_WRITE_KEY, quand elle existe, verrouille tout le relais', async () => {
  const e = env({ SHARE_WRITE_KEY: 'secret-du-relais' });
  assert.equal((await call(e, '/api/share', post(payload('verrou')))).code, 403);
  assert.equal((await call(e, '/api/share', post(payload('verrou', { writeKey: 'secret-du-relais' })))).code, 200);
});

test('les corps invalides sont refusés explicitement', async () => {
  const e = env();
  assert.equal((await call(e, '/api/share', post({ ...payload('ok'), shareId: 'Léo' }))).code, 400);
  assert.equal((await call(e, '/api/share', post({ ...payload('ok'), fix: { lat: 999, lon: 2, accM: 1 } }))).code, 400);
  assert.equal((await call(e, '/api/share', post({ ...payload('ok'), objects: [{ label: 'porte', distM: 2 }] }))).code, 400);
  assert.equal((await call(e, '/api/share', post({ ...payload('ok'), writeKey: 'x'.repeat(65) }))).code, 400);
  assert.equal((await call(e, '/api/share', { method: 'GET' })).code, 405);
});

test('lecture : sans share ou inconnu, la page ne devine aucun flux', async () => {
  const e = env();
  assert.equal((await call(e, '/api/state')).code, 400);
  assert.equal((await call(e, '/api/state?share=..%2F')).code, 400);
  const vide = await call(e, '/api/state?share=personne');
  assert.deepEqual(vide.body, { ok: true, live: false });
});

test('un état de plus de 12 s n est plus LIVE mais garde sa position', () => {
  const now = 1_000_000;
  const row = { fix: { lat: 1, lon: 2, accM: 3, headingDeg: null, atMs: now, indoor: false }, objects: [], scene: null, serverAt: now - FRESH_MS };
  const state = stateOf(row, now);
  assert.equal(state.live, false);
  assert.equal(state.ageMs, FRESH_MS);
  assert.equal(state.fix.lat, 1);
  assert.equal(stateOf({ ...row, serverAt: now }, now).live, true);
});

test('le texte est borné et reste absent quand il manque', () => {
  const long = readShare(payload('ok', { scene: { text: 'x'.repeat(3000) } }));
  assert.equal(long.doc(Date.now()).scene.text.length, 2000);
  const sans = readShare(payload('ok', { scene: undefined }));
  assert.equal(sans.doc(Date.now()).scene, null);
});
