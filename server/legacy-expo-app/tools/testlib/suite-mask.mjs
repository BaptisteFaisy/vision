/** Colorisation des masques (app/src/utils/mask.ts), vérifiée pixel à pixel. */
import assert from 'node:assert/strict';
import { grayPng } from './pnggray.mjs';
import { decodePng } from './pngdecode.mjs';

export const title = 'mask.ts — colorisation d’un PNG gris, pixel à pixel';

const ALPHA = 0.45;
const BLUE = '#4F8EF7';

/** Encode un masque gris puis le colorise via le vrai code : renvoie les octets PNG. */
function colorizeBytes(colorizeMask, mask) {
  const maskB64 = Buffer.from(grayPng(mask.width, mask.height, mask.gray)).toString('base64');
  const uri = colorizeMask({ maskB64, color: mask.color || BLUE, opacity: mask.opacity ?? ALPHA });
  assert.ok(uri && uri.startsWith('data:image/png;base64,'), 'data URI PNG attendu');
  return Buffer.from(uri.split(',')[1], 'base64');
}

const colorize = (colorizeMask, mask) => decodePng(colorizeBytes(colorizeMask, mask));

function pixel(png, index) {
  return Array.from(png.rgba.subarray(index * 4, index * 4 + 4));
}

async function colorCases(t, { colorizeMask, rgb }) {
  await t.test('masque 4×3 : couleur exacte et alpha = gris × 0,45', () => {
    const gray = Uint8Array.from([0, 64, 128, 255, 255, 0, 128, 64, 10, 20, 30, 40]);
    const out = colorize(colorizeMask, { width: 4, height: 3, gray });
    assert.equal(out.width, 4);
    assert.equal(out.height, 3);
    for (let i = 0; i < gray.length; i += 1) {
      assert.deepEqual(pixel(out, i), [...rgb, Math.round(gray[i] * ALPHA)], `pixel ${i}`);
    }
  });

  await t.test('alpha 0 sur un masque noir, arrondi exact sur du blanc', () => {
    const out = colorize(colorizeMask, { width: 2, height: 1, gray: Uint8Array.of(0, 255) });
    assert.deepEqual(pixel(out, 0), [...rgb, 0]);
    assert.deepEqual(pixel(out, 1), [...rgb, Math.round(255 * ALPHA)]);
  });

  await t.test('opacité 1 : alpha = valeur du masque', () => {
    const gray = Uint8Array.from([0, 1, 127, 255]);
    const out = colorize(colorizeMask, { width: 2, height: 2, gray, opacity: 1 });
    assert.deepEqual(pixel(out, 0), [...rgb, 0]);
    assert.deepEqual(pixel(out, 3), [...rgb, 255]);
  });

  await t.test('couleur #fff : blanc, pas de teinte résiduelle', () => {
    const out = colorize(colorizeMask, {
      width: 1, height: 1, gray: Uint8Array.of(200), color: '#fff',
    });
    assert.deepEqual(pixel(out, 0), [255, 255, 255, Math.round(200 * ALPHA)]);
  });
}

async function scaleCases(t, { colorizeMask, hexToRgb, MAX_MASK_EDGE }) {
  await t.test('masque 1024×1024 : réduit à 512 (MAX_MASK_EDGE) sans décalage', () => {
    const size = 512 * 2;
    const gray = new Uint8Array(size * size);
    for (let y = 0; y < size; y += 1) {
      for (let x = 0; x < size; x += 1) gray[y * size + x] = (x * 3 + y * 5) % 256;
    }
    const out = colorize(colorizeMask, { width: size, height: size, gray, opacity: 1 });
    assert.equal(out.width, MAX_MASK_EDGE);
    assert.equal(out.height, MAX_MASK_EDGE);
    for (const [x, y] of [[0, 0], [10, 7], [MAX_MASK_EDGE - 1, MAX_MASK_EDGE - 1]]) {
      const [red, green, blue] = hexToRgb(BLUE);
      const grayValue = gray[y * 2 * size + x * 2];
      assert.deepEqual(pixel(out, y * MAX_MASK_EDGE + x),
        [red, green, blue, grayValue], `pixel (${x},${y})`);
    }
  });

  await t.test('masque 1×1 : pixels corrects même sans chunk IEND', () => {
    // Quirk upstream constaté : pour les très petites images, upng-js 2.1.0 tronque
    // le chunk IEND (les données IDAT, elles, sont complètes). Les masques réels font
    // la taille de la photo ; le décodeur de test tolère donc l'absence d'IEND.
    const bytes = colorizeBytes(colorizeMask, {
      width: 1, height: 1, gray: Uint8Array.of(255), color: '#fff',
    });
    assert.deepEqual(pixel(decodePng(bytes), 0), [255, 255, 255, Math.round(255 * ALPHA)]);
  });
}

async function run(t, ctx) {
  const { colorizeMask, MAX_MASK_EDGE } = ctx.load('utils/mask.js');
  const { hexToRgb } = ctx.load('utils/color.js');
  const rgb = hexToRgb(BLUE);

  await t.test('hexToRgb : #rrggbb, #rgb, invalide → blanc', () => {
    assert.deepEqual(hexToRgb(BLUE), [79, 142, 247]);
    assert.deepEqual(hexToRgb('#fff'), [255, 255, 255]);
    assert.deepEqual(hexToRgb('nawak'), [255, 255, 255]);
  });
  await t.test('MAX_MASK_EDGE vaut 512 (bornes d’affichage)', () => {
    assert.equal(MAX_MASK_EDGE, 512);
  });
  await t.test('masque inexploitable → null', () => {
    assert.equal(colorizeMask({ maskB64: 'pas-un-png', color: BLUE, opacity: ALPHA }), null);
    assert.equal(colorizeMask({ maskB64: '', color: BLUE, opacity: ALPHA }), null);
  });

  await colorCases(t, { colorizeMask, rgb });
  await scaleCases(t, { colorizeMask, hexToRgb, MAX_MASK_EDGE });
}

export default { title, run };