import test from 'node:test';
import assert from 'node:assert/strict';
import { STREAM_FRESH_MS, streamUrl, frameSrcOf, cameraOnOf, watchStream } from '../live-stream.mjs';

test('the stream stays on the same origin, wss when the page is https', () => {
  assert.equal(
    streamUrl('https://relais.example/?share=leo'),
    'wss://relais.example/ws?share=leo&role=watch',
  );
  assert.equal(
    streamUrl('http://127.0.0.1:8836/?share=leo&x=1'),
    'ws://127.0.0.1:8836/ws?share=leo&role=watch',
  );
  assert.equal(STREAM_FRESH_MS, 600);
});

test('an empty frame is not a picture', () => {
  const create = (blob) => ({ blob });
  assert.equal(frameSrcOf(null, create), null);
  assert.equal(frameSrcOf(new Uint8Array([]), create), null);
  const view = frameSrcOf(new Uint8Array([1, 2]), create);
  assert.equal(view.blob.type, 'image/jpeg');
  assert.equal(view.blob.size, 2);
});

test('a socket Blob passes through as-is, with no copy', () => {
  const create = (blob) => ({ blob });
  const blob = new Blob([new Uint8Array([1, 2, 3])], { type: 'image/jpeg' });
  const view = frameSrcOf(blob, create);
  assert.equal(view.blob, blob);
  assert.equal(frameSrcOf(new Blob([]), create), null);
});

/** Fake tab: events fire by hand. */
function fakeSocket(url) {
  return {
    url,
    binaryType: 'blob',
    closed: false,
    close() { this.closed = true; this.onclose && this.onclose(); },
  };
}

function setup() {
  const sockets = [];
  const frames = [];
  const cameras = [];
  const states = [];
  const timers = [];
  const opened = [];
  const revoked = [];
  const stream = watchStream({
    href: 'https://relais.example/?share=leo',
    onFrame: (src) => frames.push(src),
    onState: (state) => states.push(state),
    onCamera: (on) => cameras.push(on),
    open: (url) => { const socket = fakeSocket(url); sockets.push(socket); opened.push(url); return socket; },
    create: () => 'blob:' + frames.length,
    revoke: (src) => revoked.push(src),
    timer: {
      set: (fn, ms) => { timers.push({ fn, ms }); return timers.length - 1; },
      clear: () => {},
    },
  });
  return { stream, sockets, frames, states, cameras, timers, opened, revoked };
}

test('the caregiver opens the stream of the right id and goes live', () => {
  const { sockets, states, opened } = setup();
  assert.deepEqual(opened, ['wss://relais.example/ws?share=leo&role=watch']);
  assert.equal(sockets[0].binaryType, 'blob');
  sockets[0].onopen();
  assert.deepEqual(states, ['live']);
});

test('every binary frame becomes a picture, text is ignored', () => {
  const { sockets, frames } = setup();
  sockets[0].onopen();
  sockets[0].onmessage({ data: new Blob([new Uint8Array([1, 2, 3])]) });
  sockets[0].onmessage({ data: 'audience' });
  sockets[0].onmessage({ data: new Blob([]) });
  assert.equal(frames.length, 1);
  assert.equal(frames[0], 'blob:0');
});

test('only the last two blobs survive, the one before is revoked', () => {
  const { sockets, revoked } = setup();
  sockets[0].onopen();
  const frame = () => sockets[0].onmessage({ data: new Blob([new Uint8Array([1])]) });
  frame();
  frame();
  assert.deepEqual(revoked, []);
  frame();
  assert.deepEqual(revoked, ['blob:0']);
  frame();
  assert.deepEqual(revoked, ['blob:0', 'blob:1']);
});

test('a drop falls back to the slow state then retries, with a growing delay', () => {
  const { sockets, states, timers } = setup();
  sockets[0].onopen();
  sockets[0].onclose();
  assert.deepEqual(states, ['live', 'idle']);
  assert.equal(timers.length, 1);
  assert.equal(timers[0].ms, 500);
});

test('closing the stream restarts no connection', () => {
  const { stream, sockets, timers } = setup();
  sockets[0].onopen();
  stream.close();
  assert.equal(timers.length, 0);
  assert.equal(sockets[0].closed, true);
});

test('the camera notice reads strictly, the rest is ignored', () => {
  assert.equal(cameraOnOf('{"type":"camera","on":true}'), true);
  assert.equal(cameraOnOf('{"type":"camera","on":false}'), false);
  assert.equal(cameraOnOf('{"type":"audience","watch":1}'), null);
  assert.equal(cameraOnOf('{"type":"camera","on":"yes"}'), null);
  assert.equal(cameraOnOf('audience'), null);
  assert.equal(cameraOnOf(null), null);
});

test('the camera notice tells the page, other text is ignored', () => {
  const { sockets, cameras, frames } = setup();
  sockets[0].onopen();
  sockets[0].onmessage({ data: '{"type":"camera","on":false}' });
  sockets[0].onmessage({ data: 'audience' });
  sockets[0].onmessage({ data: '{"type":"camera","on":true}' });
  assert.deepEqual(cameras, [false, true]);
  assert.equal(frames.length, 0);
});

test('without a stream id, no connection is ever attempted', () => {
  const opened = [];
  const stream = watchStream({
    href: 'https://relais.example/',
    onFrame: () => assert.fail('no frame expected'),
    open: (url) => { opened.push(url); return fakeSocket(url); },
    timer: { set: () => 0, clear: () => {} },
  });
  assert.deepEqual(opened, []);
  stream.close();
});
