import { test } from 'node:test'
import assert from 'node:assert/strict'
import { effectScope } from 'vue'
import { useCartQuantitySave } from '../src/composables/useCartQuantitySave.ts'

test('购物车写入响应丢失时按商品和数量只读核对', async () => {
  const originalFetch = globalThis.fetch
  const requests: string[] = []
  const scope = effectScope()
  globalThis.fetch = async (input, init) => {
    const url = String(input)
    requests.push(url)
    if (url === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'test' })
    if (url === '/api/cart/items/17') {
      assert.equal(init?.method, 'PUT')
      assert.deepEqual(JSON.parse(String(init?.body)), { quantity: 3 })
      throw new TypeError('连接中断')
    }
    assert.equal(url, '/api/cart/items')
    return Response.json([{ productId: 17, quantity: 3 }])
  }
  try {
    const cart = scope.run(useCartQuantitySave)!
    assert.equal(await cart.saveQuantity(17, 3), '已加入购物车。')
    assert.deepEqual(requests, ['/api/auth/csrf', '/api/cart/items/17', '/api/cart/items'])
    assert.equal(cart.error.value, '')
    assert.equal(cart.notice.value, '')
  } finally {
    scope.stop()
    globalThis.fetch = originalFetch
  }
})


test('累加加购的回执丢失时以旧数量加本次增量核对，不重发 POST', async () => {
  const originalFetch = globalThis.fetch
  const scope = effectScope()
  let reads = 0, writes = 0
  globalThis.fetch = async (input, init) => {
    const url = String(input)
    if (url === '/api/auth/csrf') return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'test' })
    assert.equal(url, '/api/cart/items')
    if (init?.method === 'POST') {
      writes++
      assert.deepEqual(JSON.parse(String(init.body)), { productId: 17, quantity: 1 })
      assert.equal(new Headers(init.headers).get('X-CSRF-TOKEN'), 'test')
      throw new TypeError('连接中断')
    }
    return Response.json([{ productId: 17, quantity: 2 + reads++ }])
  }
  try {
    const cart = scope.run(useCartQuantitySave)!
    const recovered = await cart.addQuantity(17, 1)
    assert.equal(recovered?.confirmed, true)
    assert.equal(recovered?.item?.quantity, 3)
    assert.equal(reads, 2)
    assert.equal(writes, 1)
  } finally {
    scope.stop()
    globalThis.fetch = originalFetch
  }
})

test('库存上限冲突只提示失败，不将已有购物车条目当作本次加购', async () => {
  const originalFetch = globalThis.fetch
  const scope = effectScope()
  let reads = 0, writes = 0
  globalThis.fetch = async (input, init) => {
    if (String(input).endsWith('/csrf')) return Response.json({ headerName: 'X-CSRF-TOKEN', token: 'test' })
    if (init?.method === 'POST') { writes++; return new Response(null, { status: 409 }) }
    reads++
    return Response.json([{ productId: 17, quantity: 5 }])
  }
  try {
    const cart = scope.run(useCartQuantitySave)!
    assert.equal(await cart.addQuantity(17, 1), undefined)
    assert.match(cart.error.value, /数量上限/)
    assert.equal(reads, 1)
    assert.equal(writes, 1)
  } finally {
    scope.stop()
    globalThis.fetch = originalFetch
  }
})
