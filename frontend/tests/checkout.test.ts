import { beforeEach, afterEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../src/stores/auth.ts'
import { advanceSession, HttpError } from '../src/api/http.ts'

const originalFetch = globalThis.fetch
const saved = new Map<string, string>()
Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, value: {
  getItem: (key: string) => saved.get(key) ?? null,
  setItem: (key: string, value: string) => { saved.set(key, value) },
  removeItem: (key: string) => { saved.delete(key) },
} })
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status })
const data = { productIds: [3, 7], addressId: 19 }
beforeEach(() => { saved.clear(); advanceSession(); setActivePinia(createPinia()) })
afterEach(() => { globalThis.fetch = originalFetch })

async function store() {
  const { useCheckoutStore } = await import('../src/stores/checkout.ts')
  const auth = useAuthStore()
  auth.user = { id: 31, username: 'buyer', role: 'USER' }
  auth.status = 'authenticated'
  return { auth, checkout: useCheckoutStore() }
}

test('lost order response is recovered automatically with the identical requestId', async () => {
  let lost = true
  const bodies: unknown[] = []
  globalThis.fetch = async (input, options = {}) => {
    if (String(input).endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'fresh' })
    bodies.push(JSON.parse(String(options.body)))
    if (lost) { lost = false; throw new TypeError('connection lost after commit') }
    return json({ id: 81, totalAmount: 12 })
  }
  const { checkout } = await store()
  assert.equal((await checkout.submit('/api/orders/checkout', data)).id, 81)
  assert.equal(bodies.length, 2)
  assert.deepEqual(bodies[0], bodies[1])
  assert.equal(checkout.pending, null)
})

test('a saved checkout intent is resolved automatically when authentication is restored', async () => {
  globalThis.fetch = async input => String(input).endsWith('/csrf')
    ? json({ headerName: 'X-CSRF-TOKEN', token: 'a' }) : Promise.reject(new TypeError('offline'))
  const { checkout } = await store()
  await assert.rejects(checkout.submit('/api/orders/checkout', data))
  const requestId = checkout.pending!.requestId
  setActivePinia(createPinia())
  const resumed = (await store()).checkout
  globalThis.fetch = async (input, options) => {
    if (String(input).endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'a' })
    assert.deepEqual(JSON.parse(String(options?.body)), { ...data, requestId })
    return json({ id: 82, requestId })
  }
  await resumed.recoverPending()
  assert.equal(resumed.recoveredOrder?.id, 82)
  assert.equal(resumed.pending, null)
})

test('unconfirmed order blocks changed purchase and account switch isolates saved intent', async () => {
  globalThis.fetch = async input => String(input).endsWith('/csrf') ? json({ headerName: 'X-CSRF-TOKEN', token: 'a' }) : Promise.reject(new TypeError('offline'))
  const { auth, checkout } = await store()
  await assert.rejects(checkout.submit('/api/orders/checkout', data))
  const original = checkout.pending!.requestId
  await assert.rejects(checkout.submit('/api/orders/checkout', { ...data, addressId: 20 }), /上一笔/)
  auth.user = { id: 32, username: 'other', role: 'USER' }
  assert.equal(checkout.pending, null)
  auth.user = { id: 31, username: 'buyer', role: 'USER' }
  assert.equal(checkout.pending!.requestId, original)
})

test('fresh CSRF denial clears a new intent but retry CSRF denial keeps unknown intent', async () => {
  let mode = 'csrf-denied'
  globalThis.fetch = async input => {
    if (String(input).endsWith('/csrf')) return mode === 'csrf-denied' ? new Response(null, { status: 403 }) : json({ headerName: 'X-CSRF-TOKEN', token: 'a' })
    throw new TypeError('unknown result')
  }
  const { checkout } = await store()
  await assert.rejects(checkout.submit('/api/orders/checkout', data), HttpError)
  assert.equal(checkout.pending, null)
  mode = 'lost'
  await assert.rejects(checkout.submit('/api/orders/checkout', data))
  const id = checkout.pending!.requestId
  mode = 'csrf-denied'
  await checkout.recoverPending()
  assert.equal(checkout.pending!.requestId, id)
})

test('late order success after identity revision change stays unconfirmed', async () => {
  let release!: (value: Response) => void
  const response = new Promise<Response>(resolve => { release = resolve })
  globalThis.fetch = async input => String(input).endsWith('/csrf') ? json({ headerName: 'X-CSRF-TOKEN', token: 'a' }) : response
  const { checkout } = await store()
  const task = checkout.submit('/api/orders/checkout', data)
  await new Promise(resolve => setImmediate(resolve))
  advanceSession()
  release(json({ id: 81 }))
  await assert.rejects(task)
  assert.ok(checkout.pending)
})

test('PUT and DELETE attach CSRF and accept empty 204 responses', async () => {
  const { writeJson } = await import('../src/api/http.ts')
  const writes: RequestInit[] = []
  globalThis.fetch = async (input, options = {}) => {
    if (String(input).endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'fresh' })
    writes.push(options)
    return new Response(null, { status: 204 })
  }
  await writeJson('/api/cart/items/3', 'PUT', { quantity: 2 })
  await writeJson('/api/cart/items/3', 'DELETE')
  assert.deepEqual(writes.map(item => item.method), ['PUT', 'DELETE'])
  assert.equal(new Headers(writes[0]!.headers).get('X-CSRF-TOKEN'), 'fresh')
  assert.equal(writes[1]!.body, undefined)
})


test('only a matching known order confirms the current pending purchase', async () => {
  globalThis.fetch = async input => String(input).endsWith('/csrf') ? json({ headerName: 'X-CSRF-TOKEN', token: 'a' }) : Promise.reject(new TypeError('unknown result'))
  const { checkout } = await store()
  await assert.rejects(checkout.submit('/api/orders/checkout', data))
  const requestId = checkout.pending!.requestId
  checkout.confirmKnownOrder({ requestId: 'unrelated' } as import('../src/types/checkout.ts').Order)
  assert.equal(checkout.pending!.requestId, requestId)
  checkout.confirmKnownOrder({ requestId } as import('../src/types/checkout.ts').Order)
  assert.equal(checkout.pending, null)
})

test('other account cannot confirm the previous account saved intent', async () => {
  globalThis.fetch = async input => String(input).endsWith('/csrf') ? json({ headerName: 'X-CSRF-TOKEN', token: 'a' }) : Promise.reject(new TypeError('unknown result'))
  const { auth, checkout } = await store()
  await assert.rejects(checkout.submit('/api/orders/checkout', data))
  const requestId = checkout.pending!.requestId
  auth.user = { id: 32, username: 'other', role: 'USER' }
  checkout.confirmKnownOrder({ requestId } as import('../src/types/checkout.ts').Order)
  auth.user = { id: 31, username: 'buyer', role: 'USER' }
  assert.equal(checkout.pending!.requestId, requestId)
})
