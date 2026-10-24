/** Client HTTP (app/src/api.ts) contre un serveur local factice : chemins d'erreur inclus. */
import assert from 'node:assert/strict';
import { startFakeServer, replyJson, withServer } from './fakeserver.mjs';

export const title = 'api.ts — client contre un serveur HTTP local';

const SEG_OK = { width: 2, height: 2, objects: [{ label: 'chat', score: 0.8, box: [0, 0, 2, 2], mask_b64: 'AAA' }] };
const ANALYZE_OK = { description: 'un chat', tags: ['chat'], objects: SEG_OK.objects };

/** Vérifie qu'une promesse rejette une ApiError du bon type et, au besoin, du bon message. */
async function expectApiError(promise, kind, pattern) {
  const error = await promise.then(() => null, (caught) => caught);
  assert.ok(error, `une erreur de type ${kind} était attendue`);
  assert.equal(error.name, 'ApiError');
  assert.equal(error.kind, kind);
  if (pattern) assert.match(error.message, pattern);
  return error;
}

/** URL d'un port fermé (connexion refusée) pour le cas « réseau ». */
async function closedPortUrl() {
  const server = await startFakeServer(() => {});
  const url = server.url;
  await server.close();
  return url;
}

async function healthCase(t, api) {
  await t.test('/health est un GET et décode le contrat', async () => {
    await withServer((req, res) => replyJson(res, 200, { ok: true, model: 'sam3.1', device: 'cuda' }),
      async (server) => {
        const out = await api.health(server.url);
        assert.deepEqual(out, { ok: true, model: 'sam3.1', device: 'cuda' });
        assert.equal(server.requests[0].method, 'GET');
        assert.equal(server.requests[0].url, '/health');
      });
  });
}

async function segmentCases(t, api) {
  await t.test('/segment : POST JSON {image_b64, prompts:null} et réponse validée', async () => {
    await withServer((req, res) => replyJson(res, 200, SEG_OK), async (server) => {
      const out = await api.segment(`${server.url}/`, 'aW1n', null);
      assert.deepEqual(out.objects[0].box, [0, 0, 2, 2]);
      const req = server.requests[0];
      assert.equal(req.method, 'POST');
      assert.equal(req.url, '/segment');
      assert.equal(req.headers['content-type'], 'application/json');
      assert.deepEqual(JSON.parse(req.body), { image_b64: 'aW1n', prompts: null });
    });
  });

  await t.test('/segment : prompts transmis tels quels', async () => {
    await withServer((req, res) => replyJson(res, 200, SEG_OK), async (server) => {
      await api.segment(server.url, 'aW1n', ['chat', 'table']);
      assert.deepEqual(JSON.parse(server.requests[0].body).prompts, ['chat', 'table']);
    });
  });
}

async function payloadCases(t, api) {
  await t.test('/analyze et /ask : corps conformes au contrat', async () => {
    await withServer((req, res) => {
      replyJson(res, 200, req.url === '/analyze' ? ANALYZE_OK : { answer: 'un chat noir' });
    }, async (server) => {
      const analyzed = await api.analyze(server.url, 'aW1n');
      assert.deepEqual(analyzed.tags, ['chat']);
      const asked = await api.ask(server.url, 'aW1n', 'quelle couleur ?', [
        { role: 'user', content: 'bonjour' },
      ]);
      assert.equal(asked.answer, 'un chat noir');
      assert.deepEqual(Object.keys(JSON.parse(server.requests[0].body)), ['image_b64']);
      const askBody = JSON.parse(server.requests[1].body);
      assert.equal(askBody.question, 'quelle couleur ?');
      assert.deepEqual(askBody.history, [{ role: 'user', content: 'bonjour' }]);
    });
  });

  await t.test('/ask sans historique envoie history:null', async () => {
    await withServer((req, res) => replyJson(res, 200, { answer: 'ok' }), async (server) => {
      await api.ask(server.url, 'aW1n', 'q', []);
      assert.equal(JSON.parse(server.requests[0].body).history, null);
    });
  });

  await t.test('URL normalisée : espaces et slashs finaux ignorés', async () => {
    await withServer((req, res) => replyJson(res, 200, SEG_OK), async (server) => {
      await api.segment(`  ${server.url}///  `, 'aW1n');
      assert.equal(server.requests[0].url, '/segment');
    });
  });
}

async function errorCases(t, api) {
  await t.test('HTTP 400 : le detail du serveur remonte dans le message', async () => {
    await withServer((req, res) => replyJson(res, 400, { detail: 'image_b64 : base64 invalide' }),
      async (server) => {
        const error = await expectApiError(api.segment(server.url, 'x'), 'http', /base64 invalide/);
        assert.equal(error.status, 400);
        assert.match(error.message, /\(400\)/);
      });
  });

  await t.test('HTTP 500 sans detail : message générique, corps non-JSON accepté', async () => {
    await withServer((req, res) => { res.writeHead(500); res.end('<html>boom</html>'); },
      async (server) => {
        const error = await expectApiError(api.analyze(server.url, 'aW1n'), 'http', /erreur HTTP 500/);
        assert.equal(error.status, 500);
      });
  });

  await t.test('200 non-JSON → kind invalid', async () => {
    await withServer((req, res) => { res.writeHead(200); res.end('pas du json'); }, async (server) => {
      await expectApiError(api.health(server.url), 'invalid', /non JSON/);
    });
  });

  await t.test('200 hors contrat → kind invalid, message « réponse inattendue »', async () => {
    await withServer((req, res) => replyJson(res, 200, { width: 2 }), async (server) => {
      await expectApiError(api.segment(server.url, 'aW1n'), 'invalid', /réponse inattendue/);
    });
  });
}

async function networkCases(t, api) {
  await t.test('hôte injoignable → kind network', async () => {
    await expectApiError(api.health(await closedPortUrl()), 'network', /Impossible de joindre le backend/);
  });

  await t.test('requête abandonnée → kind timeout', async () => {
    const original = globalThis.fetch;
    globalThis.fetch = async () => {
      const error = new Error('The operation was aborted');
      error.name = 'AbortError';
      throw error;
    };
    try {
      await expectApiError(api.health('http://127.0.0.1:1'), 'timeout', /60 s/);
    } finally {
      globalThis.fetch = original;
    }
  });
}

async function miscCases(t, api) {
  await t.test('describeError et délai SPEC (60 s)', () => {
    assert.equal(api.REQUEST_TIMEOUT_MS, 60_000);
    assert.equal(api.describeError(new api.ApiError('http', 'boum', 400)), 'boum');
    assert.match(api.describeError('texte'), /texte/);
    assert.equal(api.describeError(null), 'Erreur inconnue.');
  });
}

async function run(t, ctx) {
  const api = ctx.load('api.js');
  await healthCase(t, api);
  await segmentCases(t, api);
  await payloadCases(t, api);
  await errorCases(t, api);
  await networkCases(t, api);
  await miscCases(t, api);
}

export default { title, run };