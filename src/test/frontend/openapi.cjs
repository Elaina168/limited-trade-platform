// 使用现有Playwright，在隔离测试库对应的真实随机端口验证Swagger UI。
const { chromium } = require(process.env.PLAYWRIGHT_MODULE);
const assert = require('node:assert/strict');
const base = process.env.DOCS_BASE_URL;
const product = process.env.DOCS_PRODUCT_ID;

(async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage();
    page.setDefaultTimeout(8000);
    const errors = [], external = [];
    page.on('pageerror', error => { errors.push(error.message); console.error('浏览器脚本错误：', error.message); });
    page.on('console', message => { if (message.type() === 'error') console.error('浏览器控制台：', message.text()); });
    page.on('request', request => {
      const url = new URL(request.url());
      if (url.origin !== base) external.push(request.url());
      console.log('HTTP', request.method(), url.pathname);
    });
    await page.goto(`${base}/api-docs.html`);
    try {
      await page.getByRole('heading', { name: '限量交易平台 API', exact: false }).waitFor();
    } catch (error) {
      console.error('文档首屏：', await page.locator('body').innerText());
      throw error;
    }

    async function operation(method, path) {
      const block = page.locator(`.opblock.opblock-${method}`).filter({
        has: page.locator(`.opblock-summary-path[data-path="${path}"]`)
      });
      if (!await block.locator('.opblock-body').isVisible()) await block.locator('.opblock-summary').click();
      await block.getByRole('button', { name: /^(Try it out|Cancel)$/ }).waitFor();
      const start = block.getByRole('button', { name: 'Try it out', exact: true });
      if (await start.isVisible()) await start.click();
      await block.getByRole('button', { name: 'Execute', exact: true }).waitFor();
      return block;
    }
    async function execute(block, method, path, status) {
      let result;
      try {
        [result] = await Promise.all([
          page.waitForResponse(response => new URL(response.url()).pathname === path && response.request().method() === method),
          block.getByRole('button', { name: 'Execute', exact: true }).click()
        ]);
      } catch (error) {
        console.error('接口调试面板：', await block.innerText());
        throw error;
      }
      assert.equal(result.status(), status, `${method} ${path}`);
      await block.locator('.live-responses-table').waitFor();
      return result;
    }
    async function login(username) {
      const block = await operation('post', '/api/auth/login');
      await block.locator('input[type="text"]').fill(username);
      await block.locator('input[type="password"]').fill(process.env.DOCS_PASSWORD);
      await execute(block, 'POST', '/api/auth/login', 204);
    }

    await execute(await operation('get', '/api/orders'), 'GET', '/api/orders', 401);
    await login(process.env.DOCS_USERNAME);
    const me = await execute(await operation('get', '/api/auth/me'), 'GET', '/api/auth/me', 200);
    assert.equal((await me.json()).username, process.env.DOCS_USERNAME);
    const orders = await execute(await operation('get', '/api/orders'), 'GET', '/api/orders', 200);
    assert.deepEqual((await orders.json()).items, []);
    await execute(await operation('get', '/api/admin/orders'), 'GET', '/api/admin/orders', 403);

    const cart = await operation('put', '/api/cart/items/{productId}');
    await cart.locator('input[placeholder="productId"]').fill(product);
    await cart.locator('textarea').fill('{"quantity":2}');
    await execute(cart, 'PUT', `/api/cart/items/${product}`, 204);
    const items = await execute(await operation('get', '/api/cart/items'), 'GET', '/api/cart/items', 200);
    assert.equal((await items.json())[0].quantity, 2);
    const denied = await page.evaluate(async id => (await fetch(`/api/cart/items/${id}`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: '{"quantity":3}'
    })).status, product);
    assert.equal(denied, 403, '缺少CSRF的写请求仍须拒绝');

    await execute(await operation('post', '/api/auth/logout'), 'POST', '/api/auth/logout', 204);
    await execute(await operation('get', '/api/auth/me'), 'GET', '/api/auth/me', 401);
    await login(process.env.DOCS_ADMIN);
    await execute(await operation('get', '/api/admin/orders'), 'GET', '/api/admin/orders', 200);
    const update = await operation('patch', '/api/admin/products/{id}/status');
    await update.locator('input[placeholder="id"]').fill(product);
    await update.locator('textarea').fill('{"status":"OFF_SALE"}');
    await execute(update, 'PATCH', `/api/admin/products/${product}/status`, 200);
    await execute(await operation('post', '/api/auth/logout'), 'POST', '/api/auth/logout', 204);
    assert.deepEqual(errors, []);
    assert.deepEqual(external, [], '文档资源和请求均应来自本机应用');
    console.log('PASS 真实Swagger UI登录、Session、CSRF轮换、本人分页、角色权限、购物车与商品写入、退出；无脚本错误或外部请求');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
