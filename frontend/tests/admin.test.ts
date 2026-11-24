import { beforeEach, afterEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { createPinia } from 'pinia'
import { createSSRApp, effectScope } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { advanceSession, RequestError } from '../src/api/http.ts'
import { useAdminPage } from '../src/composables/useAdminPage.ts'
import { useAuthStore } from '../src/stores/auth.ts'
import type { Activity } from '../src/types/catalog.ts'
import type { CouponInput } from '../src/types/admin.ts'
const originalFetch = globalThis.fetch
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status })
beforeEach(() => { advanceSession() })
afterEach(() => { globalThis.fetch = originalFetch })

test('admin writes with lost responses reread the server without resending writes', async t => {
  const pinia = createPinia()
  const auth = useAuthStore(pinia)
  auth.user = { id: 1, username: 'admin', role: 'ADMIN' }
  auth.status = 'authenticated'
  let writes = 0
  let status = 'OFF_SALE'
  let read = async () => status
  let page!: ReturnType<typeof useAdminPage<string>>
  const scope = effectScope(true)
  t.after(() => scope.stop())
  const app = createSSRApp({ setup() {
    page = scope.run(() => useAdminPage(() => read(), () => {}))!
    return () => null
  } }).use(pinia)
  await renderToString(app)

  const lostWrite = async (value: string) => {
    writes++
    status = value
    throw new RequestError('写入响应丢失')
  }
  await page.mutate(() => lostWrite('ON_SALE'), '商品已上架。', undefined, value => value === 'ON_SALE')
  assert.equal(writes, 1)
  assert.equal(page.data.value, 'ON_SALE')
  assert.equal(page.notice.value, '商品已上架。')
  assert.equal(page.error.value, '')

  await page.mutate(() => lostWrite('已存在相同资料'), '商品已创建。')
  assert.equal(writes, 2)
  assert.equal(page.data.value, '已存在相同资料')
  assert.match(page.notice.value, /已自动更新页面数据.*无法确认/)
  assert.equal(page.blocked.value, false)

  let finishRead!: (value: string) => void
  const pendingRead = new Promise<string>(resolve => { finishRead = resolve })
  const readStarted = new Promise<void>(resolve => {
    read = () => { resolve(); return pendingRead }
  })
  const pendingOperation = page.mutate(() => lostWrite('旧会话的最新状态'), '商品已保存。')
  await readStarted
  advanceSession()
  finishRead('旧会话的最新状态')
  await pendingOperation
  assert.equal(writes, 3)
  assert.equal(page.data.value, null)
  assert.equal(page.notice.value, '')
})

test('admin product status uses PATCH and current CSRF while detail update excludes stock', async () => {
  const api = await import('../src/api/admin.ts')
  const writes: { path: string; options: RequestInit }[] = []
  globalThis.fetch = async (input, options = {}) => {
    if (String(input).endsWith('/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'admin-current' })
    writes.push({ path: String(input), options }); return json({ id: 11 })
  }
  await api.updateProductStatus(11, 'ON_SALE')
  await api.updateProduct(11, { name: 'edited', price: 19.99, categoryId: null })
  assert.equal(writes[0]!.path, '/api/admin/products/11/status')
  assert.equal(writes[0]!.options.method, 'PATCH')
  assert.equal(new Headers(writes[0]!.options.headers).get('X-CSRF-TOKEN'), 'admin-current')
  assert.deepEqual(JSON.parse(String(writes[0]!.options.body)), { status: 'ON_SALE' })
  assert.equal('stock' in JSON.parse(String(writes[1]!.options.body)), false)
  assert.equal(writes[1]!.options.method, 'PUT')
})

test('admin order list encodes both filters and detail/ship use admin endpoints', async () => {
  const api = await import('../src/api/admin.ts')
  const paths: string[] = []
  globalThis.fetch = async input => { const path=String(input); paths.push(path); return json(path.endsWith('/csrf') ? { headerName: 'X-CSRF-TOKEN', token: 'a' } : {}) }
  await api.adminOrders(2, 'PAID', '71')
  await api.adminOrder(91)
  await api.shipOrder(91, { carrier: '承运方', trackingNo: 'TRACK-91' })
  assert.deepEqual(Object.fromEntries(new URL(paths[0]!, 'http://localhost').searchParams), { page:'2', size:'10', status:'PAID', userId:'71' })
  assert.ok(paths.includes('/api/admin/orders/91'))
  assert.equal(paths.at(-1), '/api/admin/orders/91/ship')
})

test('admin create and rename APIs preserve real paths and payloads', async () => {
  const api = await import('../src/api/admin.ts')
  const requests: { path: string; method: string | undefined }[] = []
  globalThis.fetch = async (input, options = {}) => {
    const path = String(input)
    if (path.endsWith('/csrf')) return json({ headerName:'X-CSRF-TOKEN', token:'a' })
    requests.push({ path, method: options.method });return json({ id:1 },201)
  }
  await api.createCategory('分类')
  await api.renameCategory(3,'新分类')
  await api.createProduct({ name:'商品',price:1,stock:0,categoryId:null })
  await api.createActivity({ productId:1,name:'活动',totalQuantity:1,startsAt:'2026-11-21T10:00',endsAt:'2026-11-22T10:00' })
  await api.createCoupon({} as CouponInput)
  assert.deepEqual(requests, [
    {path:'/api/admin/categories',method:'POST'}, {path:'/api/admin/categories/3',method:'PUT'},
    {path:'/api/admin/products',method:'POST'}, {path:'/api/admin/activities',method:'POST'}, {path:'/api/admin/coupons',method:'POST'},
  ])
})

test('money and quantities enforce backend precision and integer bounds', async () => {
  const { validMoney, validQuantity } = await import('../src/utils/admin.ts')
  for (const value of ['0.01','99999999.99','15','15.0']) assert.equal(validMoney(value),true)
  for (const value of ['0','-1','1.001','100000000.00','NaN','']) assert.equal(validMoney(value),false)
  assert.equal(validQuantity(0,true),true);assert.equal(validQuantity(0),false)
  assert.equal(validQuantity(2147483647),true);assert.equal(validQuantity(2147483648),false)
  assert.equal(validQuantity(1.1),false)
})

test('coupon windows follow activity bounds and allow pre-activity claiming', async () => {
  const { validCouponWindow } = await import('../src/utils/admin.ts')
  const event={ startsAt:'2026-11-21T10:00', endsAt:'2026-11-22T10:00' } as Activity
  const rule={ claimStartsAt:'2026-11-20T10:00',claimEndsAt:'2026-11-21T10:00',useStartsAt:event.startsAt,useEndsAt:event.endsAt } as CouponInput
  assert.equal(validCouponWindow(rule,event),true)
  assert.equal(validCouponWindow({...rule,claimEndsAt:rule.claimStartsAt},event),false)
  assert.equal(validCouponWindow({...rule,useStartsAt:'2026-11-21T09:59'},event),false)
  assert.equal(validCouponWindow({...rule,useEndsAt:'2026-11-22T10:01'},event),false)
  assert.equal(validCouponWindow({...rule,claimEndsAt:'2026-11-22T10:01'},event),false)
  assert.equal(validCouponWindow({...rule,useEndsAt:''},event),false)
})
