import type { CsrfToken } from '../types/auth.ts'

export class HttpError extends Error {
  status: number
  traceId: string | null
  path: string | undefined
  constructor(status: number, traceId: string | null, path?: string) {
    super(`HTTP ${status}`)
    this.status = status
    this.traceId = traceId
    this.path = path
  }
}

export class RequestError extends Error {}
export class SessionChangedError extends RequestError {
  constructor() { super('账号状态已变化，请重新操作。') }
}

export function isUncertainWrite(cause: unknown) {
  return cause instanceof RequestError && !(cause instanceof SessionChangedError)
    || cause instanceof HttpError && cause.status >= 500
    || cause instanceof DOMException && cause.name === 'TimeoutError'
}

let revision = 0
let unauthorized: () => void = () => {}

export function sessionRevision() { return revision }
const sessionListeners = new Set<() => void>()
export function onSessionChange(listener: () => void) {
  sessionListeners.add(listener)
  return () => { sessionListeners.delete(listener) }
}
export function advanceSession() {
  revision++
  for (const listener of sessionListeners) listener()
  return revision
}
export function onUnauthorized(handler: () => void) { unauthorized = handler }

function checkSession(expected: number) {
  if (expected !== revision) throw new SessionChangedError()
}

async function request(path: string, options: RequestInit, expected: number): Promise<Response> {
  checkSession(expected)
  let response: Response
  try {
    response = await fetch(path, {
      ...options,
      credentials: 'same-origin',
      cache: 'no-store',
      signal: options.signal ?? AbortSignal.timeout(15_000),
    })
  } catch (error) {
    if (error instanceof TypeError) throw new RequestError('无法连接服务，请检查网络后重试。')
    if (error instanceof DOMException && error.name === 'TimeoutError') {
      throw new RequestError('请求超时。')
    }
    throw error
  }
  // 身份切换后，旧成功响应和旧401都不能影响当前页面。
  checkSession(expected)
  if (!response.ok) {
    if (response.status === 401 && path !== '/api/auth/me' && path !== '/api/auth/login') unauthorized()
    throw new HttpError(response.status, response.headers.get('X-Trace-Id'), path)
  }
  return response
}

export async function readJson<T>(path: string, expected = revision, signal?: AbortSignal): Promise<T> {
  const response = await request(path, { signal }, expected)
  let body: T
  try { body = await response.json() as T }
  catch (error) {
    if (error instanceof SyntaxError) throw new RequestError('服务返回的数据无法读取，请稍后重试。')
    throw error
  }
  checkSession(expected)
  return body
}

export async function postForm(path: string, values: Record<string, string>, expected = revision): Promise<void> {
  // Spring Security在登录与退出后轮换令牌，每次写操作获取当前会话的令牌。
  const csrf = await readJson<CsrfToken>('/api/auth/csrf', expected)
  checkSession(expected)
  await request(path, {
    method: 'POST',
    headers: { [csrf.headerName]: csrf.token, 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
    body: new URLSearchParams(values),
  }, expected)
}

export async function writeJson<T = void>(path: string, method: 'POST' | 'PUT' | 'DELETE' | 'PATCH', values?: unknown, expected = revision, signal?: AbortSignal): Promise<T> {
  const csrf = await readJson<CsrfToken>('/api/auth/csrf', expected, signal)
  const response = await request(path, {
    method, signal,
    headers: { [csrf.headerName]: csrf.token, 'Content-Type': 'application/json' },
    body: values === undefined ? undefined : JSON.stringify(values),
  }, expected)
  if (response.status === 204) return undefined as T
  let body: T
  try { body = await response.json() as T }
  catch (error) {
    if (error instanceof SyntaxError) throw new RequestError('服务返回的数据无法读取。')
    throw error
  }
  checkSession(expected)
  return body
}

export function postJson<T>(path: string, values?: unknown, expected = revision, signal?: AbortSignal): Promise<T> {
  return writeJson<T>(path, 'POST', values, expected, signal)
}

export async function postEventStream(path: string, values: unknown, signal: AbortSignal, expected = revision) {
  const csrf = await readJson<CsrfToken>('/api/auth/csrf', expected, signal)
  return request(path, {
    method: 'POST', signal,
    headers: { [csrf.headerName]: csrf.token, 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify(values),
  }, expected)
}

export function errorMessage(error: unknown): string {
  if (error instanceof RequestError) return error.message
  if (!(error instanceof HttpError)) throw error
  const messages: Record<number, string> = {
    400: '填写内容不符合要求，请检查后重试。',
    401: '登录未通过或会话已过期，请确认账号密码后重新登录。',
    403: '当前操作未获允许，页面凭据可能已过期。',
    404: '请求的内容不存在，请返回首页。',
    409: '当前库存、活动名额、优惠券条件或其他业务状态不允许此操作。',
    429: '操作较频繁，请稍后重试。',
    502: '后端服务暂时无法连接，请稍后重试。',
    503: '服务暂时不可用，请稍后重试。',
  }
  const message = messages[error.status] ?? `请求未完成（HTTP ${error.status}），请稍后重试。`
  return error.traceId ? `${message} 追踪编号 ${error.traceId}` : message
}
