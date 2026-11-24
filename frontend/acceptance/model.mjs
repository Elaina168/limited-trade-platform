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
  const server = createServer((request, response) => {
    if (request.method === 'POST' && request.url === '/chat/completions') attempts++
    if (request.method !== 'POST' || request.url !== '/chat/completions' || rounds.length >= 2) {
      response.writeHead(404).end()
      return
    }
    const first = rounds.length === 0
    const round = { response, closed: false }
    rounds.push(round)
    let timer
    response.once('close', () => { clearInterval(timer); round.closed = true })

    request.resume()
    request.once('end', () => {
      response.writeHead(200, { 'Content-Type': 'text/event-stream' })
      // Spring AI 聚合 usage 时会保留一帧前瞻。
      response.write(frame({ role: 'assistant', content: first ? '验收首段' : '验收可停止片段' }))
      response.write(frame({ content: '' }))
      if (!first) timer = setInterval(() => response.write(frame({ content: '…' })), 150)
    })
  })
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolve)
  })
  return {
    url: 'http://127.0.0.1:' + server.address().port,
    rounds,
    get attempts() { return attempts },
    close: () => new Promise((resolve, reject) => {
      server.close((error) => error ? reject(error) : resolve())
      server.closeAllConnections()
    }),
  }
}

export async function verifyAgent(browser, base, fixture, model, mysql) {
  const page = await browser.newPage()
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

  const orderId = await mysql('SELECT id FROM trade_order')
  await page.goto(base + '/orders/' + orderId)
  await page.getByRole('link', { name: '问这笔订单', exact: true }).click()
  await page.getByRole('heading', { name: '交易助手', exact: true }).waitFor()
  assert.equal(await page.getByLabel('你的问题', { exact: true }).inputValue(), '请查询订单 #' + orderId + ' 的当前状态。')
  console.log('通过：从本人订单详情进入助手并准确预填订单号')
  await page.goto(base + '/agent')
  await page.reload()
  await page.getByRole('heading', { name: '交易助手', exact: true }).waitFor()
  const question = page.getByLabel('你的问题', { exact: true })
  const send = page.getByRole('button', { name: '发送', exact: true })
  const chat = page.getByRole('log', { name: '聊天记录' })
  const turns = 'SELECT COUNT(*) FROM agent_conversation_turn'

  await question.fill('验收首次流式回答')
  const [stream] = await Promise.all([
    page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/messages/stream')),
    send.click(),
  ])
  assert.equal(stream.status(), 200)
  assert.match(stream.headers()['content-type'], /^text\/event-stream/)
  assert.equal(stream.headers()['cache-control'], 'no-store')
  await chat.getByText('验收首段', { exact: true }).waitFor()
  assert.equal(model.rounds.length, 1)
  assert.equal(model.rounds[0].closed, false)
  assert.equal(await mysql(turns), '0')
  model.rounds[0].response.end(frame({ content: '与尾段' }, 'stop') +
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
  assert.equal((await savedHistory.json()).turns[0].clientTurnId, clientTurnId)
  const conversationPath = historyPath.slice(0, -'/messages'.length)
  const conversationId = conversationPath.split('/').at(-1)
  const saved = page.getByRole('button', { name: '会话 ' + conversationId.slice(0, 8), exact: true })
  await page.reload()
  await chat.getByText('验收首段与尾段', { exact: true }).waitFor()
  assert.equal(await saved.getAttribute('aria-pressed'), 'true')
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
  assert.deepEqual(inProgress.turns.map(turn => turn.question), ['验收首次流式回答'])
  await page.getByRole('button', { name: '停止生成', exact: true }).click()
  await chat.getByText('已停止（未确认完成）', { exact: true }).waitFor()
  assert.equal(model.rounds.length, 2)
  const busy = 'SELECT COUNT(*) FROM agent_conversation WHERE generation_token IS NOT NULL'
  const deadline = Date.now() + 8000
  while (Date.now() < deadline && (!model.rounds[1].closed || await mysql(busy) !== '0')) await delay(150)
  assert.equal(model.rounds[1].closed, true, '停止后本地模型的 HTTP 连接仍未关闭')
  assert.equal(await mysql(busy), '0')
  assert.equal(await mysql(turns), '1')
  await page.getByText('本轮未保存回答。', { exact: true }).waitFor()
  assert.equal(model.rounds.length, 2)
  console.log('通过：首段先到，生成中只读到已提交历史，停止传到上游并释放生成资格')

  const button = name => page.getByRole('button', { name, exact: true })
  for (let i = 0; i < 5; i++) {
    const [created] = await Promise.all([
      page.waitForResponse(res => new URL(res.url()).pathname === '/api/agent/conversations' && res.request().method() === 'POST'),
      button('新会话').click(),
    ])
    assert.equal(created.status(), 201)
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
  assert.equal(model.rounds.length, 2)
  assert.equal(await mysql("SELECT COUNT(*) FROM agent_budget_call WHERE state='SETTLED' AND actual_input_tokens=10 AND actual_output_tokens=4 AND actual_total_tokens=14 AND charged_micros=52"), '1')
  assert.equal(await mysql("SELECT COUNT(*) FROM agent_budget_call WHERE state='UNKNOWN' AND actual_total_tokens IS NULL AND charged_micros IS NULL AND reserved_micros>0"), '1')
  const csrf = await (await page.request.get(base + '/api/auth/csrf')).json()
  const denied = await page.request.post(base + '/api/agent/chat', {
    headers: { [csrf.headerName]: csrf.token }, data: { message: '预算耗尽后不得出站' },
  })
  assert.equal(denied.status(), 429)
  assert.equal(model.attempts, 2)
  console.log('通过：实际 usage 结算为 52 微元，取消且无 usage 时保留预留，预算不足在出站前返回 429')
  console.log('通过：Vue 能分页切换会话，清空保留当前会话，删除后历史返回 404')
}
