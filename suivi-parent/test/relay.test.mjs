import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

// Ces tests n'appellent jamais le stockage de production.
for (const key of ['UPSTASH_REDIS_REST_URL', 'KV_REST_API_URL', 'SHARE_WRITE_KEY']) delete process.env[key];
const require = createRequire(import.meta.url);
const share = require('../api/share.js');
const state = require('../api/state.js');

async function request(handler, req) {
  const result = { code: 200 };
  const res = {
    setHeader() {},
    status(code) { result.code = code; return this; },
    json(body) { result.body = body; return this; },
  };
  await handler(req, res);
  return result;
}

const payload = id => ({
  shareId: id, fix: { lat: 48.85, lon: 2.35, accM: 5, headingDeg: 90 },
  objects: [{ label: 'porte', distM: 2, angleDeg: 90 }],
  scene: { text: 'Un obstacle à droite.', speaking: true, fromUser: false },
});

test('deux téléphones lisent des sessions distinctes avec texte et objets associés', async () => {
  await request(share, { method: 'POST', body: payload('test-a') });
  await request(share, { method: 'POST', body: { ...payload('test-b'), scene: { text: 'Autre trajet.' }, objects: [] } });
  const a = await request(state, { query: { share: 'test-a' } });
  const b = await request(state, { query: { share: 'test-b' } });
  assert.equal(a.body.scene.text, 'Un obstacle à droite.');
  assert.equal(a.body.scene.speaking, true);
  assert.equal(a.body.objects[0].label, 'porte');
  assert.equal(b.body.scene.text, 'Autre trajet.');
  assert.deepEqual(b.body.objects, []);
  assert.equal((await request(state, { query: {} })).code, 400);
});

test('un ancien client sans texte reste lisible ; aucun texte antérieur ne fuit', async () => {
  const body = payload('test-old');
  delete body.scene;
  await request(share, { method: 'POST', body });
  const response = await request(state, { query: { share: 'test-old' } });
  assert.equal(response.body.scene, null);
  assert.equal(response.body.objects.length, 1);
});

test('texte borné et clé d’écriture toujours exigée quand configurée', async () => {
  const body = { ...payload('test-long'), scene: { text: 'x'.repeat(3000) } };
  await request(share, { method: 'POST', body });
  assert.equal((await request(state, { query: { share: 'test-long' } })).body.scene.text.length, 2000);
  process.env.SHARE_WRITE_KEY = 'test-only';
  try { assert.equal((await request(share, { method: 'POST', body })).code, 403); }
  finally { delete process.env.SHARE_WRITE_KEY; }
});
