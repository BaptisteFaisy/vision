import test from 'node:test';
import assert from 'node:assert/strict';
import { frameOf, tripOf, tripPercent, tripRemaining, PHOTO_FRESH_MS } from '../live-view.mjs';

const now = 1_700_000_000_000;
const photo = { jpeg: 'AAAA', atMs: now - 1000 };
const trip = { destination: 'Gare de Lyon', totalM: 1200, remainingM: 300, remainingS: 240, progress: 0.75 };

test('la caméra s’affiche quand le proche la fait tourner, data URL comprise', () => {
  const view = frameOf({ photo }, 'live', now);
  assert.equal(view.src, 'data:image/jpeg;base64,AAAA');
});

test('caméra éteinte : la page n’affiche ni image conservée ni image ancienne', () => {
  assert.equal(frameOf({}, 'live', now), null);
  assert.equal(frameOf({ photo: { jpeg: '' } }, 'live', now), null);
  assert.equal(frameOf({ photo: { jpeg: 'AAAA' } }, 'live', now), null);
  // Le relais garde la dernière frame après une coupure : elle ne s'affiche pas.
  assert.equal(frameOf({ photo }, 'lost', now), null);
  assert.equal(frameOf({ photo }, 'offline', now), null);
  // Une frame de plus de 8 s ne prouve plus une caméra allumée.
  assert.equal(frameOf({ photo }, 'live', now + PHOTO_FRESH_MS + 1), null);
});

test('la barre suit le trajet guidé, du départ à l’arrivée', () => {
  const current = tripOf({ trip }, 'live');
  assert.deepEqual(current, trip);
  assert.equal(tripPercent(current), 75);
  assert.equal(tripRemaining(current), 'Encore 300 m · 4 min');
});

test('la barre disparaît hors guidage, à la coupure, et ne dépasse jamais ses bornes', () => {
  assert.equal(tripOf({ fix: {} }, 'live'), null);
  assert.equal(tripOf({ trip }, 'offline'), null);
  assert.equal(tripOf({ trip: { destination: '  ' } }, 'live'), null);
  assert.equal(tripPercent({ progress: 4 }), 100);
  assert.equal(tripPercent({ progress: -2 }), 0);
  assert.equal(tripPercent({}), 0);
});

test('le reste du trajet se dit en mètres, kilomètres et heures', () => {
  assert.equal(tripRemaining({ remainingM: 0, remainingS: 20 }), "Encore 0 m · moins d'une minute");
  assert.equal(tripRemaining({ remainingM: 0, remainingS: 90 }), 'Encore 0 m · 2 min');
  assert.equal(tripRemaining({ remainingM: 2450, remainingS: 0 }), 'Encore 2,5 km');
  assert.equal(tripRemaining({ remainingM: 9000, remainingS: 3_600 }), 'Encore 9 km · 1 h 00');
  assert.equal(tripRemaining({}), '');
});
