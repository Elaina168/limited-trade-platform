import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { setTimeout as delay } from 'node:timers/promises'
import { login } from './trade.mjs'

function frame(delta, finish = null) {
  const chunk = {
    id: 'acceptance-stream', object: 'chat.completion.chunk', created: 1, model: 'deepseek-flash',
    choices: [{ index: 0, delta, finish_reason: finish }],
  }
  return 'data: ' + JSON.stringify(chunk) + '\n\n'
}

export async function startModel() {
  const rounds = []
  let attempts = 0
  let orderToLookUp
  const server = createServer((request, response) => {
    if (request.method === 'POST' && request.url === '/chat/completions') attempts++
    if (request.method !== 'POST' || request.url !== '/chat/completions' || rounds.length >= 3) {
      response.writeHead(404).end()
      return
    }
    const index = rounds.length
    const round = { response, closed: false }
    rounds.push(round)
    let timer
    response.once('close', () => { clearInterval(timer); round.closed = true })

    request.resume()
    request.once('end', () => {
      response.writeHead(200, { 'Content-Type': 'text/event-stream' })
      if (index === 0) {
        const toolCall = { index: 0, id: 'acceptance-order-tool', type: 'function',
          function: { name: 'get_my_order', arguments: JSON.stringify({ orderId: orderToLookUp }) } }
        response.end(frame({ role: 'assistant', tool_calls: [toolCall] }, 'tool_calls') +
          'data: {"id":"acceptance-stream","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":4,"total_tokens":14}}\n\n' +
          'data: [DONE]\n\n')
        return
      }
      // Spring AI 聚合 usage 时会保留一帧前瞻。
      response.write(frame({ role: 'assistant', content: index === 1 ? '验收首段' : '验收可停止片段' }))
      response.write(frame({ content: '' }))
      if (index === 2) timer = setInterval(() => response.write(frame({ content: '…' })), 150)
    })
  })
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolve)
  })
  return {
    url: 'http://127.0.0.1:' + server.address().port,
    rounds,
    setOrderToLookUp: id => { orderToLookUp = Number(id) },
    get attempts() { return attempts },
    close: () => new Promise((resolve, reject) => {
      server.close((error) => error ? reject(error) : resolve())
      server.closeAllConnections()
    }),
  }
}

