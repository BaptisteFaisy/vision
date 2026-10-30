import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const css = readFileSync(join(ROOT, 'styles.css'), 'utf8');
const html = readFileSync(join(ROOT, 'index.html'), 'utf8');

/** Premier bloc `selecteur { ... }` de la feuille (hors media queries). */
function rule(selector) {
  const at = css.indexOf(selector + ' {');
  assert.notEqual(at, -1, selector + ' a un bloc de style');
  return css.slice(at, css.indexOf('}', at));
}

test('la caméra reste collée en bas même quand pictos et trajet sont cachés', () => {
  // Une grille à N rangées explicites range #view dans la 2e rangée (auto) dès
  // qu'une section se cache, et la rangée 1.3fr vide reste blanche en dessous.
  // En colonne flex, les sections cachées s'effacent et la dernière remplit.
  const main = rule('main');
  assert.match(main, /display\s*:\s*flex/, 'main est en flex');
  assert.match(main, /flex-direction\s*:\s*column/, 'main est en colonne');
  assert.doesNotMatch(main, /grid-template-rows/, 'aucune rangée explicite');
});

test('la caméra remplit jusqu’au bord bas quand elle s’affiche', () => {
  const view = rule('#view');
  const grow = view.match(/flex\s*:\s*([0-9]+)/);
  assert.ok(grow && Number(grow[1]) > 0, '#view grandit (flex-grow > 0)');
  assert.match(view, /margin\s*:[^;]*calc\(-1 \* max\(12px,env\(safe-area-inset-bottom\)\)\)/,
    '#view déborde jusqu’au bord bas (plein-bleed)');
});

test('#view est la dernière section du main : en colonne, elle touche le bas', () => {
  const main = html.slice(html.indexOf('<main>'), html.indexOf('</main>'));
  const order = ['id="pictos"', 'id="trip"', 'id="view"'].map((id) => main.indexOf(id));
  assert.ok(order.every((at) => at !== -1), 'pictos, trip et view sont dans main');
  assert.ok(order[0] < order[2] && order[1] < order[2], '#view est après pictos et trip');
});
