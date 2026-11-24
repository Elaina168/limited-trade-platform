import { readAgentStream, StreamProtocolError } from './sse.js';

export class ApiError extends Error {
  constructor(status, retryAfter, path, traceId) { super(`HTTP ${status}`); this.status = status; this.retryAfter = retryAfter; this.path = path; this.traceId = traceId; }
}
export class UserError extends Error {}
class ConnectionError extends Error {}
class ResultUnavailableError extends UserError {}
export function resultUnavailable(error) {
  return error instanceof ConnectionError || error instanceof ResultUnavailableError
    || error instanceof ApiError && error.status >= 500;
}
let expired = () => {};
let sessionGeneration = 0;
export function advanceSession() { sessionGeneration++; }
export function onSessionExpired(callback) { expired = callback; }

async function request(path, options = {}) {
  const generation = sessionGeneration;
  let response;
  try { response = await fetch(path, { credentials: 'same-origin', cache: 'no-store', ...options }); }
  catch (error) {
    if (error instanceof TypeError) throw new ConnectionError('无法连接服务器，请检查网络后重试。');
    throw error;
  }
  if (!response.ok) {
    if (response.status === 401 && generation === sessionGeneration && path !== '/api/auth/me' && path !== '/api/auth/login') expired();
    throw new ApiError(response.status, response.headers.get('Retry-After'), path, response.headers.get('X-Trace-Id'));
  }
  if (path === '/api/auth/login' || path === '/api/auth/logout') advanceSession();
  return response;
}

export async function api(path, options = {}) {
  const response = await request(path, options);
  if (response.status === 204) return null;
  try { return await response.json(); }
  catch (error) {
    if (error instanceof SyntaxError || error instanceof TypeError) throw new ResultUnavailableError('服务器返回的数据无法读取。');
    throw error;
  }
}

export async function write(path, method = 'POST', data, form = false, signal) {
  const generation = sessionGeneration;
  // 登录和退出会轮换CSRF令牌，每次写入重新获取，不自动重放写请求。
  const csrf = await api('/api/auth/csrf', { signal });
  if (generation !== sessionGeneration) throw new UserError('账号状态已经变化，请在当前账号下重新操作。');
  const headers = { [csrf.headerName]: csrf.token };
  let body;
  if (data !== undefined) {
    headers['Content-Type'] = form ? 'application/x-www-form-urlencoded;charset=UTF-8' : 'application/json';
    body = form ? new URLSearchParams(data).toString() : JSON.stringify(data);
  }
  return api(path, { method, headers, body, signal });
}

export async function streamWrite(path, data, onEvent, signal) {
  const generation = sessionGeneration;
  const check = () => {
    if (signal?.aborted) throw new DOMException('请求已停止', 'AbortError');
    if (generation !== sessionGeneration) throw new UserError('账号状态已经变化，请重新登录后操作。');
  };
  const csrf = await api('/api/auth/csrf', { signal });
  check();
  const response = await request(path, { method: 'POST', signal,
    headers: { [csrf.headerName]: csrf.token, 'Content-Type': 'application/json', Accept: 'text/event-stream' }, body: JSON.stringify(data) });
  check();
  if (!response.headers.get('Content-Type')?.includes('text/event-stream') || !response.body) throw new ResultUnavailableError('助手当前无法提供流式回答。');
  try { return await readAgentStream(response.body, (name, payload) => { check(); onEvent(name, payload); }, signal); }
  catch (error) {
    if (error instanceof StreamProtocolError) throw new ResultUnavailableError(error.message);
    if (error instanceof TypeError) throw new ResultUnavailableError('连接中断，回答是否完成尚未确认。');
    throw error;
  }
}

export function errorMessage(error) {
  if (error instanceof UserError || error instanceof ConnectionError) return error.message;
  if (!(error instanceof ApiError)) throw error;
  const messages = {
    400: '填写内容不符合要求，请检查格式、数值或时间范围。',
    401: '尚未登录、登录已过期，或用户名和密码不正确。',
    403: '服务器拒绝了请求：没有操作权限，或页面凭据已失效。',
    404: '目标记录不存在或不属于当前账号。',
    409: '当前状态不允许操作：请检查库存、活动名额、券的期限和门槛，或订单状态。',
    429: `操作较频繁，请${error.retryAfter ? `等待 ${error.retryAfter} 秒后` : '稍后'}重试。`,
    503: '服务暂不可用，请稍后重试。'
  };
  const message = messages[error.status] || `请求未完成（HTTP ${error.status}），请稍后重试。`;
  return error.traceId ? `${message}追踪编号：${error.traceId}` : message;
}

const intentKey = userId => `limited-market:order-intent:${userId}`;
export function pendingOrder(userId) {
  const saved = sessionStorage.getItem(intentKey(userId));
  return saved ? JSON.parse(saved) : null;
}
const orderRequests = new Set();

async function submitOrder(userId, intent, resuming) {
  const generation = sessionGeneration;
  orderRequests.add(userId);
  try {
    for (let attempt = 0; ; attempt++) {
      try {
        const order = await write(intent.path, 'POST', { ...intent.data, requestId: intent.requestId });
        sessionStorage.removeItem(intentKey(userId));
        return order;
      } catch (error) {
        // 自动核对只复用原来的请求标识和购买参数。
        if (!attempt && !resuming && resultUnavailable(error) && navigator.onLine && generation === sessionGeneration) {
          resuming = true;
          continue;
        }
        // 恢复请求只有在业务入口完成幂等判断后的404/409才能解除待确认状态。
        if (error instanceof ApiError && error.status >= 400 && error.status < 500
            && (!resuming || error.path === intent.path && [404, 409].includes(error.status))) {
          sessionStorage.removeItem(intentKey(userId));
        }
        throw error;
      }
    }
  } finally {
    orderRequests.delete(userId);
  }
}

export function resumeOrder(userId) {
  const intent = pendingOrder(userId);
  return intent && !orderRequests.has(userId) ? submitOrder(userId, intent, true) : null;
}

export function placeOrder(userId, path, data) {
  if (pendingOrder(userId)) throw new UserError('上一笔下单还在确认，确认后可继续下单。');
  const requestId = Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, '0')).join('');
  const intent = { path, data, requestId };
  sessionStorage.setItem(intentKey(userId), JSON.stringify(intent));
  return submitOrder(userId, intent, false);
}
