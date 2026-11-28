import { beforeEach, afterEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { createPinia, disposePinia, setActivePinia } from 'pinia'
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


test('购物车角标统计总件数且切换账号后不接收旧快照', async () => {
  const { useCartStore } = await import('../src/stores/cart.ts')
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = { id: 101, username: 'a', role: 'USER' }
  const cart = useCartStore()
  cart.replace([{ productId: 1, name: '甲', quantity: 2, stock: 5, price: 1, status: 'ON_SALE' },
    { productId: 2, name: '乙', quantity: 3, stock: 5, price: 1, status: 'ON_SALE' }])
  assert.equal(cart.totalCount, 5)
  let complete!: (value: Response) => void
  globalThis.fetch = () => new Promise(resolve => { complete = resolve })
  const refreshed = cart.refresh()
  try {
    advanceSession()
    auth.user = { id: 102, username: 'b', role: 'USER' }
    assert.equal(cart.totalCount, 0)
    complete(Response.json([{ productId: 1, quantity: 5 }]))
    await refreshed
    assert.equal(cart.totalCount, 0)
    assert.deepEqual(cart.items, [])
  } finally { disposePinia(pinia) }
})

test('异步秒杀收到 202 QUEUED 后自动轮询成单并清理待定意图', async () => {
  let polls = 0
  const notices: string[] = []
  globalThis.fetch = async input => {
    const url = String(input)
    if (url.endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'fresh' })
    if (url.endsWith('/api/activities/9/seckill')) {
      return json({ requestId: 'req-1', status: 'QUEUED', orderId: null, order: null, message: '排队中' }, 202)
    }
    if (url.includes('/api/activities/seckill/result/')) {
      polls++
      if (polls === 1) return json({ requestId: 'req-1', status: 'QUEUED', orderId: null, order: null, message: '排队中' })
      return json({ requestId: 'req-1', status: 'CREATED', orderId: 501, order: { id: 501, totalAmount: 18 }, message: '已生成' })
    }
    throw new Error('unexpected url: ' + url)
  }
  const { checkout } = await store()
  const order = await checkout.submitSeckill(9, { productId: 3, quantity: 1, addressId: 19, userCouponId: null }, msg => notices.push(msg))
  assert.equal(order.id, 501)
  assert.equal(polls, 2)
  assert.ok(notices.length >= 1)
  assert.equal(checkout.pending, null)
})

test('异步秒杀最终返回 FAILED 时清除待定意图并抛出原因', async () => {
  globalThis.fetch = async input => {
    const url = String(input)
    if (url.endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'fresh' })
    if (url.endsWith('/api/activities/9/seckill')) {
      return json({ requestId: 'req-2', status: 'QUEUED', orderId: null, order: null, message: '排队中' }, 202)
    }
    return json({ requestId: 'req-2', status: 'FAILED', orderId: null, order: null, message: '优惠券已失效' })
  }
  const { checkout } = await store()
  await assert.rejects(
    checkout.submitSeckill(9, { productId: 3, quantity: 1, addressId: 19, userCouponId: 88 }),
    /优惠券已失效/,
  )
  assert.equal(checkout.pending, null)
})

test('恢复已受理的秒杀时越过短轮询窗口并在弱网后成单，全程只查询结果', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  saved.set('limited-market:vue-order-intent:31', JSON.stringify({
    path: '/api/activities/9/seckill', data: { productId: 3, quantity: 1, addressId: 19, userCouponId: null },
    requestId: 'long-queue', queued: true, purchaseRoute: '/activities/9',
  }))
  let polls = 0
  const { checkout } = await store()
  globalThis.fetch = async (input, options) => {
    assert.equal(options?.method, undefined)
    assert.equal(String(input), '/api/activities/seckill/result/long-queue')
    polls++
    if (polls === 11) throw new TypeError('offline')
    if (polls === 12) return json({}, 404)
    if (polls < 14) return json({ requestId: 'long-queue', status: 'QUEUED' })
    return json({ requestId: 'long-queue', status: 'CREATED', orderId: 501, order: { id: 501 } })
  }
  const recovery = checkout.recoverPending()
  for (let i = 0; i < 14; i++) {
    t.mock.timers.tick(10_000)
    await new Promise(resolve => setImmediate(resolve))
    if (i === 10) assert.ok(checkout.pending?.requestId === 'long-queue')
    if (i === 11) void checkout.recoverPending()
  }
  await recovery
  assert.equal(polls, 14)
  assert.equal(checkout.pending, null)
  assert.equal(checkout.recoveredOrder?.id, 501)
  assert.equal(checkout.takeSeckillPayment('/activities/9'), 501)
  assert.equal(checkout.takeSeckillPayment('/activities/9'), undefined)
})

test('切换账号立即停止已受理秒杀的轮询并保留原账号的恢复标识', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const savedIntent = JSON.stringify({
    path: '/api/activities/9/seckill', data: { productId: 3, quantity: 1, addressId: 19, userCouponId: null },
    requestId: 'switch-queue', queued: true, purchaseRoute: '/activities/9',
  })
  saved.set('limited-market:vue-order-intent:31', savedIntent)
  const { auth, checkout } = await store()
  let signal: AbortSignal | null | undefined
  globalThis.fetch = async (_, options) => {
    signal = options?.signal
    return new Promise((_, reject) => signal?.addEventListener('abort', () => reject(signal!.reason), { once: true }))
  }
  const recovery = checkout.recoverPending()
  t.mock.timers.tick(1000)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(signal?.aborted, false)
  advanceSession()
  auth.user = { id: 32, username: 'other', role: 'USER' }
  assert.equal(signal?.aborted, true)
  await recovery
  assert.equal(checkout.pending, null)
  assert.equal(checkout.recoveredOrder, null)
  assert.equal(saved.get('limited-market:vue-order-intent:31'), savedIntent)
})

test('秒杀提交回执丢失但结果接口已确认成单时不重发写请求', async () => {
  let requestId: string
  let posts = 0
  globalThis.fetch = async (input, options) => {
    const path = String(input)
    if (path.endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'current' })
    if (options?.method === 'POST') {
      posts++
      requestId = JSON.parse(String(options.body)).requestId
      throw new TypeError('连接在写入后断开')
    }
    assert.equal(path, '/api/activities/seckill/result/' + requestId)
    return json({ requestId, status: 'CREATED', orderId: 501, order: { id: 501 } })
  }
  const { checkout } = await store()
  const order = await checkout.submitSeckill(9, { productId: 3, quantity: 1, addressId: 19, userCouponId: null })
  assert.equal(order.id, 501)
  assert.equal(posts, 1)
  assert.equal(checkout.pending, null)
})

test('秒杀意图只能由秒杀结果确认，订单列表中的同标识普通订单不清除排队状态', async () => {
  saved.set('limited-market:vue-order-intent:31', JSON.stringify({
    path: '/api/activities/9/seckill', data: { productId: 3, quantity: 1, addressId: 19, userCouponId: null },
    requestId: 'cross-route', queued: true, purchaseRoute: '/activities/9',
  }))
  const { checkout } = await store()
  checkout.confirmKnownOrder({ id: 19, requestId: 'cross-route' } as import('../src/types/checkout.ts').Order)
  assert.equal(checkout.pending?.requestId, 'cross-route')
  assert.equal(checkout.recoveredOrder, null)
})
