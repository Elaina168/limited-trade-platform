import { afterEach, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../src/stores/auth.ts'
import { advanceSession, readJson, postForm, sessionRevision, SessionChangedError } from '../src/api/http.ts'

const originalFetch = globalThis.fetch
const user = { id: 73, username: 'buyer', role: 'USER' }
const admin = { id: 74, username: 'manager', role: 'ADMIN' }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

beforeEach(() => { setActivePinia(createPinia()); advanceSession() })
afterEach(() => { globalThis.fetch = originalFetch })

test('login sends fresh CSRF and form credentials then reads identity after 204', async () => {
  const requests: { path: string; options: RequestInit }[] = []
  globalThis.fetch = async (input, options = {}) => {
    const path = String(input)
    requests.push({ path, options })
    if (path.endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf-a' })
    if (path.endsWith('/login')) return new Response(null, { status: 204 })
    return json(user)
  }
  const auth = useAuthStore()
  await auth.login({ username: 'buyer', password: 'CorrectPassword!' })
  assert.equal(auth.status, 'authenticated')
  assert.deepEqual(auth.user, user)
  assert.deepEqual(requests.map(item => item.path), ['/api/auth/csrf', '/api/auth/login', '/api/auth/me'])
  assert.equal(requests[1]!.options.credentials, 'same-origin')
  assert.equal(new Headers(requests[1]!.options.headers).get('X-CSRF-TOKEN'), 'csrf-a')
  assert.equal(new URLSearchParams(String(requests[1]!.options.body)).get('password'), 'CorrectPassword!')
  assert.equal('password' in auth.$state, false)
})

test('concurrent restores share one request and initial 401 means anonymous', async () => {
  let requests = 0
  globalThis.fetch = async () => { requests++; return new Response(null, { status: 401 }) }
  const auth = useAuthStore()
  await Promise.all([auth.restore(), auth.restore()])
  assert.equal(requests, 1)
  assert.equal(auth.status, 'anonymous')
  assert.equal(auth.error, '')
})

test('wrong password stops before me and leaves no authenticated identity', async () => {
  const requests: string[] = []
  globalThis.fetch = async input => {
    requests.push(String(input))
    return String(input).endsWith('/csrf') ? json({ headerName: 'X-CSRF-TOKEN', token: 'a' }) : new Response(null, { status: 401 })
  }
  const auth = useAuthStore()
  await auth.login({ username: 'buyer', password: 'wrong' })
  assert.equal(auth.status, 'anonymous')
  assert.equal(auth.user, null)
  assert.match(auth.error, /登录未通过/)
  assert.equal(requests.includes('/api/auth/me'), false)
})

test('old successful me response cannot overwrite a new login', async () => {
  let release!: (response: Response) => void
  let meCount = 0
  globalThis.fetch = async input => {
    const path = String(input)
    if (path.endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'a' })
    if (path.endsWith('/login')) return new Response(null, { status: 204 })
    if (++meCount === 1) return new Promise<Response>(resolve => { release = resolve })
    return json(admin)
  }
  const auth = useAuthStore()
  const old = auth.restore()
  await auth.login({ username: 'manager', password: 'password' })
  release(json(user))
  await old
  assert.deepEqual(auth.user, admin)
  assert.equal(auth.isAdmin, true)
})

test('identity change during CSRF preparation prevents the old POST', async () => {
  let release!: (response: Response) => void
  let count = 0
  globalThis.fetch = async () => { count++; return new Promise<Response>(resolve => { release = resolve }) }
  const pending = postForm('/api/auth/logout', {}, sessionRevision())
  advanceSession()
  release(json({ headerName: 'X-CSRF-TOKEN', token: 'old' }))
  await assert.rejects(pending, SessionChangedError)
  assert.equal(count, 1)
})

test('a late business 401 cannot expire the new account', async () => {
  let release!: (response: Response) => void
  globalThis.fetch = async input => {
    const path = String(input)
    if (path === '/api/orders') return new Promise<Response>(resolve => { release = resolve })
    if (path.endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'a' })
    if (path.endsWith('/login')) return new Response(null, { status: 204 })
    return json(admin)
  }
  const auth = useAuthStore()
  const old = readJson('/api/orders')
  await auth.login({ username: 'manager', password: 'password' })
  release(new Response(null, { status: 401 }))
  await assert.rejects(old, SessionChangedError)
  assert.equal(auth.status, 'authenticated')
  assert.deepEqual(auth.user, admin)
})

