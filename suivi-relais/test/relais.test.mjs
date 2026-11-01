import test from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/worker.js';
import { stateOf, readShare, FRESH_MS } from '../src/contrat.js';

/** In-memory D1 base: only replays the four `store.js` queries. */
function fakeDb() {
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
          throw new Error('Unexpected SQL: ' + sql);
        },
      };
      return stmt;
    },
  };
}

const env = (extra = {}) => ({ DB: fakeDb(), ...extra });

async function call(handlerEnv, path, init = {}) {
  const response = await worker.fetch(new Request('https://relais.test' + path, init), handlerEnv);
  return { code: response.status, body: await response.json() };
}

const post = (body) => ({ method: 'POST', body: JSON.stringify(body) });

const payload = (id, extra = {}) => ({
  shareId: id,
  writeKey: '',
  fix: { lat: 48.85, lon: 2.35, accM: 6, headingDeg: 90, atMs: Date.now(), indoor: false },
  objects: [{ label: 'door', distM: 2, angleDeg: 10 }],
  scene: { text: 'An obstacle on the right.', speaking: true, fromUser: false },
  ...extra,
});

test('two stream ids stay fully separate', async () => {
  const e = env();
  assert.equal((await call(e, '/api/share', post(payload('camille-a')))).code, 200);
  assert.equal((await call(e, '/api/share', post({
    ...payload('camille-b'),
    objects: [],
    scene: { text: 'Another trip.' },
  }))).code, 200);
  const a = await call(e, '/api/state?share=camille-a');
  const b = await call(e, '/api/state?share=camille-b');
  assert.equal(a.body.live, true);
  assert.equal(a.body.fix.lat, 48.85);
  assert.equal(a.body.objects[0].label, 'door');
  assert.equal(a.body.scene.text, 'An obstacle on the right.');
  assert.deepEqual(b.body.objects, []);
  assert.equal(b.body.scene.text, 'Another trip.');
  assert.equal(e.DB.rows.size, 2);
});

test('a fresh install writes with no key to type', async () => {
  const e = env();
  assert.equal((await call(e, '/api/share', post(payload('no-key')))).code, 200);
  const state = await call(e, '/api/state?share=no-key');
  assert.equal(state.body.live, true);
  assert.equal(state.body.fix.accM, 6);
});

test('the key belongs to the stream: the first write fixes it', async () => {
  const e = env();
  assert.equal((await call(e, '/api/share', post(payload('key-a', { writeKey: 'k1' })))).code, 200);
  const other = await call(e, '/api/share', post(payload('key-a', { writeKey: 'k2' })));
  assert.equal(other.code, 403);
  assert.equal(other.body.error, 'invalid key');
  assert.equal((await call(e, '/api/share', post(payload('key-a', { writeKey: 'k1' })))).code, 200);
  // A neighbouring stream keeps its own: no global key was imposed.
  assert.equal((await call(e, '/api/share', post(payload('key-b', { writeKey: 'k9' })))).code, 200);
});

test('SHARE_WRITE_KEY, when set, locks the whole relay', async () => {
  const e = env({ SHARE_WRITE_KEY: 'relay-secret' });
  assert.equal((await call(e, '/api/share', post(payload('locked')))).code, 403);
  assert.equal((await call(e, '/api/share', post(payload('locked', { writeKey: 'relay-secret' })))).code, 200);
});

test('invalid bodies are refused explicitly', async () => {
  const e = env();
  assert.equal((await call(e, '/api/share', post({ ...payload('ok'), shareId: 'Léo' }))).code, 400);
  assert.equal((await call(e, '/api/share', post({ ...payload('ok'), fix: { lat: 999, lon: 2, accM: 1 } }))).code, 400);
  assert.equal((await call(e, '/api/share', post({ ...payload('ok'), objects: [{ label: 'door', distM: 2 }] }))).code, 400);
  assert.equal((await call(e, '/api/share', post({ ...payload('ok'), writeKey: 'x'.repeat(65) }))).code, 400);
  assert.equal((await call(e, '/api/share', { method: 'GET' })).code, 405);
});

test('reading: with no share or an unknown one, the page guesses no stream', async () => {
  const e = env();
  assert.equal((await call(e, '/api/state')).code, 400);
  assert.equal((await call(e, '/api/state?share=..%2F')).code, 400);
  const empty = await call(e, '/api/state?share=nobody');
  assert.deepEqual(empty.body, { ok: true, live: false });
});

test('a state over 12 s old is no longer LIVE but keeps its position', () => {
  const now = 1_000_000;
  const row = { fix: { lat: 1, lon: 2, accM: 3, headingDeg: null, atMs: now, indoor: false }, objects: [], scene: null, serverAt: now - FRESH_MS };
  const state = stateOf(row, now);
  assert.equal(state.live, false);
  assert.equal(state.ageMs, FRESH_MS);
  assert.equal(state.fix.lat, 1);
  assert.equal(stateOf({ ...row, serverAt: now }, now).live, true);
});

