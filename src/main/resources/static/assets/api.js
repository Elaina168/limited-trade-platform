export class ApiError extends Error {
  constructor(status, retryAfter, path, traceId) { super(`HTTP ${status}`); this.status = status; this.retryAfter = retryAfter; this.path = path; this.traceId = traceId; }
}
export class UserError extends Error {}
class ConnectionError extends Error {}
let expired = () => {};
let sessionGeneration = 0;
export function advanceSession() { sessionGeneration++; }
export function onSessionExpired(callback) { expired = callback; }

export async function api(path, options = {}) {
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
  if (response.status === 204) return null;
  try { return await response.json(); }
  catch (error) {
    if (error instanceof SyntaxError) throw new UserError('服务器返回的数据无法读取，请稍后刷新。');
    throw error;
  }
}

export async function write(path, method = 'POST', data, form = false) {
  const generation = sessionGeneration;
  // 登录和退出会轮换CSRF令牌，每次写入重新获取，不自动重放写请求。
  const csrf = await api('/api/auth/csrf');
  if (generation !== sessionGeneration) throw new UserError('账号状态已经变化，请在当前账号下重新操作。');
  const headers = { [csrf.headerName]: csrf.token };
  let body;
  if (data !== undefined) {
    headers['Content-Type'] = form ? 'application/x-www-form-urlencoded;charset=UTF-8' : 'application/json';
    body = form ? new URLSearchParams(data).toString() : JSON.stringify(data);
  }
  return api(path, { method, headers, body });
}

export function errorMessage(error) {
  if (error instanceof UserError || error instanceof ConnectionError) return error.message;
  if (!(error instanceof ApiError)) throw error;
  const messages = {
    400: '填写内容不符合要求，请检查格式、数值或时间范围。',
    401: '尚未登录、登录已过期，或用户名和密码不正确。',
    403: '没有操作权限，或页面凭据已失效；请刷新后重试。',
    404: '目标记录不存在或不属于当前账号，请刷新列表。',
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
export function clearPendingOrder(userId) { sessionStorage.removeItem(intentKey(userId)); }
export async function placeOrder(userId, path, data) {
  let intent = pendingOrder(userId);
  const resuming = Boolean(intent);
  if (intent && (intent.path !== path || JSON.stringify(intent.data) !== JSON.stringify(data))) {
    throw new UserError('上一笔下单结果尚未确认，请先在订单页查询或使用原参数重试，避免重复购买。');
  }
  if (!intent) {
    const requestId = Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, '0')).join('');
    intent = { path, data, requestId };
    sessionStorage.setItem(intentKey(userId), JSON.stringify(intent));
  }
  try {
    const order = await write(intent.path, 'POST', { ...intent.data, requestId: intent.requestId });
    clearPendingOrder(userId);
    return order;
  } catch (error) {
    // 已有未知结果遇到认证/CSRF/限流拒绝时，尚未进入幂等查询，必须保留原标识。
    // 当前订单入口的业务404/409发生在用户锁内幂等查询后，可以修正重新购买。
    if (error instanceof ApiError && error.status >= 400 && error.status < 500
        && (!resuming || error.path === intent.path && [404, 409].includes(error.status))) clearPendingOrder(userId);
    throw error;
  }
}