test('logout uses a new CSRF token and clears the current user', async () => {
  const tokens: string[] = []
  let sequence = 0
  globalThis.fetch = async (input, options = {}) => {
    if (String(input).endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: `csrf-${++sequence}` })
    if (options.method === 'POST') {
      tokens.push(new Headers(options.headers).get('X-CSRF-TOKEN')!)
      return new Response(null, { status: 204 })
    }
    return json(user)
  }
  const auth = useAuthStore()
  await auth.login({ username: 'buyer', password: 'password' })
  await auth.logout()
  assert.deepEqual(tokens, ['csrf-1', 'csrf-2'])
  assert.equal(auth.user, null)
  assert.equal(auth.status, 'anonymous')
})

test('failed connection is retryable and is not treated as anonymous', async () => {
  globalThis.fetch = async () => { throw new TypeError('network failure') }
  const auth = useAuthStore()
  await auth.restore()
  assert.equal(auth.status, 'failed')
  assert.match(auth.error, /无法连接/)
  globalThis.fetch = async () => json(user)
  await auth.restore(true)
  assert.equal(auth.status, 'authenticated')
})

test('CSRF denial prevents login POST and exposes its trace identifier', async () => {
  let count = 0
  globalThis.fetch = async () => {
    count++
    return new Response(null, { status: 403, headers: { 'X-Trace-Id': 'trace-csrf' } })
  }
  const auth = useAuthStore()
  await auth.login({ username: 'buyer', password: 'password' })
  assert.equal(count, 1)
  assert.equal(auth.status, 'failed')
  assert.match(auth.error, /trace-csrf/)
})

test('uncertain logout rechecks the server and keeps its failure visible', async () => {
  let reads = 0
  globalThis.fetch = async (input, options = {}) => {
    if (String(input).endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'a' })
    if (options.method === 'POST') return new Response(null, { status: 403 })
    reads++
    return reads === 1 ? json(user) : new Response(null, { status: 401 })
  }
  const auth = useAuthStore()
  await auth.restore()
  await auth.logout()
  assert.equal(auth.user, null)
  assert.equal(auth.status, 'anonymous')
  assert.match(auth.error, /凭据/)
})

test('a focus refresh that finds another account invalidates old business responses', async () => {
  let release!: (response: Response) => void
  let reads = 0
  globalThis.fetch = async input => {
    if (String(input) === '/api/orders') return new Promise<Response>(resolve => { release = resolve })
    return json(++reads === 1 ? user : admin)
  }
  const auth = useAuthStore()
  await auth.restore()
  const old = readJson('/api/orders')
  await auth.restore(true)
  release(json({ owner: 'buyer' }))
  await assert.rejects(old, SessionChangedError)
  assert.deepEqual(auth.user, admin)
})

test('late logout confirmation cannot attach its error to a newer login', async () => {
  let release!: (response: Response) => void
  let reads = 0
  globalThis.fetch = async (input, options = {}) => {
    const path = String(input)
    if (path.endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'a' })
    if (path.endsWith('/logout')) return new Response(null, { status: 403 })
    if (options.method === 'POST') return new Response(null, { status: 204 })
    if (++reads === 2) return new Promise<Response>(resolve => { release = resolve })
    return json(reads === 1 ? user : admin)
  }
  const auth = useAuthStore()
  await auth.restore()
  const leaving = auth.logout()
  // 等待退出后的身份确认进入受控请求，再启动另一账号登录。
  while (!release) await new Promise(resolve => setImmediate(resolve))
  await auth.login({ username: 'manager', password: 'password' })
  release(json(user))
  await leaving
  assert.deepEqual(auth.user, admin)
  assert.equal(auth.error, '')
})
