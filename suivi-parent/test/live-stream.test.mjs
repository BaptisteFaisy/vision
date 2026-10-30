import test from 'node:test';
import assert from 'node:assert/strict';
import { STREAM_FRESH_MS, streamUrl, frameSrcOf, cameraOnOf, watchStream } from '../live-stream.mjs';

test('le flux est sur la même origine, en wss quand la page est en https', () => {
  assert.equal(
    streamUrl('https://relais.example/?share=leo'),
    'wss://relais.example/ws?share=leo&role=watch',
  );
  assert.equal(
    streamUrl('http://127.0.0.1:8836/?share=leo&x=1'),
    'ws://127.0.0.1:8836/ws?share=leo&role=watch',
  );
  assert.equal(STREAM_FRESH_MS, 1_500);
});

test('une frame vide n’est pas une image', () => {
  const create = (blob) => ({ blob });
  assert.equal(frameSrcOf(null, create), null);
  assert.equal(frameSrcOf(new Uint8Array([]), create), null);
  const view = frameSrcOf(new Uint8Array([1, 2]), create);
  assert.equal(view.blob.type, 'image/jpeg');
  assert.equal(view.blob.size, 2);
});

/** Onglet factice : on déclenche les évènements à la main. */
function fauxSocket(url) {
  return {
    url,
    binaryType: 'blob',
    closed: false,
    close() { this.closed = true; this.onclose && this.onclose(); },
  };
}

function montage() {
  const sockets = [];
  const frames = [];
  const cameras = [];
  const etats = [];
  const timers = [];
  const ouvertures = [];
  const flux = watchStream({
    href: 'https://relais.example/?share=leo',
    onFrame: (src) => frames.push(src),
    onState: (etat) => etats.push(etat),
    onCamera: (on) => cameras.push(on),
    open: (url) => { const socket = fauxSocket(url); sockets.push(socket); ouvertures.push(url); return socket; },
    create: () => 'blob:' + frames.length,
    revoke: () => {},
    timer: {
      set: (fn, ms) => { timers.push({ fn, ms }); return timers.length - 1; },
      clear: () => {},
    },
  });
  return { flux, sockets, frames, etats, cameras, timers, ouvertures };
}

test('l’aidant ouvre le flux du bon identifiant et passe en direct', () => {
  const { sockets, etats, ouvertures } = montage();
  assert.deepEqual(ouvertures, ['wss://relais.example/ws?share=leo&role=watch']);
  assert.equal(sockets[0].binaryType, 'arraybuffer');
  sockets[0].onopen();
  assert.deepEqual(etats, ['live']);
});

test('chaque frame binaire devient une image, un texte est ignoré', () => {
  const { sockets, frames } = montage();
  sockets[0].onopen();
  sockets[0].onmessage({ data: new Uint8Array([1, 2, 3]) });
  sockets[0].onmessage({ data: 'audience' });
  sockets[0].onmessage({ data: new Uint8Array([]) });
  assert.equal(frames.length, 1);
  assert.equal(frames[0], 'blob:0');
});

test('une coupure repasse en état lent puis retente, avec un délai qui grandit', () => {
  const { sockets, etats, timers } = montage();
  sockets[0].onopen();
  sockets[0].onclose();
  assert.deepEqual(etats, ['live', 'idle']);
  assert.equal(timers.length, 1);
  assert.equal(timers[0].ms, 500);
});

test('fermer le flux ne relance aucune connexion', () => {
  const { flux, sockets, timers } = montage();
  sockets[0].onopen();
  flux.close();
  assert.equal(timers.length, 0);
  assert.equal(sockets[0].closed, true);
});

test('l’avis caméra se lit strictement, le reste est ignoré', () => {
  assert.equal(cameraOnOf('{"type":"camera","on":true}'), true);
  assert.equal(cameraOnOf('{"type":"camera","on":false}'), false);
  assert.equal(cameraOnOf('{"type":"audience","watch":1}'), null);
  assert.equal(cameraOnOf('{"type":"camera","on":"oui"}'), null);
  assert.equal(cameraOnOf('audience'), null);
  assert.equal(cameraOnOf(null), null);
});

test('l’avis caméra prévient la page, un autre texte est ignoré', () => {
  const { sockets, cameras, frames } = montage();
  sockets[0].onopen();
  sockets[0].onmessage({ data: '{"type":"camera","on":false}' });
  sockets[0].onmessage({ data: 'audience' });
  sockets[0].onmessage({ data: '{"type":"camera","on":true}' });
  assert.deepEqual(cameras, [false, true]);
  assert.equal(frames.length, 0);
});

test('sans identifiant de flux, aucune connexion n’est tentée', () => {
  const ouvertures = [];
  const flux = watchStream({
    href: 'https://relais.example/',
    onFrame: () => assert.fail('aucune frame attendue'),
    open: (url) => { ouvertures.push(url); return fauxSocket(url); },
    timer: { set: () => 0, clear: () => {} },
  });
  assert.deepEqual(ouvertures, []);
  flux.close();
});
