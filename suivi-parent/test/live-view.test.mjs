import test from 'node:test';
import assert from 'node:assert/strict';
import { frameOf, pictosOf, tripOf, tripPercent, tripRemaining, frozenFrameOf, freezeFrameOf, PHOTO_FRESH_MS, PICTO_FRESH_MS, DIRECT_FREEZE_MS } from '../live-view.mjs';

const now = 1_700_000_000_000;
const photo = { jpeg: 'AAAA', atMs: now - 1000 };
const pictos = { atMs: now - 1000, items: [{ label: 'medics', png: 'AQID' }] };
const trip = { destination: 'Gare de Lyon', totalM: 1200, remainingM: 300, remainingS: 240, progress: 0.75 };

test('the camera shows while the loved one runs it, data URL included', () => {
  const view = frameOf({ photo }, 'live', now);
  assert.equal(view.src, 'data:image/jpeg;base64,AAAA');
});

test('camera off: the page shows neither a kept picture nor an old one', () => {
  assert.equal(frameOf({}, 'live', now), null);
  assert.equal(frameOf({ photo: { jpeg: '' } }, 'live', now), null);
  assert.equal(frameOf({ photo: { jpeg: 'AAAA' } }, 'live', now), null);
  // The relay keeps the last frame after a drop: it never shows.
  assert.equal(frameOf({ photo }, 'lost', now), null);
  assert.equal(frameOf({ photo }, 'offline', now), null);
  // A frame over 8 s old no longer proves a running camera.
  assert.equal(frameOf({ photo }, 'live', now + PHOTO_FRESH_MS + 1), null);
});

test('camera-off announced: even a fresh frame no longer shows', () => {
  assert.equal(frameOf({ photo }, 'live', now, true), null);
  assert.equal(frameOf({ photo }, 'live', now, false).src, 'data:image/jpeg;base64,AAAA');
});

test('landmarks show as drawings, data URL included', () => {
  const items = pictosOf({ pictos }, 'live', now);
  assert.equal(items.length, 1);
  assert.equal(items[0].src, 'data:image/png;base64,AQID');
  assert.equal(items[0].label, 'medics');
});

test('missing, stale or empty landmarks: nothing shows', () => {
  assert.equal(pictosOf({}, 'live', now), null);
  assert.equal(pictosOf({ pictos }, 'lost', now), null);
  assert.equal(pictosOf({ pictos }, 'offline', now), null);
  assert.equal(pictosOf({ pictos }, 'live', now + PICTO_FRESH_MS + 1), null);
  assert.equal(pictosOf({ pictos: { atMs: now, items: [] } }, 'live', now), null);
  assert.equal(pictosOf({ pictos: { atMs: now, items: [{ label: '', png: '' }] } }, 'live', now), null);
});

test('the bar follows the guided trip, from start to destination', () => {
  const current = tripOf({ trip }, 'live');
  assert.deepEqual(current, trip);
  assert.equal(tripPercent(current), 75);
  assert.equal(tripRemaining(current), '300 m to go · 4 min');
});

test('the bar goes away outside guidance, on drop, and never leaves its bounds', () => {
  assert.equal(tripOf({ fix: {} }, 'live'), null);
  assert.equal(tripOf({ trip }, 'offline'), null);
  assert.equal(tripOf({ trip: { destination: '  ' } }, 'live'), null);
  assert.equal(tripPercent({ progress: 4 }), 100);
  assert.equal(tripPercent({ progress: -2 }), 0);
  assert.equal(tripPercent({}), 0);
});

test('live micro-drop: the painted frame stays frozen during the grace', () => {
  assert.equal(DIRECT_FREEZE_MS, 2_500);
  assert.equal(frozenFrameOf(now - 1000, now), true);
  assert.equal(frozenFrameOf(now - DIRECT_FREEZE_MS, now), false);
  assert.equal(frozenFrameOf(now - DIRECT_FREEZE_MS - 1, now), false);
  // Never painted: nothing to freeze, the page clears as before.
  assert.equal(frozenFrameOf(0, now), false);
});

test('live freeze: no view always freezes, slow photo only when live was showing', () => {
  const young = now - 1000, old = now - DIRECT_FREEZE_MS - 1;
  const slow = { src: 'data:image/jpeg;base64,BBBB' };
  assert.equal(freezeFrameOf('blob:x', young, true, null, now), true);
  assert.equal(freezeFrameOf('blob:x', young, true, slow, now), true);
  // Slow photo alone: its normal refresh is never blocked.
  assert.equal(freezeFrameOf('data:y', young, false, slow, now), false);
  // … but a bogus camera notice never clears it during the grace.
  assert.equal(freezeFrameOf('data:y', young, false, null, now), true);
  assert.equal(freezeFrameOf('blob:x', old, true, null, now), false);
  assert.equal(freezeFrameOf('', young, true, null, now), false);
});

test('the remaining trip reads in meters, kilometers and hours', () => {
  assert.equal(tripRemaining({ remainingM: 0, remainingS: 20 }), '0 m to go · under a minute');
  assert.equal(tripRemaining({ remainingM: 0, remainingS: 90 }), '0 m to go · 2 min');
  assert.equal(tripRemaining({ remainingM: 2450, remainingS: 0 }), '2.5 km to go');
  assert.equal(tripRemaining({ remainingM: 9000, remainingS: 3_600 }), '9 km to go · 1h 00');
  assert.equal(tripRemaining({}), '');
});
