import test from 'node:test';
import assert from 'node:assert/strict';
import { shareIdOf, stateOf, movementBetween, narrationOf, neverPublished, NO_STREAM_TEXT } from '../session.mjs';
import { wordsSpoken } from '../spoken-line.mjs';

const fix = { lat: 48.85, lon: 2.35, accM: 1, atMs: 1000, headingDeg: 0, indoor: false };

test('le lien reçu choisit son flux ; aucun flux local ou par défaut', () => {
  assert.equal(shareIdOf('https://example.com/?share=proche-b'), 'proche-b');
  for (const url of ['https://example.com/', 'https://example.com/?share=Proche', 'javascript:alert(1)', 'invalide']) {
    assert.equal(shareIdOf(url), null);
  }
});

test('le flux se périme par paliers même si le dernier paquet reste en mémoire', () => {
  const live = { serverAt: 1000, objects: [{ label: 'porte', distM: 2, angleDeg: 0 }] };
  assert.equal(stateOf(live, 12_999), 'live');
  assert.equal(stateOf(live, 13_000), 'lost');
  assert.equal(stateOf(live, 121_000), 'offline');
  assert.equal(stateOf(null), 'offline');
});

test('un déplacement mesuré distingue avance et décalage', () => {
  assert.match(movementBetween(fix, { ...fix, lat: fix.lat + 10 / 111320, atMs: 11_000 }), /A avancé.*10 m/);
  const east = fix.lon + 10 / (111320 * Math.cos(fix.lat * Math.PI / 180));
  assert.match(movementBetween(fix, { ...fix, lon: east, atMs: 11_000 }), /droite.*10 m/);
});

test('le bruit GPS, les sauts et la reconnexion ne deviennent pas des gestes inventés', () => {
  const moved = { ...fix, lat: fix.lat + 10 / 111320, atMs: 11_000 };
  assert.equal(movementBetween({ ...fix, accM: 15 }, moved), '');
  assert.equal(movementBetween(fix, { ...moved, indoor: true }), '');
  assert.equal(movementBetween(fix, { ...moved, atMs: 1001 }), '');
  assert.equal(movementBetween(fix, { ...moved, atMs: 40_000 }), '');
});

test('le texte vient du téléphone partagé et les états déconnectés arrêtent son annonce', () => {
  const live = { scene: { text: 'Porte à droite.' }, objects: [] };
  assert.equal(narrationOf(live, 'live', 'A avancé.'), 'Porte à droite. A avancé.');
  assert.match(narrationOf(live, 'lost'), /Signal perdu/);
  assert.match(narrationOf(live, 'offline'), /En attente/);
  assert.match(narrationOf(live, 'live', '', false), /lien de suivi/);
});

test('l’éclairage mot à mot suit la cadence Portrait, puis termine la phrase', () => {
  assert.equal(wordsSpoken('Un obstacle devant toi', 0), 0);
  assert.equal(wordsSpoken('Un obstacle devant toi', 151), 1);
  assert.equal(wordsSpoken('Un obstacle devant toi', 2000), 4);
});

test('un lien qui ne désigne aucun flux se dit, au lieu d’attendre à vie', () => {
  // Le relais répond la même chose pour un identifiant inconnu et pour un flux muet :
  // seul `serverAt` distingue un flux existant d’un identifiant jamais publié.
  assert.equal(neverPublished({ ok: true, live: false }), true);
  assert.equal(neverPublished({ ok: true, live: false, serverAt: 1000 }), false);
  assert.equal(neverPublished({ ok: true, live: true, serverAt: 1000 }), false);
  assert.equal(neverPublished(null), false);
  assert.equal(narrationOf(null, 'offline', '', true, false), NO_STREAM_TEXT);
  assert.match(NO_STREAM_TEXT, /réinstallée/);
  assert.match(narrationOf(null, 'offline', '', false, false), /lien de suivi/);
});
