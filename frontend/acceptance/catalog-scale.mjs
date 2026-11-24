import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const prefix = '扩展演示·'
const stockBeforeRace = 8

export function seedCatalogScale(mysql, seed) {
  const initial = mysql("SELECT COUNT(*) FROM product WHERE name LIKE '验收商品_%'")
  mysql(seed)
  const counts = () => [
    "SELECT COUNT(*) FROM product_category WHERE name LIKE '扩展演示·%'",
    "SELECT COUNT(*) FROM product WHERE name LIKE '扩展演示·%'",
    "SELECT COUNT(*) FROM activity WHERE name LIKE '扩展演示·%'",
    "SELECT COUNT(*) FROM activity_coupon WHERE name LIKE '扩展演示·%'",
  ].map(sql => Number(mysql(sql)))
  assert.deepEqual(counts(), [12, 720, 120, 240])
  const sample = "SELECT p.stock,a.remaining_quantity,a.starts_at,a.ends_at,c.remaining_quantity FROM product p JOIN activity a ON a.product_id=p.id JOIN activity_coupon c ON c.activity_id=a.id WHERE a.name='扩展演示·活动 003' ORDER BY c.id"
  mysql("UPDATE activity SET remaining_quantity=remaining_quantity-1 WHERE name='扩展演示·活动 003'")
  const beforeRepeat = mysql(sample)
  mysql(seed)
  assert.deepEqual(counts(), [12, 720, 120, 240])
  assert.equal(mysql(sample), beforeRepeat)
  assert.equal(mysql("SELECT COUNT(*) FROM product WHERE name LIKE '验收商品_%'"), initial)
  console.log('通过：默认目录种子 12 分类、720 商品、120 活动、240 券，二次导入保持数量、名额和时间')
}