export async function verifyAgent(browser, base, fixture, model, mysql) {
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } })
  page.setDefaultTimeout(8000)
  await login(page, base, fixture.buyer, fixture.password)

  const me = await page.request.get(base + '/api/auth/me')
  assert.equal(me.status(), 200)
  assert.equal(me.headers()['cache-control'], 'no-store')
  // 未放行的路径由 SecurityConfig 拒绝；已放行的订单详情保留业务 404。
  for (const path of ['/api', '/api/not-a-vue-route']) {
    const denied = await page.request.get(base + path, { headers: { Accept: 'application/json' } })
    assert.equal(denied.status(), 403)
    assert.equal(denied.headers()['cache-control'], 'no-store')
    assert.doesNotMatch(await denied.text(), /id="app"/)
  }
  const missing = await page.request.get(base + '/api/orders/9223372036854775807', {
    headers: { Accept: 'application/json' },
  })
  assert.equal(missing.status(), 404)
  assert.match(missing.headers()['content-type'], /application\/json/)
  assert.equal(missing.headers()['cache-control'], 'no-store')
  assert.equal((await missing.json()).status, 404)
  console.log('通过：认证响应 no-store，API 保留权限 403 和订单 404 JSON')

  const orderId = await mysql("SELECT id FROM trade_order WHERE status='COMPLETED'")
  await page.goto(base + '/orders/' + orderId)
  await page.getByRole('link', { name: '问这笔订单', exact: true }).click()
  await page.getByRole('heading', { name: '交易助手', exact: true }).waitFor()
  const orderQuestion = '请查询订单 #' + orderId + ' 的当前状态，并说明这笔订单是否已经确认收货。'
  const prefilled = page.getByLabel('你的问题', { exact: true })
  await page.waitForFunction(() => document.activeElement?.id === 'agent-question')
  assert.equal(await prefilled.inputValue(), '请查询订单 #' + orderId + ' 的当前状态。')
  assert.equal(model.attempts, 0)
  model.setOrderToLookUp(orderId)
  console.log('通过：从本人订单详情进入助手后预填和聚焦，等待买家发送')
  await page.goto(base + '/agent')
  await page.reload()
  await page.getByRole('heading', { name: '交易助手', exact: true }).waitFor()
  const question = page.getByLabel('你的问题', { exact: true })
  const send = page.getByRole('button', { name: '发送', exact: true })
  const chat = page.getByRole('log', { name: '聊天记录' })
  assert.equal(await page.getByRole('button', { name: '刷新会话', exact: true }).count(), 0)
  const turns = 'SELECT COUNT(*) FROM agent_conversation_turn'

  assert.equal(await page.getByRole('button', { name: '查一下我最近的待支付订单', exact: true }).count(), 1)
  await question.fill(orderQuestion)
  const [stream] = await Promise.all([
    page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/messages/stream')),
    send.click(),
  ])
  assert.equal(stream.status(), 200)
  assert.match(stream.headers()['content-type'], /^text\/event-stream/)
  assert.equal(stream.headers()['cache-control'], 'no-store')
  await chat.getByText('验收首段', { exact: true }).waitFor()
  assert.equal(model.rounds.length, 2)
  assert.equal(model.rounds[1].closed, false)
  assert.equal(await mysql(turns), '0')
  model.rounds[1].response.end(frame({ content: '与尾段' }, 'stop') +
    'data: {"id":"acceptance-stream","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":4,"total_tokens":14}}\n\n' +
    'data: [DONE]\n\n')
  await chat.getByText('验收首段与尾段', { exact: true }).waitFor()
  await page.getByText('回答已完成并确认保存。', { exact: true }).waitFor()
  assert.equal(await mysql(turns), '1')
  const streamPath = new URL(stream.url()).pathname
  const historyPath = streamPath.slice(0, -'/stream'.length)
  const savedHistory = await page.request.get(base + historyPath)
  assert.equal(savedHistory.status(), 200)
  const { clientTurnId } = stream.request().postDataJSON()
  assert.match(clientTurnId, /^[0-9a-f-]{36}$/)
  const savedTurn = (await savedHistory.json()).turns[0]
  assert.equal(savedTurn.clientTurnId, clientTurnId)
  assert.deepEqual(savedTurn.orders.map(order => [order.id, order.status]), [[Number(orderId), 'COMPLETED']])
  const orderCard = page.getByRole('region', { name: '关联订单', exact: true }).getByRole('link', { name: '查看订单', exact: true })
  await orderCard.waitFor()
  assert.equal(await orderCard.getAttribute('href'), '/orders/' + orderId)
  const conversationPath = historyPath.slice(0, -'/messages'.length)
  const conversationId = conversationPath.split('/').at(-1)
  const saved = page.locator('[data-conversation-id="' + conversationId + '"]')
  const list = await (await page.request.get(base + '/api/agent/conversations')).json()
  assert.equal(list.items.find(item => item.conversationId === conversationId).title, orderQuestion.slice(0, 22))
  await page.reload()
  await chat.getByText('验收首段与尾段', { exact: true }).waitFor()
  assert.equal(await saved.getAttribute('aria-pressed'), 'true')
  assert.match(await saved.textContent(), new RegExp(orderQuestion.slice(0, 22)))
  await orderCard.waitFor()
  await Promise.all([
    page.waitForResponse(response => new URL(response.url()).pathname === '/api/auth/logout'),
    page.getByRole('button', { name: '退出登录', exact: true }).click(),
  ])
  await login(page, base, fixture.buyer, fixture.password)
  await page.goto(base + '/agent')
  await chat.getByText('验收首段与尾段', { exact: true }).waitFor()

  const other = await browser.newPage()
  try {
    await login(other, base, fixture.admin, fixture.password)
    const otherList = await other.request.get(base + '/api/agent/conversations')
    assert.equal(otherList.status(), 200)
    assert.deepEqual((await otherList.json()).items, [])
    assert.equal((await other.request.get(base + historyPath)).status(), 404)
  } finally {
    await other.close()
  }
  console.log('通过：刷新和重登恢复本人问答，其他账号的列表不含该会话，历史读取返回 404')

  await question.fill('验收中止流式回答')
  await send.click()
  await chat.getByText('验收可停止片段', { exact: false }).waitFor()
  const snapshot = await page.request.get(base + historyPath)
  assert.equal(snapshot.status(), 200)
  const inProgress = await snapshot.json()
  assert.equal(inProgress.generating, true)
  assert.deepEqual(inProgress.turns.map(turn => turn.question), [orderQuestion])
  await page.getByRole('button', { name: '停止生成', exact: true }).click()
  await chat.getByText('已停止（未确认完成）', { exact: true }).waitFor()
  assert.equal(model.rounds.length, 3)
  const busy = 'SELECT COUNT(*) FROM agent_conversation WHERE generation_token IS NOT NULL'
  const deadline = Date.now() + 8000
  while (Date.now() < deadline && (!model.rounds[2].closed || await mysql(busy) !== '0')) await delay(150)
  assert.equal(model.rounds[2].closed, true, '停止后本地模型的 HTTP 连接仍未关闭')
  assert.equal(await mysql(busy), '0')
  assert.equal(await mysql(turns), '1')
  await page.getByText('本轮未保存回答。', { exact: true }).waitFor()
  assert.equal(model.rounds.length, 3)
  console.log('通过：首段先到，生成中只读到已提交历史，停止传到上游并释放生成资格')

  const button = name => page.getByRole('button', { name, exact: true })
  for (let i = 0; i < 5; i++) {
    const [created] = await Promise.all([
      page.waitForResponse(res => new URL(res.url()).pathname === '/api/agent/conversations' && res.request().method() === 'POST'),
      button('+ 新建对话').click(),
    ])
    assert.equal(created.status(), 201)
    assert.equal((await created.json()).title, '新对话')
  }
  await button('下一页').click()
  await saved.click()
  await chat.getByText('验收首段与尾段', { exact: true }).waitFor()
  await button('清空历史').click()
  await button('确认清空').click()
  await page.getByText('历史已清空，可以重新提问。', { exact: true }).waitFor()
  const cleared = await page.request.get(base + historyPath)
  assert.equal(cleared.status(), 200)
  assert.deepEqual((await cleared.json()).turns, [])
  await button('删除会话').click()
  await button('确认删除').click()
  await page.getByText('会话已删除。请选择会话或直接提问。', { exact: true }).waitFor()
  assert.equal((await page.request.get(base + historyPath)).status(), 404)
  assert.equal(model.rounds.length, 3)
  assert.equal(await mysql("SELECT COUNT(*) FROM agent_budget_call WHERE state='SETTLED' AND actual_input_tokens=10 AND actual_output_tokens=4 AND actual_total_tokens=14 AND charged_micros=52"), '2')
  assert.equal(await mysql("SELECT COUNT(*) FROM agent_budget_call WHERE state='UNKNOWN' AND actual_total_tokens IS NULL AND charged_micros IS NULL AND reserved_micros>0"), '1')
  const csrf = await (await page.request.get(base + '/api/auth/csrf')).json()
  const denied = await page.request.post(base + '/api/agent/chat', {
    headers: { [csrf.headerName]: csrf.token }, data: { message: '预算耗尽后不得出站' },
  })
  assert.equal(denied.status(), 429)
  assert.equal(model.attempts, 3)
  console.log('通过：工具轮次与回答轮次分别按实际 usage 结算 52 微元，取消且无 usage 时保留预留，预算不足在出站前返回 429')
  await page.setViewportSize({ width: 390, height: 844 })
  await button('我的会话').click()
  await button('+ 新建对话').waitFor()
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
  console.log('通过：桌面与移动助手布局、会话标题、真实只读工具订单卡、清空与删除')
}
