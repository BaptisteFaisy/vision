import test from 'node:test';
import assert from 'node:assert/strict';
import { shareIdOf, stateOf, movementBetween, anchorAfter, narrationOf, neverPublished, NO_STREAM_TEXT } from '../session.mjs';
import { wordsSpoken } from '../spoken-line.mjs';

const fix = { lat: 48.85, lon: 2.35, accM: 1, atMs: 1000, headingDeg: 0, indoor: false };

test('the received link picks its stream; no local or default stream', () => {
  assert.equal(shareIdOf('https://example.com/?share=loved-b'), 'loved-b');
  for (const url of ['https://example.com/', 'https://example.com/?share=Loved', 'javascript:alert(1)', 'invalid']) {
    assert.equal(shareIdOf(url), null);
  }
});

test('the stream expires in steps even when the last packet stays in memory', () => {
  const live = { serverAt: 1000, objects: [{ label: 'door', distM: 2, angleDeg: 0 }] };
  assert.equal(stateOf(live, 12_999), 'live');
  assert.equal(stateOf(live, 13_000), 'lost');
  assert.equal(stateOf(live, 121_000), 'offline');
  assert.equal(stateOf(null), 'offline');
});

test('a measured move tells forward from sideways apart', () => {
  assert.match(movementBetween(fix, { ...fix, lat: fix.lat + 10 / 111320, atMs: 11_000 }), /Moved forward.*10 m/);
  const east = fix.lon + 10 / (111320 * Math.cos(fix.lat * Math.PI / 180));
  assert.match(movementBetween(fix, { ...fix, lon: east, atMs: 11_000 }), /right.*10 m/);
});

test('GPS noise, jumps and reconnects never become invented moves', () => {
  const moved = { ...fix, lat: fix.lat + 10 / 111320, atMs: 11_000 };
  assert.equal(movementBetween({ ...fix, accM: 15 }, moved), '');
  assert.equal(movementBetween(fix, { ...moved, indoor: true }), '');
  assert.equal(movementBetween(fix, { ...moved, atMs: 1001 }), '');
  assert.equal(movementBetween(fix, { ...moved, atMs: 40_000 }), '');
});

test('without a position, the move reference clears instead of inventing a move', () => {
  assert.equal(anchorAfter(null, '', fix), fix);
  assert.equal(anchorAfter(fix, '', null), null);
  assert.equal(anchorAfter(null, '', null), null);
  // A fresh fix keeps the anchor to measure the move, an old one moves it.
  assert.equal(anchorAfter(fix, '', { ...fix, atMs: fix.atMs + 1_000 }), fix);
  const old = { ...fix, atMs: fix.atMs + 31_000 };
  assert.equal(anchorAfter(fix, '', old), old);
  // A measured move shifts the anchor onto the current fix.
  const measured = { ...fix, atMs: fix.atMs + 1_000 };
  assert.equal(anchorAfter(fix, 'Moved forward.', measured), measured);
});

test('the text comes from the shared phone, and offline states stop announcing it', () => {
  const live = { scene: { text: 'Door on the right.' }, objects: [] };
  assert.equal(narrationOf(live, 'live', 'Moved forward.'), 'Door on the right. Moved forward.');
  assert.match(narrationOf(live, 'lost'), /Signal lost/);
  assert.match(narrationOf(live, 'offline'), /Waiting for/);
  assert.match(narrationOf(live, 'live', '', false), /tracking link/);
});

test('the word-by-word highlight follows the Portrait cadence, then ends the sentence', () => {
  assert.equal(wordsSpoken('An obstacle ahead of you', 0), 0);
  assert.equal(wordsSpoken('An obstacle ahead of you', 151), 1);
  assert.equal(wordsSpoken('An obstacle ahead of you', 2000), 5);
});

test('a link with no stream is said out loud, instead of waiting forever', () => {
  // The relay answers the same for an unknown id and for a silent stream:
  // only `serverAt` tells an existing stream from a never-published id.
  assert.equal(neverPublished({ ok: true, live: false }), true);
  assert.equal(neverPublished({ ok: true, live: false, serverAt: 1000 }), false);
  assert.equal(neverPublished({ ok: true, live: true, serverAt: 1000 }), false);
  assert.equal(neverPublished(null), false);
  assert.equal(narrationOf(null, 'offline', '', true, false), NO_STREAM_TEXT);
  assert.match(NO_STREAM_TEXT, /reinstalled/);
  assert.match(narrationOf(null, 'offline', '', false, false), /tracking link/);
});
