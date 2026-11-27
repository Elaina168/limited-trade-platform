import assert from 'node:assert/strict'
import { test } from 'node:test'
import { safeRedirect } from '../src/utils/safe-redirect.ts'
import { pickBestCoupon } from '../src/utils/purchase-coupons.ts'
import type { UserCoupon } from '../src/types/catalog.ts'

test('登录回跳保留站内查询和锚点，拒绝跨站和认证循环', () => {
  assert.equal(safeRedirect('/purchase/3?quantity=2#address'), '/purchase/3?quantity=2#address')
  assert.equal(safeRedirect('/products?q=%E6%B0%B4'), '/products?q=%E6%B0%B4')
  for (const value of [undefined, ['/cart'], 'https://example.com', '//example.com', '/\\example.com',
    '/%2fexample.com', '/%255Cexample.com', '/%09example.com', '/LOGIN/?a=1', '/register#x', '/x/../login', '/%6cogin']) {
    assert.equal(safeRedirect(value), undefined, String(value))
  }
})

test('可用券先比较面额，再选更早到期的券', () => {
  const coupon = (id: number, discountAmount: number, useEndsAt: string) => ({
    id, coupon: { discountAmount, useEndsAt },
  }) as UserCoupon
  const early = coupon(3, 10, '2026-11-28T00:00:00')
  const list = [coupon(1, 6, '2026-11-27T00:00:00'), coupon(2, 10, '2026-11-29T00:00:00'), early]
  assert.equal(pickBestCoupon(list), early)
  assert.deepEqual(list.map(item => item.id), [1, 2, 3])
  assert.equal(pickBestCoupon([]), undefined)
})
