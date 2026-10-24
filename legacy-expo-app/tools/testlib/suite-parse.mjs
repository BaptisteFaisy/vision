/** Parsing des 4 réponses du contrat (app/src/parse.ts). */
import assert from 'node:assert/strict';

export const title = 'parse.ts — validation des réponses du contrat';

const OBJECT = { label: 'person', score: 0.91, box: [1, 2, 30, 40], mask_b64: 'iVBORw0KGgo=' };

function rejects(fn, label) {
  assert.throws(fn, Error, `${label} aurait dû être rejeté`);
}

async function segmentCases(t, ctx) {
  const { parseSegment } = ctx.load('parse.js');

  await t.test('/segment valide : width, height et objets', () => {
    const out = parseSegment({ width: 640, height: 480, objects: [OBJECT] });
    assert.equal(out.width, 640);
    assert.equal(out.height, 480);
    assert.deepEqual(out.objects, [OBJECT]);
  });

  await t.test('/segment : réponses hors contrat rejetées', () => {
    rejects(() => parseSegment(null), 'null');
    rejects(() => parseSegment([]), 'tableau');
    rejects(() => parseSegment({ height: 1, objects: [] }), 'width manquant');
    rejects(() => parseSegment({ width: 1, height: 1 }), 'objects manquant');
  });

  await t.test('/segment : objets invalides rejetés', () => {
    const base = { width: 1, height: 1 };
    rejects(() => parseSegment({ ...base, objects: [{ ...OBJECT, score: '0.9' }] }), 'score en chaîne');
    rejects(() => parseSegment({ ...base, objects: [{ ...OBJECT, box: [1, 2, 3] }] }), 'box de 3 valeurs');
    rejects(() => parseSegment({ ...base, objects: [{ ...OBJECT, mask_b64: 42 }] }), 'mask_b64 non-chaîne');
    rejects(() => parseSegment({ ...base, objects: [{ ...OBJECT, label: null }] }), 'label null');
    rejects(() => parseSegment({ ...base, objects: [OBJECT, 'texte'] }), 'objet non-objet');
  });
}

async function analyzeCases(t, ctx) {
  const { parseAnalyze } = ctx.load('parse.js');

  await t.test('/analyze : tags absents ou null → []', () => {
    assert.deepEqual(parseAnalyze({ description: 'd', objects: [] }).tags, []);
    assert.deepEqual(parseAnalyze({ description: 'd', tags: null, objects: [] }).tags, []);
    assert.deepEqual(parseAnalyze({ description: 'd', tags: ['a', 'b'], objects: [] }).tags, ['a', 'b']);
  });

  await t.test('/analyze : réponses hors contrat rejetées', () => {
    rejects(() => parseAnalyze({ objects: [] }), 'description manquante');
    rejects(() => parseAnalyze({ description: 'd', tags: [1], objects: [] }), 'tag non-chaîne');
    rejects(() => parseAnalyze({ description: 'd', tags: 'a', objects: [] }), 'tags non-liste');
  });
}

async function miscCases(t, ctx) {
  const { parseAsk, parseHealth, parseObjects } = ctx.load('parse.js');

  await t.test('/ask et /health', () => {
    assert.deepEqual(parseAsk({ answer: 'chat' }), { answer: 'chat' });
    rejects(() => parseAsk({}), 'answer manquante');
    assert.deepEqual(parseHealth({ ok: true, model: 'sam3.1', device: 'cuda' }),
      { ok: true, model: 'sam3.1', device: 'cuda' });
    assert.deepEqual(parseHealth({}), { ok: false, model: 'inconnu', device: 'inconnu' });
  });

  await t.test('parseObjects : nom du champ propagé dans les erreurs', () => {
    assert.deepEqual(parseObjects([OBJECT], 'items'), [OBJECT]);
    assert.throws(() => parseObjects([{ ...OBJECT, score: null }], 'items'), /items\[0\]\.score/);
    assert.throws(() => parseObjects({}, 'items'), /items : tableau attendu/);
  });
}

async function run(t, ctx) {
  await segmentCases(t, ctx);
  await analyzeCases(t, ctx);
  await miscCases(t, ctx);
}

export default { title, run };