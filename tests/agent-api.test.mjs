import test from 'node:test';
import assert from 'node:assert/strict';
import { streamWrite, advanceSession, onSessionExpired, ApiError, UserError } from '../src/main/resources/static/assets/api.js';

const csrf = () => new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'test-csrf' }), { headers: { 'Content-Type': 'application/json' } });
const done = 'event:done\ndata:{"answer":"完成","sources":[],"knowledgeSources":[]}\n\n';
test('stream POST sends current CSRF and same-origin credentials', async () => {
  const original = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (path, options) => {
    calls.push([path, options]);
    return calls.length === 1 ? csrf() : new Response(done, { headers: { 'Content-Type': 'text/event-stream' } });
  };
  try {
    const result = await streamWrite('/api/agent/conversations/id/messages/stream', { message: '问题' }, () => {});
    assert.equal(result.answer, '完成');
    assert.equal(calls[1][1].headers['X-CSRF-TOKEN'], 'test-csrf');
    assert.equal(calls[1][1].credentials, 'same-origin');
    assert.equal(calls[1][1].method, 'POST');
  } finally { globalThis.fetch = original; }
});

test('identity change during CSRF preparation prevents POST', async () => {
  const original = globalThis.fetch;
  let count = 0;
  globalThis.fetch = async () => { count++; advanceSession(); return csrf(); };
  try {
    await assert.rejects(streamWrite('/stream', {}, () => {}), UserError);
    assert.equal(count, 1);
  } finally { globalThis.fetch = original; }
});

test('401 invalidates current identity without replaying a request', async () => {
  const original = globalThis.fetch;
  let count = 0, expired = 0;
  onSessionExpired(() => { expired++; });
  globalThis.fetch = async () => ++count === 1 ? csrf() : new Response('', { status: 401 });
  try {
    await assert.rejects(streamWrite('/stream', {}, () => {}), error => error instanceof ApiError && error.status === 401);
    assert.equal(expired, 1);
    assert.equal(count, 2);
  } finally { globalThis.fetch = original; onSessionExpired(() => {}); }
});

test('identity change while reading suppresses later events', async () => {
  const original = globalThis.fetch;
  let calls = 0, events = 0;
  const bytes = new TextEncoder().encode('event:start\ndata:{}\n\n' + done);
  globalThis.fetch = async () => ++calls === 1 ? csrf() : new Response(bytes, { headers: { 'Content-Type': 'text/event-stream' } });
  try {
    await assert.rejects(streamWrite('/stream', {}, () => { events++; advanceSession(); }), UserError);
    assert.equal(events, 1);
  } finally { globalThis.fetch = original; }
});
