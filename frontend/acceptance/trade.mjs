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
  await heading(page, '欢迎回来')
  assert.equal(new URL(page.url()).pathname, '/login')
}

export async function login(page, base, username, password, path = '/login') {
  await page.goto(base + path)
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

  await buyer.goto(base + '/')
  await heading(buyer, '限量集市')
  await heading(buyer, '在售好物')
  await heading(buyer, fixture.product)
  await buyer.goto(base + '/products')
  await heading(buyer, fixture.product)
  await buyer.goto(base + '/cart?source=acceptance#summary')
  await heading(buyer, '欢迎回来')
  assert.equal(new URL(buyer.url()).searchParams.get('redirect'), '/cart?source=acceptance#summary')
  await register(buyer, base, fixture.buyer, fixture.password)
  await login(buyer, base, fixture.buyer, fixture.password)
  await heading(buyer, '发现好物')
  assert.equal(new URL(buyer.url()).pathname, '/products')
  await buyer.reload()
  await heading(buyer, '发现好物')
  assert.equal((await (await buyer.request.get(base + '/api/auth/me')).json()).role, 'USER')
  await button(buyer, '账号菜单').click()
  await buyer.getByRole('link', name('账号概览')).click()
  await buyer.waitForURL(base + '/account')
  await heading(buyer, '账号信息')
  console.log('通过：游客首页、注册登录、刷新与账号菜单')

  await button(buyer, '退出登录').click()
  await login(buyer, base, fixture.buyer, fixture.password, '/cart?source=acceptance#summary')
  await buyer.waitForURL(base + '/cart?source=acceptance#summary')
  await heading(buyer, '购物车')
  const fields = [
    ['收货人', '验收买家'], ['手机号', '13800138000'], ['省份', '浙江省'],
    ['城市', '杭州市'], ['区县', '西湖区'], ['详细地址', '验收地址'],
  ]
  const productId = await mysql("SELECT id FROM product WHERE name='" + fixture.product + "'")
  assert.match(productId, /^\d+$/)
  await buyer.goto(base + '/purchase/9999999')
  await buyer.getByRole('alert').filter({ hasText: '商品不存在或当前已下架，请返回商品列表。' }).waitFor()
  await mysql("INSERT INTO product_category(name) VALUES ('验收分类'); UPDATE product SET category_id=(SELECT id FROM product_category WHERE name='验收分类') WHERE id=" + productId)
  await buyer.goto(base + '/products')
  await button(buyer, '验收分类').click()
  assert.equal(await button(buyer, '验收分类').getAttribute('aria-pressed'), 'true')
  await buyer.getByLabel('商品名称', exact).fill('验收不存在商品')
  await button(buyer, '搜索').click()
  await buyer.getByText('没有找到符合条件的商品，试试其他名称或分类。', exact).waitFor()
  await buyer.getByLabel('商品名称', exact).hover()
  await buyer.locator('.el-input__clear').click()
  await heading(buyer, fixture.product)
  const focusRead = buyer.waitForResponse(r => new URL(r.url()).pathname === '/api/products/search')
  await buyer.evaluate(() => window.dispatchEvent(new Event('focus')))
  assert.equal((await focusRead).status(), 200)
  await heading(buyer, fixture.product)
  await buyer.getByRole('link').filter({ has: buyer.getByRole('heading', name(fixture.product)) }).click()
  await buyer.waitForURL(base + '/products/' + productId)
  await heading(buyer, fixture.product)
  await buyer.getByLabel('购买数量', exact).fill('2')
  for (const expected of [2, 4]) {
    const [cartWrite] = await Promise.all([
      buyer.waitForResponse(r => r.url() === base + '/api/cart/items' && r.request().method() === 'POST'),
      button(buyer, '加入购物车').click(),
    ])
    assert.equal(cartWrite.status(), 200)
    assert.deepEqual(cartWrite.request().postDataJSON(), { productId: Number(productId), quantity: 2 })
    assert.equal((await cartWrite.json()).quantity, expected)
    await buyer.locator('[aria-label="购物车商品总件数"]').filter({ hasText: String(expected) }).waitFor({ state: 'attached' })
  }
  assert.equal(await mysql('SELECT quantity FROM cart_item WHERE product_id=' + productId), '4')
  console.log('通过：商品详情累加加购和购物车总件数徽标')
  await button(buyer, '立即购买').click()
  const drawer = buyer.getByRole('dialog', name('立即购买'))
  await drawer.getByLabel('购买数量', exact).waitFor()
  assert.equal(await drawer.getByLabel('购买数量', exact).inputValue(), '2')
  assert.ok(await button(drawer, '提交订单').isDisabled())
  await button(drawer, '+ 新增地址').click()
  const editor = buyer.getByRole('dialog', name('新增收货地址'))
  await editor.getByLabel('省市区', exact).waitFor()
  await button(editor, '手动填写').click()
  for (const [field, value] of fields) await editor.getByLabel(field, exact).fill(value)
  const addressWrite = Promise.withResolvers()
  await buyer.route(base + '/api/addresses', async route => {
    try {
      if (route.request().method() !== 'POST') {
        await route.continue()
        return
      }
      const response = await route.fetch()
      if (response.ok()) await route.abort()
      else await route.fulfill({ response })
      addressWrite.resolve(response.status())
    } catch (error) {
      addressWrite.reject(error)
    }
  })
  const [addressStatus] = await Promise.all([addressWrite.promise, button(editor, '保存地址').click()])
  assert.equal(addressStatus, 201)
  await editor.waitFor({ state: 'hidden' })
  await buyer.unroute(base + '/api/addresses')
  const addressSelect = drawer.getByRole('combobox', name('收货地址'))
  await addressSelect.waitFor()
  await drawer.locator('.el-select__selected-item').filter({ hasText: '验收地址' }).waitFor()
  const addressId = Number(await mysql("SELECT id FROM user_address WHERE detail_address='验收地址'"))
  assert.ok(addressId > 0)
  assert.equal(new URL(buyer.url()).pathname, '/products/' + productId)
  assert.equal(await button(drawer, '刷新购买信息').count(), 0)
  assert.equal(await button(drawer, '保存到购物车').count(), 0)
  await buyer.goto(base + '/addresses')
  await buyer.getByText('验收地址', { exact: false }).waitFor()
  assert.equal(await button(buyer, '刷新地址').count(), 0)
  const region = buyer.getByLabel('省市区', exact)
  await region.click()
  for (const label of ['浙江省', '杭州市', '西湖区']) {
    await buyer.getByText(label, exact).click()
  }
  assert.match(await region.inputValue(), /浙江省.*杭州市.*西湖区/)

  // 独立库内保留过期活动名额，以区分显示问题和服务端实际拒绝。
  await mysql("INSERT INTO activity(product_id,name,total_quantity,remaining_quantity,starts_at,ends_at) VALUES (" +
    productId + ",'验收过期活动',3,3,DATE_SUB(NOW(),INTERVAL 3 DAY),DATE_SUB(NOW(),INTERVAL 2 DAY)),(" +
    productId + ",'验收有效活动',3,3,DATE_SUB(NOW(),INTERVAL 2 DAY),DATE_ADD(NOW(),INTERVAL 1 YEAR))")
  const expired = await mysql("SELECT id FROM activity WHERE name='验收过期活动'")
  await buyer.goto(base + '/activities')
  const expiredCard = buyer.locator('article').filter({ has: buyer.getByRole('heading', name('验收过期活动')) })
  await expiredCard.getByText('已结束', { exact: true }).waitFor()
  await buyer.locator('article').filter({ has: buyer.getByRole('heading', name('验收有效活动')) })
    .getByText('进行中', { exact: true }).waitFor()
  await expiredCard.getByRole('link').click()
  await heading(buyer, '验收过期活动')
  assert.equal(await button(buyer, '参加活动下单').count(), 0)
  assert.equal(await button(buyer, '刷新活动').count(), 0)
  await buyer.goto(base + '/purchase/' + productId + '?activityId=' + expired)
  await button(buyer, '提交订单').waitFor()
  assert.ok(await button(buyer, '提交订单').isDisabled())
  await buyer.getByText('活动已结束。', { exact: true }).waitFor()
  const csrf = await (await buyer.request.get(base + '/api/auth/csrf')).json()
  const ended = await buyer.request.post(base + '/api/orders', {
    headers: { [csrf.headerName]: csrf.token },
    data: { productId: Number(productId), activityId: Number(expired), quantity: 1, addressId, requestId: 'acceptance-ended' },
  })
  assert.equal(ended.status(), 409)
  assert.equal(await mysql("SELECT remaining_quantity FROM activity WHERE id=" + expired), '3')
  console.log('通过：商品详情与地址选择、过期活动禁购及服务端拒绝')
  await buyer.goto(base + '/cart')
  await buyer.locator('.cart-row .el-checkbox').click()
  assert.ok(await buyer.getByRole('checkbox', name('选择' + fixture.product)).isChecked())
  await buyer.locator('.checkout-summary .el-select__selected-item').filter({ hasText: '验收地址' }).waitFor()
  assert.equal(await button(buyer, '刷新购物车').count(), 0)

  assert.equal(await button(buyer, '保存数量').count(), 0)
  let quantitySaved = false
  await buyer.route(base + '/api/cart/items/' + productId, async route => {
    const request = route.request()
    assert.equal(request.method(), 'PUT')
    assert.deepEqual(request.postDataJSON(), { quantity: 2 })
    // 保持写入在途，核对“立即结算”会先等待数量落库。
    await new Promise(resolve => setTimeout(resolve, 350))
    const saved = await route.fetch()
    assert.equal(saved.status(), 204)
    quantitySaved = true
    await route.fulfill({ response: saved })
  }, { times: 1 })
  const cartQuantity = buyer.getByLabel(fixture.product + '数量', exact)
  await cartQuantity.fill('2')

  // 首次提交实际写库，浏览器只丢弃本次响应。
  const checkoutUrl = base + '/api/orders/checkout'
  const submitted = Promise.withResolvers()
  await buyer.route(checkoutUrl, async (route) => {
    try {
      const request = route.request()
      assert.equal(request.method(), 'POST')
      assert.ok(quantitySaved, '结算须等待购物车数量写入完成')
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
  assert.equal(firstOrder.data.items[0].quantity, 2)
  await buyer.locator('[aria-label="购物车商品总件数"]').waitFor({ state: 'detached' })
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
  await heading(admin, '发现好物')
  assert.equal((await admin.request.get(base + orderPath)).status(), 404)
  await mysql("UPDATE app_user SET role='ADMIN' WHERE username='" + fixture.admin + "'")
  await admin.context().clearCookies()
  await login(admin, base, fixture.admin, fixture.password)
  await heading(admin, '管理中心')
  await admin.goto(base + '/admin/orders/' + id)
  await admin.getByLabel('承运方', exact).fill('验收快递')
  await admin.getByLabel('运单号', exact).fill('ACCEPTANCE-001')
  await dropCommittedResponse(admin, base + '/api/admin/orders/' + id + '/ship')
  await button(admin, '登记发货').click()
  await admin.getByText('发货已登记。', { exact: true }).waitFor()
  await heading(admin, '已登记物流')

  await buyer.setViewportSize({ width: 1280, height: 800 })
  await buyer.goto(base + '/orders')
  await buyer.getByRole('tab', name('已发货')).click()
  const orderCard = buyer.locator('article').filter({ has: buyer.getByRole('heading', name('订单 #' + id)) })
  await dropCommittedResponse(buyer, base + orderPath + '/receive')
  await button(orderCard, '确认收货').click()
  await button(buyer, '确认收到').click()
  await buyer.getByText('已确认收货。', exact).waitFor()
  await buyer.getByRole('tab', name('已完成')).click()
  await orderCard.getByText('已完成', exact).waitFor()
  assert.equal(await button(buyer, '刷新订单列表').count(), 0)
  await admin.reload()
  await admin.getByText('已完成', { exact: true }).waitFor()
  assert.equal((await (await buyer.request.get(base + orderPath)).json()).status, 'COMPLETED')
  const catalogSize = 723
  const catalog = Array.from({ length: catalogSize }, (_, i) => "('验收管理-" + String(i).padStart(4, '0') + "',19.90,3,'" + (i === 0 ? 'OFF_SALE' : 'ON_SALE') + "')")
  await mysql('INSERT INTO product(name,price,stock,status) VALUES ' + catalog.join(','))
  await admin.goto(base + '/admin/products')
  await heading(admin, '商品管理')
  const adminCards = admin.locator('.admin-grid .market-card')
  await adminCards.nth(19).waitFor()
  assert.equal(await adminCards.count(), 20)
  const firstName = await adminCards.first().getByRole('heading').textContent()
  const adminPage = admin.waitForResponse(r => new URL(r.url()).pathname === '/api/admin/products/page' && new URL(r.url()).searchParams.get('page') === '2')
  await admin.getByRole('navigation', name('管理商品分页')).locator('.btn-next').click()
  const nextPage = await (await adminPage).json()
  assert.equal(nextPage.total, catalogSize + 1)
  await adminCards.first().getByRole('heading', name(nextPage.items[0].name)).waitFor()
  assert.notEqual(nextPage.items[0].name, firstName)
  await admin.getByLabel('按商品名称搜索', exact).fill('验收管理-0000')
  await button(admin, '搜索商品').click()
  await heading(admin, '验收管理-0000')
  assert.equal(await adminCards.count(), 1)
  const adminStatus = admin.locator('.el-select').filter({ has: admin.locator('#admin-product-status') })
  await adminStatus.click()
  await admin.getByRole('option', name('上架中')).click()
  await adminCards.first().waitFor({ state: 'detached' })
  await adminStatus.click()
  await admin.getByRole('option', name('已下架')).click()
  await heading(admin, '验收管理-0000')
  console.log('通过：724 件隔离商品的管理员分页、名称和状态筛选')

  const active = await mysql("SELECT id FROM activity WHERE name='验收有效活动'")
  await mysql("INSERT INTO activity_coupon(activity_id,name,threshold_amount,discount_amount,total_quantity,remaining_quantity,claim_starts_at,claim_ends_at,use_starts_at,use_ends_at) VALUES " +
    ['验收低面额券', '验收晚到期券', '验收最优券'].map((label, index) =>
      '(' + active + ",'" + label + "',15," + (index === 0 ? 2 : 5) +
      ",1,0,NOW()-INTERVAL 1 DAY,NOW()+INTERVAL 1 DAY,NOW()-INTERVAL 1 DAY,NOW()+INTERVAL " + (index === 1 ? 3 : 2) + " DAY)"
    ).join(','))
  await mysql("INSERT INTO user_coupon(coupon_id,user_id,status,claimed_at) SELECT c.id,u.id,'AVAILABLE',NOW() FROM activity_coupon c " +
    "JOIN app_user u ON u.username='" + fixture.buyer + "' WHERE c.activity_id=" + active)
  await buyer.goto(base + '/activities/' + active)
  await button(buyer, '参加活动下单').click()
  const activityDrawer = buyer.getByRole('dialog', name('参加活动下单'))
  const coupon = activityDrawer.locator('.el-select').filter({ has: buyer.getByRole('combobox', name('活动优惠券')) })
  const best = await mysql("SELECT uc.id FROM user_coupon uc JOIN activity_coupon c ON uc.coupon_id=c.id WHERE c.name='验收最优券'")
  await activityDrawer.locator('.el-select__selected-item').filter({ hasText: '验收最优券' }).waitFor()
  await coupon.click()
  await activityDrawer.getByRole('option', name('不使用优惠券')).click()
  await activityDrawer.getByLabel('购买数量', exact).fill('2')
  await activityDrawer.getByLabel('购买数量', exact).press('Tab')
  await activityDrawer.locator('.el-select__selected-item').filter({ hasText: '不使用优惠券' }).waitFor()
  await coupon.click()
  await activityDrawer.getByRole('option', { name: /验收最优券/ }).click()
  const [activityOrder] = await Promise.all([
    buyer.waitForResponse(r => r.url() === base + '/api/orders' && r.request().method() === 'POST'),
    button(activityDrawer, '提交订单').click(),
  ])
  assert.equal(activityOrder.status(), 201)
  const created = await activityOrder.json()
  assert.equal(created.discountAmount, 5)
  assert.equal(activityOrder.request().postDataJSON().userCouponId, Number(best))
  await buyer.waitForURL(base + '/orders/' + created.id + '?justCreated=1')
  await button(buyer, '立即模拟支付').waitFor()
  await buyer.getByText(/^支付剩余 /).waitFor()
  await buyer.goto(base + '/orders')
  await buyer.getByRole('tab', name('待支付')).click()
  const newCard = buyer.locator('article').filter({ has: buyer.getByRole('heading', name('订单 #' + created.id)) })
  await newCard.getByText(/^支付剩余 /).waitFor()
  await buyer.getByRole('tab', name('全部')).click()
  await button(newCard, '立即支付').click()
  await newCard.getByText('已支付', exact).waitFor()
  console.log('通过：活动购买抽屉自动匹配最优券，保留主动弃券，提交后直达支付')

  await button(buyer, '退出登录').click()
  await buyer.goto(base + '/orders/' + id)
  await heading(buyer, '欢迎回来')
  assert.deepEqual(pageErrors, [])
  console.log('通过：CSRF、订单归属、管理权限、支付、发货、收货丢失回执后自动核对及退出')
}
