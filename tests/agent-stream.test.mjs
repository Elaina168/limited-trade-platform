import test from 'node:test';
import assert from 'node:assert/strict';
import { readAgentStream, StreamProtocolError, StreamEventError } from '../src/main/resources/static/assets/sse.js';

const result = { answer: '中文回答', sources: [], knowledgeSources: [] };
const event = (name, data, nl = '\n') => `event:${name}${nl}data:${JSON.stringify(data)}${nl}${nl}`;
function bytes(text, split = false) {
  const raw = new TextEncoder().encode(text);
  return new ReadableStream({ start(controller) {
    if (split) for (const byte of raw) controller.enqueue(Uint8Array.of(byte));
    else controller.enqueue(raw);
    controller.close();
  } });
}

test('UTF-8 and CRLF survive single-byte chunks with reset and authoritative done', async () => {
  const seen = [];
  const text = ':心跳\r\n\r\n' + event('start', {}, '\r\n')
    + event('delta', { round: 1, text: '临时中文' }, '\r\n')
    + event('reset', { round: 1, text: '' }, '\r\n')
    + event('delta', { round: 2, text: '中文回答' }, '\r\n') + event('done', result, '\r\n');
  assert.deepEqual(await readAgentStream(bytes(text, true), (name, data) => seen.push([name, data])), result);
  assert.deepEqual(seen.map(x => x[0]), ['start', 'delta', 'reset', 'delta', 'done']);
  assert.equal(seen[1][1].text, '临时中文');
});

test('multiple data lines form one JSON payload', async () => {
  const text = 'event:done\ndata:{"answer":"中文回答",\ndata:"sources":[],"knowledgeSources":[]}\n\n';
  assert.deepEqual(await readAgentStream(bytes(text), () => {}), result);
});

test('error, missing done, malformed data and unknown event are failures', async () => {
  await assert.rejects(readAgentStream(bytes(event('error', { code: 409, message: '会话正在结束' })), () => {}),
    error => error instanceof StreamEventError && error.status === 409);
  for (const text of [event('delta', { round: 1, text: '半轮' }), 'event:done\ndata:broken\n\n', event('other', {}), event('done', { answer: '缺来源' })]) {
    await assert.rejects(readAgentStream(bytes(text), () => {}), StreamProtocolError);
  }
});

test('abort cancels a pending reader instead of reporting normal EOF', async () => {
  const controller = new AbortController();
  let cancelled = false;
  const body = new ReadableStream({ cancel() { cancelled = true; } });
  const pending = readAgentStream(body, () => {}, controller.signal);
  controller.abort();
  await assert.rejects(pending, error => error.name === 'AbortError');
  assert.equal(cancelled, true);
});