export async function verifyCatalogScale({ root, backend, mysql, runId, profile, fixture }) {
  async function request(path, session, method = 'GET', data) {
    const headers = {}
    if (session?.cookie) headers.Cookie = session.cookie
    if (method === 'POST' && session?.csrf) headers[session.csrf.headerName] = session.csrf.token
    if (data) headers['Content-Type'] = data instanceof URLSearchParams ? 'application/x-www-form-urlencoded' : 'application/json'
    const started = performance.now()
    const response = await fetch(backend + path, { method, headers, signal: AbortSignal.timeout(15_000),
      body: data instanceof URLSearchParams ? data : data ? JSON.stringify(data) : undefined })
    if (session) {
      const cookie = response.headers.getSetCookie().find(value => value.startsWith('JSESSIONID='))
      if (cookie) session.cookie = cookie.split(';')[0]
    }
    const raw = await response.text()
    return { status: response.status, ms: performance.now() - started, data: raw ? JSON.parse(raw) : null }
  }

  const [categories, activities] = await Promise.all([request('/api/categories'), request('/api/activities')])
  assert.equal(categories.status, 200)
  assert.equal(activities.status, 200)
  assert.equal(categories.data.filter(x => x.name.startsWith(prefix)).length, 12)
  const addedActivities = activities.data.filter(x => x.name.startsWith(prefix))
  assert.equal(addedActivities.length, 120)
  const foundProducts = new Set()
  for (let page = 1; page <= 8; page++) {
    const result = await request('/api/products/search?' + new URLSearchParams({ keyword: prefix, size: '100', page: String(page) }))
    assert.equal(result.status, 200)
    assert.equal(result.data.total, 720)
    assert.equal(result.data.items.length, page < 8 ? 100 : 20)
    for (const item of result.data.items) {
      assert.ok(!foundProducts.has(item.id))
      foundProducts.add(item.id)
    }
  }
  assert.equal(foundProducts.size, 720)
  const category = categories.data.find(x => x.name === prefix + '电脑外设')
  const filtered = await request('/api/products/search?' + new URLSearchParams({ categoryId: category.id, keyword: '无线鼠标' }))
  assert.equal(filtered.status, 200)
  assert.equal(filtered.data.total, 20)

  const allCoupons = new Map()
  for (let start = 0; start < addedActivities.length; start += 12) {
    await Promise.all(addedActivities.slice(start, start + 12).map(async activity => {
      const result = await request('/api/activities/' + activity.id + '/coupons')
      assert.equal(result.status, 200)
      assert.equal(result.data.length, 2)
      assert.ok(result.data.every(coupon => coupon.activityId === activity.id))
      allCoupons.set(activity.id, result.data)
    }))
  }
  assert.equal(new Set([...allCoupons.values()].flat().map(c => c.id)).size, 240)
  console.log('通过：分页遍历 720 商品且无重复，分类关键词组合与 120 活动的 240 张券逐一核对')

  const readings = []
  for (const width of [1, 8, 24, 48]) {
    const batch = await Promise.all(Array.from({ length: width }, () => request('/api/products')))
    for (const data of batch) {
      assert.equal(data.status, 200)
      assert.equal(data.data.filter(x => x.name.startsWith(prefix)).length, 720)
    }
    const times = batch.map(row => row.ms).sort((a, b) => a - b)
    readings.push({ width, requests: times.length, medianMs: times[Math.floor((times.length - 1) / 2)],
      p95Ms: times[Math.ceil(times.length * 0.95) - 1] })
  }
  const details = await Promise.all([...foundProducts].slice(0, 48).map(id => request('/api/products/' + id)))
  assert.ok(details.every(detail => detail.status === 200 && foundProducts.has(detail.data.id)))
  assert.equal((await request('/api/products/9223372036854775807')).status, 404)

  const active = addedActivities.find(item => item.name === prefix + '活动 003')
  const future = addedActivities.find(item => item.name === prefix + '活动 002')
  const past = addedActivities.find(item => item.name === prefix + '活动 001')
  const coupon = allCoupons.get(active.id).find(c => c.name === prefix + '单件满减券')
  mysql('UPDATE product SET stock=' + stockBeforeRace + ' WHERE id=' + active.productId +
    '; UPDATE activity SET remaining_quantity=' + stockBeforeRace + ' WHERE id=' + active.id)
  const sessions = []
  for (let start = 0; start < 24; start += 6) {
    const group = await Promise.all(Array.from({ length: 6 }, async (_, index) => {
      const session = {}
      const credentials = { username: 'scale_' + runId + '_' + (start + index), password: fixture.password }
      session.csrf = (await request('/api/auth/csrf', session)).data
      assert.equal((await request('/api/auth/register', session, 'POST', credentials)).status, 201)
      assert.equal((await request('/api/auth/login', session, 'POST', new URLSearchParams(credentials))).status, 204)
      session.csrf = (await request('/api/auth/csrf', session)).data
      return session
    }))
    sessions.push(...group)
  }
  const claim = (session, id) => request('/api/coupons/' + id + '/claim', session, 'POST')
  assert.equal((await claim(sessions[0], allCoupons.get(future.id)[0].id)).status, 409)
  assert.equal((await claim(sessions[0], allCoupons.get(past.id)[0].id)).status, 409)
  const claims = await Promise.all(sessions.map(async session => ({ session, result: await claim(session, coupon.id) })))
  assert.deepEqual(claims.map(x => x.result.status).sort(), [...Array(16).fill(200), ...Array(8).fill(409)])
  const winners = claims.filter(row => row.result.status === 200)
  assert.equal((await claim(winners[0].session, coupon.id)).data.id, winners[0].result.data.id)
  const orders = await Promise.all(winners.map(async ({ session, result }, index) => ({
    session, result: await request('/api/orders', session, 'POST',
      { productId: active.productId, activityId: active.id, quantity: 1,
        userCouponId: result.data.id, requestId: 'scale-order-' + index }),
  })))
  assert.deepEqual(orders.map(x => x.result.status).sort(), [...Array(8).fill(201), ...Array(8).fill(409)])
  const created = orders.filter(row => row.result.status === 201)
  assert.ok(created.every(x => x.result.data.discountAmount === coupon.discountAmount))
  const cancel = await Promise.all(created.map(({ session, result }) =>
    request('/api/orders/' + result.data.id + '/cancel', session, 'POST')))
  assert.ok(cancel.every(x => x.status === 200))
  assert.equal(mysql('SELECT stock FROM product WHERE id=' + active.productId), '8')
  assert.equal(mysql('SELECT remaining_quantity FROM activity WHERE id=' + active.id), '8')
  assert.equal(mysql('SELECT remaining_quantity FROM activity_coupon WHERE id=' + coupon.id), '0')
  assert.equal(mysql("SELECT COUNT(*) FROM user_coupon WHERE coupon_id=" + coupon.id + " AND status='AVAILABLE'"), '16')
  assert.equal(mysql('SELECT COUNT(*) FROM order_coupon WHERE activity_id=' + active.id), '8')
  const emptySession = claims.find(row => row.result.status === 409).session
  const productId = Number(mysql("SELECT id FROM product WHERE name='" + fixture.product + "'"))
  const payload = { productId, quantity: 1, requestId: 'scale-idempotent' }
  const retries = await Promise.all(Array.from({ length: 8 }, () => request('/api/orders', emptySession, 'POST', payload)))
  const admitted = profile === 'off' ? 8 : 5
  assert.deepEqual(retries.map(x => x.status).sort(), [
    ...Array(admitted - 1).fill(200), 201, ...Array(8 - admitted).fill(429),
  ])
  assert.equal(new Set(retries.filter(x => x.status < 300).map(x => x.data.id)).size, 1)
  assert.equal(mysql('SELECT stock FROM product WHERE id=' + productId), '9')
  console.log('通过：同账号 8 次幂等提交，' + admitted + ' 次准入，始终只扣 1 件库存')
  const output = resolve(root, 'tools/catalog-scale')
  await mkdir(output, { recursive: true })
  const file = resolve(output, runId + '.json')
  await writeFile(file, JSON.stringify({ profile, categories: 12, products: 720, activities: 120, coupons: 240,
    readings, details: 48, couponRace: { attempts: 24, claimed: 16 },
    orderRace: { attempts: 16, created: 8, cancelled: 8 }, limiterBurst: { attempts: 8, admitted }, complete: true }, null, 2) + '\n')
  console.log('通过：24 人抢 16 张券，16 人争 8 个名额，取消后库存、活动和券状态对账；报告 ' + file)
}
