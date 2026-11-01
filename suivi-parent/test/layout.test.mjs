import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const css = readFileSync(join(ROOT, 'styles.css'), 'utf8');
const html = readFileSync(join(ROOT, 'index.html'), 'utf8');

/** First `selector { ... }` block of the sheet (outside media queries). */
function rule(selector) {
  const at = css.indexOf(selector + ' {');
  assert.notEqual(at, -1, selector + ' has a style block');
  return css.slice(at, css.indexOf('}', at));
}

test('the camera stays glued to the bottom even when pictos and trip hide', () => {
  // A grid with N explicit rows files #view into the 2nd (auto) row as soon
  // as one section hides, and the empty 1.3fr row stays white below.
  // In a flex column, hidden sections vanish and the last one fills.
  const main = rule('main');
  assert.match(main, /display\s*:\s*flex/, 'main is flex');
  assert.match(main, /flex-direction\s*:\s*column/, 'main is a column');
  assert.doesNotMatch(main, /grid-template-rows/, 'no explicit row');
});

test('the camera fills down to the bottom edge when it shows', () => {
  const view = rule('#view');
  const grow = view.match(/flex\s*:\s*([0-9]+)/);
  assert.ok(grow && Number(grow[1]) > 0, '#view grows (flex-grow > 0)');
  assert.match(view, /margin\s*:[^;]*calc\(-1 \* max\(12px,env\(safe-area-inset-bottom\)\)\)/,
    '#view bleeds down to the bottom edge (full-bleed)');
});

test('#view is the last section of main: in a column, it touches the bottom', () => {
  const main = html.slice(html.indexOf('<main>'), html.indexOf('</main>'));
  const order = ['id="pictos"', 'id="trip"', 'id="view"'].map((id) => main.indexOf(id));
  assert.ok(order.every((at) => at !== -1), 'pictos, trip and view are in main');
  assert.ok(order[0] < order[2] && order[1] < order[2], '#view comes after pictos and trip');
});
