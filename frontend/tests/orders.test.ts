import { beforeEach, afterEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { advanceSession } from '../src/api/http.ts'
import type { Order } from '../src/types/checkout.ts'
const originalFetch = globalThis.fetch
const json = (value: unknown) => new Response(JSON.stringify(value), { status: 200 })
beforeEach(() => { advanceSession() })
afterEach(() => { globalThis.fetch = originalFetch })

test('order list sends correct page size and optional status', async () => {
  const { listOrders } = await import('../src/api/orders.ts')
  const paths: string[] = []
  globalThis.fetch = async input => { paths.push(String(input)); return json({ page: 2, size: 10, total: 11, items: [] }) }
  await listOrders(2, 'PAID')
  await listOrders(1, '')
  await listOrders(1, 'CANCELED_OR_CLOSED')
  assert.deepEqual(Object.fromEntries(new URL(paths[0]!, 'http://localhost').searchParams), { page: '2', size: '10', status: 'PAID' })
  assert.equal(new URL(paths[1]!, 'http://localhost').searchParams.has('status'), false)
  assert.equal(new URL(paths[2]!, 'http://localhost').searchParams.get('status'), 'CANCELED_OR_CLOSED')
})

test('shared order action reconciles a lost write using only an authoritative GET', async () => {
  const { submitOrderAction } = await import('../src/api/orders.ts')
  const visits: string[] = []
  globalThis.fetch = async (input, options) => {
    const path = String(input)
    visits.push(`${options?.method ?? 'GET'} ${path}`)
    if (path.endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'current' })
    if (options?.method === 'POST') throw new TypeError('连接在写入后断开')
    return json({ id: 91, status: 'SHIPPED' })
  }
  let checking = false
  const outcome = await submitOrderAction(91, 'pay', () => true, () => { checking = true })
  assert.equal(checking, true)
  assert.equal(outcome?.snapshot?.status, 'SHIPPED')
  assert.equal(outcome?.notice, '模拟支付成功。')
  assert.deepEqual(visits, ['GET /api/auth/csrf', 'POST /api/orders/91/pay', 'GET /api/orders/91'])
})

test('order actions use fresh CSRF and order-specific POST endpoints', async () => {
  const { changeOrder } = await import('../src/api/orders.ts')
  const paths: string[] = []
  globalThis.fetch = async (input, options = {}) => {
    const path = String(input); paths.push(path)
    if (path.endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'current' })
    assert.equal(options.method, 'POST')
    assert.equal(new Headers(options.headers).get('X-CSRF-TOKEN'), 'current')
    assert.equal(options.body, undefined)
    return json({ id: 91 })
  }
  for (const action of ['pay', 'cancel', 'receive'] as const) await changeOrder(91, action)
  assert.deepEqual(paths, ['/api/auth/csrf','/api/orders/91/pay','/api/auth/csrf','/api/orders/91/cancel','/api/auth/csrf','/api/orders/91/receive'])
})

test('available order actions match server state and exact pay deadline', async () => {
  const { availableOrderActions } = await import('../src/utils/orders.ts')
  const order = { status: 'PENDING_PAYMENT', expiresAt: '2026-11-20T12:00:00' } as Order
  const deadline = new Date(order.expiresAt).getTime()
  assert.deepEqual(availableOrderActions(order, deadline - 1), ['pay', 'cancel'])
  assert.deepEqual(availableOrderActions(order, deadline), ['cancel'])
  assert.deepEqual(availableOrderActions(order, deadline + 1), ['cancel'])
  for (const status of ['PAID','CANCELED','CLOSED','COMPLETED'] as const) assert.deepEqual(availableOrderActions({ ...order, status }, deadline), [])
  assert.deepEqual(availableOrderActions({ ...order, status: 'SHIPPED' }, deadline), ['receive'])
})
