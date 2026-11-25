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
