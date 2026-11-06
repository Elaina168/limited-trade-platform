// 仅用于本机页面契约验证，不模拟真实数据库或模型验收。
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve, extname, sep } from 'node:path';
import { randomUUID } from 'node:crypto';

const root = resolve('src/main/resources/static');
const conversations = new Map();
let opened = 0, closed = 0;
function json(response, status, data) { response.writeHead(status, { 'Content-Type': 'application/json' }); response.end(JSON.stringify(data)); }
const server = createServer(async (request, response) => {
  const url = new URL(request.url, 'http://127.0.0.1');
  const owner = /fixture-user=(\d+)/.exec(request.headers.cookie || '')?.[1];
  let raw = ''; for await (const part of request) raw += part;
  if (url.pathname === '/api/auth/csrf') return json(response, 200, { headerName: 'X-CSRF-TOKEN', token: 'fixture-csrf' });
  if (url.pathname === '/api/auth/login') {
    const name = new URLSearchParams(raw).get('username');
    response.setHeader('Set-Cookie', `fixture-user=${name.includes('two') ? 2 : 1}; Path=/; HttpOnly; SameSite=Lax`);
    response.writeHead(204); return response.end();
  }
  if (url.pathname === '/api/auth/logout') {
    response.setHeader('Set-Cookie', 'fixture-user=; Path=/; Max-Age=0'); response.writeHead(204); return response.end();
  }
  if (url.pathname === '/api/auth/me') return json(response, owner ? 200 : 401,
    owner ? { id: Number(owner), username: owner === '1' ? 'page_buyer_one' : 'page_buyer_two', role: 'USER' } : {});
  if (url.pathname === '/__fixture/state') return json(response, 200, { opened, closed, conversations: conversations.size });
  if (url.pathname.startsWith('/api/agent/')) {
    if (!owner) return json(response, 401, {});
    if (request.headers['x-csrf-token'] !== 'fixture-csrf') return json(response, 403, {});
    if (url.pathname === '/api/agent/conversations') {
      const id = randomUUID(); conversations.set(id, owner); return json(response, 201, { conversationId: id, expiresAt: '2026-11-07T12:00:00' });
    }
    const id = url.pathname.split('/')[4];
    if (conversations.get(id) !== owner) return json(response, 404, {});
    if (request.method === 'DELETE') { response.writeHead(204); return response.end(); }
    if (url.pathname.endsWith('/stream')) {
      const { message } = JSON.parse(raw);
      if (message.includes('冲突')) return json(response, 409, {});
      response.writeHead(200, { 'Content-Type': 'text/event-stream', 'X-Accel-Buffering': 'no' });
      const emit = (name, data) => response.write(`event:${name}\ndata:${JSON.stringify(data)}\n\n`);
      opened++;
      emit('start', {});
      const answer = message.includes('脚本') ? '<img src=x onerror="alert(1)"> 这只是文本。' : '订单查询已完成。支付期限以实际订单为准。';
      let index = 0;
      const timer = setInterval(() => {
        index++;
        if (message.includes('中断') && index === 2) { clearInterval(timer); response.end(); return; }
        if (index === 1) { emit('delta', { round: 1, text: '正在查询临时内容' }); return; }
        if (index === 2) { emit('reset', { round: 1, text: '' }); return; }
        emit('delta', { round: 2, text: answer.slice(index - 3, index - 2) });
        if (index >= answer.length + 2) {
          clearInterval(timer);
          emit('done', { answer, sources: [], knowledgeSources: [{ id: 'fixture-payment', documentId: 'payment',
            title: '支付期限', path: 'knowledge/payment.md', startLine: 4, endLine: 4,
            text: '创建订单后15分钟内支付，实际截止时间以expiresAt为准。', score: 0.8 }] });
          response.end();
        }
      }, message.includes('慢速') ? 400 : 70);
      response.on('close', () => { clearInterval(timer); closed++; });
      return;
    }
  }
  if (url.pathname.startsWith('/api/products')) return json(response, 200, { page: 1, size: 12, total: 0, items: [] });
  if (url.pathname === '/api/categories') return json(response, 200, []);
  const path = resolve(root, '.' + (url.pathname === '/' ? '/index.html' : decodeURIComponent(url.pathname)));
  if (!path.startsWith(root + sep)) return json(response, 404, {});
  try {
    const content = await readFile(path);
    response.writeHead(200, { 'Content-Type': ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' })[extname(path)] || 'application/octet-stream' });
    response.end(content);
  } catch (error) {
    if (error.code === 'ENOENT') return json(response, 404, {});
    console.error(error); json(response, 500, {});
  }
});
server.listen(18737, '127.0.0.1', () => console.log('Page contract fixture: http://127.0.0.1:18737/#agent'));