test('the photo and trip reach the caregiver page as-is', async () => {
  const e = env();
  const photo = { jpeg: 'AAAA', atMs: 1_700_000_000_000 };
  const trip = { destination: 'Gare de Lyon', totalM: 1200, remainingM: 300, remainingS: 240, progress: 0.75 };
  assert.equal((await call(e, '/api/share', post(payload('track-image', { photo, trip })))).code, 200);
  const state = await call(e, '/api/state?share=track-image');
  assert.deepEqual(state.body.photo, photo);
  assert.deepEqual(state.body.trip, trip);
  // Without guidance, the bar goes away on the next packet.
  assert.equal((await call(e, '/api/share', post(payload('track-image')))).code, 200);
  assert.equal((await call(e, '/api/state?share=track-image')).body.trip, null);
});

test('a refused photo is explicit, and a missing picture keeps the last one', async () => {
  const e = env();
  assert.equal((await call(e, '/api/share', post(payload('broken-photo', { photo: { jpeg: '<script>' } })))).code, 400);
  assert.equal((await call(e, '/api/share', post(payload('long-photo', { photo: { jpeg: 'A'.repeat(120_001) } })))).code, 400);
  assert.equal((await call(e, '/api/share', post(payload('broken-trip', { trip: { destination: 'X', totalM: -1 } })))).code, 400);
  const photo = { jpeg: 'BBBB', atMs: 1 };
  await call(e, '/api/share', post(payload('kept-photo', { photo })));
  // The screen-off tracking service republishes without a picture: the last one stays, with its age.
  await call(e, '/api/share', post(payload('kept-photo')));
  assert.deepEqual((await call(e, '/api/state?share=kept-photo')).body.photo, photo);
});

test('the text is bounded and stays missing when absent', () => {
  const long = readShare(payload('ok', { scene: { text: 'x'.repeat(3000) } }));
  assert.equal(long.doc(Date.now()).scene.text.length, 2000);
  const missing = readShare(payload('ok', { scene: undefined }));
  assert.equal(missing.doc(Date.now()).scene, null);
});

test('recognized landmarks reach the caregiver page as drawings', async () => {
  const e = env();
  const pictos = { atMs: 1_700_000_000_000, items: [{ label: 'medics', png: 'AQID' }] };
  assert.equal((await call(e, '/api/share', post(payload('track-picto', { pictos })))).code, 200);
  const state = await call(e, '/api/state?share=track-picto');
  assert.deepEqual(state.body.pictos, pictos);
  // Without detection, the strip goes away on the next packet.
  assert.equal((await call(e, '/api/share', post(payload('track-picto')))).code, 200);
  assert.equal((await call(e, '/api/state?share=track-picto')).body.pictos, null);
  // An explicit empty list counts as missing, not a stored empty object.
  const empty = { atMs: 1, items: [] };
  assert.equal((await call(e, '/api/share', post(payload('track-picto', { pictos: empty })))).code, 200);
  assert.equal((await call(e, '/api/state?share=track-picto')).body.pictos, null);
});

test('without a position, photo and sentence still pass (camera without GPS)', async () => {
  const e = env();
  const photo = { jpeg: 'AAAA', atMs: 1_700_000_000_000 };
  const missing = { ...payload('no-gps', { photo }), fix: undefined };
  assert.equal((await call(e, '/api/share', post(missing))).code, 200);
  const state = await call(e, '/api/state?share=no-gps');
  assert.equal(state.body.live, true);
  assert.equal(state.body.fix, null);
  assert.deepEqual(state.body.photo, photo);
  // An explicit `null` counts as missing, not an error.
  const nul = { ...payload('no-gps-null', { photo }), fix: null };
  assert.equal((await call(e, '/api/share', post(nul))).code, 200);
  assert.equal((await call(e, '/api/state?share=no-gps-null')).body.fix, null);
  // A present but invalid position stays refused.
  const bad = { ...payload('no-gps'), fix: { lat: 999, lon: 2, accM: 1 } };
  assert.equal((await call(e, '/api/share', post(bad))).code, 400);
});

test('unreadable landmarks are refused explicitly', async () => {
  const e = env();
  const bad = (pictos) => call(e, '/api/share', post(payload('broken-picto', { pictos })));
  assert.equal((await bad({ atMs: 1, items: [{ label: '', png: 'AQID' }] })).code, 400);
  assert.equal((await bad({ atMs: 1, items: [{ label: 'x', png: '<script>' }] })).code, 400);
  assert.equal((await bad({ atMs: 1, items: [{ label: 'x', png: 'A'.repeat(30_001) }] })).code, 400);
  assert.equal((await bad({ atMs: 1, items: 'nope' })).code, 400);
});
