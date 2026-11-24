import assert from 'node:assert/strict'

const name = (name) => ({ name, exact: true })
const exact = { exact: true }
const heading = (page, text) => page.getByRole('heading', name(text)).waitFor()
const button = (page, text) => page.getByRole('button', name(text))

async function register(page, base, username, password) {
  await page.goto(base + '/register')
  await page.getByLabel('用户名', exact).fill(username)
  await page.getByLabel('密码', exact).fill(password)
  await page.getByLabel('确认密码', exact).fill(password)
  await button(page, '注册').click()
  await page.getByText('注册成功，请使用新账号登录。', { exact: true }).waitFor()
}

export async function login(page, base, username, password) {
  await page.goto(base + '/login')
  await page.getByLabel('用户名', exact).fill(username)
  await page.getByLabel('密码', exact).fill(password)
  const [response] = await Promise.all([
    page.waitForResponse((r) => r.url() === base + '/api/auth/login' && r.request().method() === 'POST'),
    button(page, '登录').click(),
  ])
  assert.equal(response.status(), 204)
}

async function dropCommittedResponse(page, url) {
  await page.route(url, async route => {
    const result = await route.fetch()
    if (result.ok()) await route.abort()
    else await route.fulfill({ response: result })
  }, { times: 1 })
}

export async function verifyTrade(browser, base, backend, fixture, mysql) {
  const buyer = await browser.newPage({ viewport: { width: 390, height: 844 } })
  const admin = await browser.newPage()
  const pageErrors = []
  for (const page of [buyer, admin]) {
    page.setDefaultTimeout(10_000)
    page.on('pageerror', (error) => pageErrors.push(error.message))
  }

  await buyer.goto(base + '/products')
  await heading(buyer, fixture.product)
  await buyer.goto(base + '/cart')
  await heading(buyer, '欢迎回来')
  await register(buyer, base, fixture.buyer, fixture.password)
  await login(buyer, base, fixture.buyer, fixture.password)
  await heading(buyer, '你好，' + fixture.buyer)
  await buyer.reload()
  await heading(buyer, '你好，' + fixture.buyer)
  assert.equal((await (await buyer.request.get(base + '/api/auth/me')).json()).role, 'USER')
  console.log('通过：游客浏览、注册和登录后刷新')

  await buyer.goto(base + '/addresses')
  const fields = [
    ['收货人', '验收买家'], ['手机号', '13800138000'], ['省份', '浙江省'],
    ['城市', '杭州市'], ['区县', '西湖区'], ['详细地址', '验收地址'],
  ]
  for (const [field, value] of fields) await buyer.getByLabel(field, exact).fill(value)
  await button(buyer, '保存地址').click()
  await buyer.getByText('地址已保存。', { exact: true }).waitFor()

  const productId = await mysql("SELECT id FROM product WHERE name='" + fixture.product + "'")
  assert.match(productId, /^\d+$/)
  await buyer.goto(base + '/purchase/9999999')
  await buyer.getByRole('alert').filter({ hasText: '商品不存在或当前已下架，请返回商品列表。' }).waitFor()
  const [detail] = await Promise.all([
    buyer.waitForResponse((response) => response.url() === base + '/api/products/' + productId),
    buyer.goto(base + '/purchase/' + productId),
  ])
  assert.equal(detail.status(), 200)
  await buyer.getByLabel('购买数量', exact).fill('2')
  const [cartWrite] = await Promise.all([
    buyer.waitForResponse((r) => r.url() === base + '/api/cart/items/' + productId && r.request().method() === 'PUT'),
    button(buyer, '保存到购物车').click(),
  ])
  assert.equal(cartWrite.status(), 204)
  await buyer.goto(base + '/cart')
  await buyer.getByLabel('选择' + fixture.product, exact).check()

  // 首次提交实际写库，浏览器只丢弃本次响应。
  const checkoutUrl = base + '/api/orders/checkout'
  const submitted = Promise.withResolvers()
  await buyer.route(checkoutUrl, async (route) => {
    try {
      const request = route.request()
      assert.equal(request.method(), 'POST')
      const headers = await request.allHeaders()
      const response = await fetch(backend + '/api/orders/checkout', {
        method: 'POST',
        body: request.postData(),
        headers: {
          'content-type': headers['content-type'],
          'x-csrf-token': headers['x-csrf-token'],
          cookie: headers.cookie,
        },
        signal: AbortSignal.timeout(8000),
      })
      const data = await response.json()
      assert.equal(response.status, 201)
      await route.abort()
      submitted.resolve({ data, request: request.postDataJSON() })
    } catch (error) {
      submitted.reject(error)
    }
  }, { times: 1 })
  const [firstOrder, retried] = await Promise.all([
    submitted.promise,
    buyer.waitForResponse(r => r.url() === checkoutUrl && r.request().method() === 'POST' && r.status() === 200),
    button(buyer, '提交购物车订单').click(),
  ])
  assert.equal(retried.status(), 200)
  assert.deepEqual(retried.request().postDataJSON(), firstOrder.request)
  assert.deepEqual(await retried.json(), firstOrder.data)
  const { id, totalAmount, address } = firstOrder.data
  assert.equal(totalAmount, 39.8)
  assert.equal(address.detailAddress, '验收地址')
  assert.equal(await mysql('SELECT COUNT(*) FROM trade_order'), '1')
  assert.equal(await mysql('SELECT stock FROM product WHERE id=' + productId), '8')
  console.log('通过：购物车结算回执丢失后自动确认原订单和库存对账')

  const orderPath = '/api/orders/' + id
  await heading(buyer, '订单 #' + id)
  const noCsrf = await buyer.request.post(base + orderPath + '/pay')
  assert.equal(noCsrf.status(), 403)
  assert.equal((await (await buyer.request.get(base + orderPath)).json()).status, 'PENDING_PAYMENT')
  await dropCommittedResponse(buyer, base + orderPath + '/pay')
  await button(buyer, '模拟支付').click()
  await buyer.getByText('模拟支付成功。', { exact: true }).waitFor()
  await buyer.goto(base + '/admin/orders')
  await heading(buyer, '当前账号没有访问权限')
  assert.equal((await buyer.request.get(base + '/api/admin/orders')).status(), 403)

  // 第二个独立会话先保持 USER，核对订单归属，随后只提升临时库中的夹具账号。
  await register(admin, base, fixture.admin, fixture.password)
  await login(admin, base, fixture.admin, fixture.password)
  await heading(admin, '你好，' + fixture.admin)
  assert.equal((await admin.request.get(base + orderPath)).status(), 404)
  await mysql("UPDATE app_user SET role='ADMIN' WHERE username='" + fixture.admin + "'")
  await admin.context().clearCookies()
  await login(admin, base, fixture.admin, fixture.password)
  await heading(admin, '管理中心')
  await admin.goto(base + '/admin/orders/' + id)
  await admin.getByLabel('承运方', exact).fill('验收快递')
  await admin.getByLabel('运单号', exact).fill('ACCEPTANCE-001')
  admin.once('dialog', (dialog) => dialog.accept())
  await dropCommittedResponse(admin, base + '/api/admin/orders/' + id + '/ship')
  await button(admin, '登记发货').click()
  await admin.getByText('发货已登记。', { exact: true }).waitFor()
  await heading(admin, '已登记物流')

  await buyer.goto(base + '/orders/' + id)
  buyer.once('dialog', (dialog) => dialog.accept())
  await dropCommittedResponse(buyer, base + orderPath + '/receive')
  await button(buyer, '确认收货').click()
  await buyer.getByText('已确认收货。', { exact: true }).waitFor()
  await admin.reload()
  await admin.getByText('已完成', { exact: true }).waitFor()
  assert.equal((await (await buyer.request.get(base + orderPath)).json()).status, 'COMPLETED')
  await button(buyer, '退出登录').click()
  await buyer.goto(base + '/orders/' + id)
  await heading(buyer, '欢迎回来')
  assert.deepEqual(pageErrors, [])
  console.log('通过：CSRF、订单归属、管理权限、支付、发货、收货丢失回执后自动核对及退出')
}
