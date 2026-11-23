import assert from 'node:assert/strict'
import { createHash, randomUUID } from 'node:crypto'
import { appendFile, mkdir, readFile, writeFile } from 'node:fs/promises'
import { createConnection } from 'node:net'
import { availableParallelism, cpus, freemem, release, totalmem } from 'node:os'
import { monitorEventLoopDelay, performance } from 'node:perf_hooks'
import { resolve } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'

const clock = () => performance.timeOrigin + performance.now()
const json = (response) => JSON.parse(response.body.toString())
const token = () => randomUUID().replaceAll('-', '')
const concurrency = [1, 8, 24]
const warmMs = 1000
const measureMs = 3000
const repeats = 2

function redisInfo(port) {
  return new Promise((resolve, reject) => {
    const socket = createConnection({ host: '127.0.0.1', port })
    let buffer = Buffer.alloc(0)
    socket.setTimeout(3000, () => socket.destroy(new Error('Redis INFO 超时')))
    socket.once('error', reject)
    socket.on('close', () => reject(new Error('Redis INFO 响应不完整')))
    socket.once('connect', () => socket.write('*1\r\n$4\r\nINFO\r\n'))
    socket.on('data', (chunk) => {
      buffer = Buffer.concat([buffer, chunk])
      const end = buffer.indexOf('\r\n')
      if (end < 0) return
      const length = Number(buffer.toString('ascii', 1, end))
      if (buffer[0] !== 36 || !Number.isInteger(length)) {
        socket.destroy(new Error('Redis INFO 响应无效'))
      } else if (buffer.length >= end + 2 + length) {
        const info = new Map(buffer.toString('utf8', end + 2, end + 2 + length).split('\r\n')
          .filter((line) => line.includes(':')).map((line) => [line.slice(0, line.indexOf(':')), line.slice(line.indexOf(':') + 1)]))
        resolve(info)
        socket.end()
      }
    })
  })
}

export async function benchmark({ root, backend, mysql, redisPort, mqPort, runId, artifact, jvmLog }) {
  const output = resolve(root, 'tools/trade-benchmark/' + runId)
  await mkdir(output, { recursive: true })
  console.log('基线证据 ' + output)
  const rawPath = resolve(output, 'requests.jsonl')
  const reportPath = resolve(output, 'summary.json')
  const pwd = 'Bench_' + token()
  const baseline = {
    startedAt: new Date().toISOString(),
    node: process.version, os: release(), cpu: cpus()[0].model,
    logicalCpu: availableParallelism(), ramBytes: totalmem(), availableRamBytes: freemem(),
    mysql: mysql('SELECT @@version,@@transaction_isolation,@@innodb_buffer_pool_size,@@performance_schema,@@innodb_flush_log_at_trx_commit,@@max_connections'),
    redis: (await redisInfo(redisPort)).get('redis_version'),
    redisPort, mqPort, backend, warmMs, measureMs, repeats, concurrency, scanDelayMs: 1000,
    jarSha256: createHash('sha256').update(await readFile(artifact)).digest('hex'),
    cases: [],
  }
  const persist = () => writeFile(reportPath, JSON.stringify(baseline, null, 2) + '\n')

  async function request(path, { actor, method = 'GET', payload, form } = {}) {
    const headers = {}
    if (actor?.cookie) headers.Cookie = actor.cookie
    if (method === 'POST' && actor?.csrf) headers[actor.csrf.headerName] = actor.csrf.token
    if (payload) headers['Content-Type'] = form ? 'application/x-www-form-urlencoded' : 'application/json'
    const started = clock()
    try {
      const response = await fetch(backend + path, {
        method, headers, signal: AbortSignal.timeout(8000),
        body: payload && (form ? new URLSearchParams(payload).toString() : JSON.stringify(payload)),
      })
      const body = Buffer.from(await response.arrayBuffer())
      const session = response.headers.getSetCookie().find((cookie) => cookie.startsWith('JSESSIONID='))
      if (session && actor) actor.cookie = session.split(';')[0]
      return { status: response.status, started, ms: clock() - started, body }
    } catch (error) {
      if (!(error instanceof TypeError) && error.name !== 'TimeoutError') throw error
      return { status: 0, started, ms: clock() - started, body: Buffer.alloc(0) }
    }
  }

  async function actor(label, admin = false) {
    const credentials = { username: 'bench_' + runId + '_' + label, password: pwd }
    const session = {}
    session.csrf = json(await request('/api/auth/csrf', { actor: session }))
    const registered = await request('/api/auth/register', { actor: session, method: 'POST', payload: credentials })
    assert.equal(registered.status, 201)
    session.id = json(registered).id
    if (admin) mysql('UPDATE app_user SET role=\'ADMIN\' WHERE id=' + session.id)
    const signedIn = await request('/api/auth/login', { actor: session, method: 'POST', payload: credentials, form: true })
    assert.equal(signedIn.status, 204)
    session.csrf = json(await request('/api/auth/csrf', { actor: session }))
    return session
  }

  await writeFile(rawPath, '')
  await persist()
  const admin = await actor('admin', true)
  const buyers = []
  for (let index = 0; index < 12; index++) buyers.push(await actor('buyer' + index))
  const replay = await actor('retry')
  const viewer = await actor('page')
  const order = (actor, payload) => request('/api/orders', { actor, method: 'POST', payload })
  const act = (actor, id, operation) => request('/api/orders/' + id + '/' + operation, { actor, method: 'POST' })

  async function telemetry() {
    const snapshot = await request('/api/admin/operations/order-messages', { actor: admin })
    assert.equal(snapshot.status, 200)
    const mq = json(snapshot)
    assert.equal(mq.queues.available, true)
    const redis = await redisInfo(redisPort)
    const sql = Object.fromEntries(mysql("SHOW GLOBAL STATUS WHERE Variable_name IN (" +
      "'Com_select','Innodb_row_lock_waits','Innodb_row_lock_time','Threads_connected','Threads_running'," +
      "'Innodb_buffer_pool_read_requests','Innodb_buffer_pool_reads')").split(/\r?\n/)
      .map((line) => { const [key, value] = line.split('\t'); return [key, Number(value)] }))
    return {
      sql,
      redis: {
        hits: Number(redis.get('keyspace_hits')), misses: Number(redis.get('keyspace_misses')),
        setCalls: Number(/^calls=(\d+)/.exec(redis.get('cmdstat_set') ?? 'calls=0')[1]),
      },
      mq,
    }
  }

  function resultClass(status, scenario) {
    if (status === 201) return 'created'
    if (status === 200) return scenario === 'same-key' ? 'idempotent' : 'ok'
    if (status === 429) return 'limited'
    if (status === 409) return scenario === 'stock-race' ? 'out_of_stock' : 'state_conflict'
    return status === 0 || status >= 500 ? 'system_error' : 'unexpected'
  }

  async function collect(scenario, width, repeat, operation, issueWindowMs) {
    const before = await telemetry()
    const eventLoop = monitorEventLoopDelay({ resolution: 10 })
    eventLoop.enable()
    const cpu = process.cpuUsage()
    const start = clock()
    const observations = await operation(start)
    const end = clock()
    const clientCpu = process.cpuUsage(cpu)
    eventLoop.disable()
    const sorted = observations.map((sample) => sample.ms).sort((a, b) => a - b)
    const counts = {}
    for (const sample of observations) {
      const name = resultClass(sample.status, scenario)
      counts[name] = (counts[name] ?? 0) + 1
    }
    const offeredMs = issueWindowMs ?? (Math.max(...observations.map((o) => o.started)) - start)
    const summary = {
      scenario, width, repeat, start, end, offeredMs, counts, count: observations.length,
      offeredPerSecond: observations.length * 1000 / offeredMs,
      completedPerSecond: observations.length * 1000 / (end - start),
      p50: sorted[Math.ceil(sorted.length * 0.5) - 1],
      p95: sorted[Math.ceil(sorted.length * 0.95) - 1],
      p99: sorted[Math.ceil(sorted.length * 0.99) - 1],
      client: { cpuUs: clientCpu.user + clientCpu.system, rssBytes: process.memoryUsage.rss(),
        eventLoopP99Ms: eventLoop.count ? eventLoop.percentile(99) / 1e6 : null },
      before, after: await telemetry(),
    }
    await appendFile(rawPath, observations.map(({ started, ms, status }) =>
      JSON.stringify({ scenario, width, repeat, started, ms, status, class: resultClass(status, scenario) })).join('\n') + '\n')
    baseline.cases.push(summary)
    await persist()
    console.log(scenario + ' ' + width + '/' + repeat + ' ' + JSON.stringify(counts) +
      ' ' + summary.completedPerSecond.toFixed(1) + '/s p95=' + summary.p95.toFixed(2) + 'ms')
    assert.equal((counts.system_error ?? 0) + (counts.unexpected ?? 0), 0)
    return observations
  }

  mysql("INSERT INTO product(id,name,price,stock,status) VALUES (700,'限量夹具',20,5,'ON_SALE'),(701,'幂等夹具',20,5,'ON_SALE');\n" +
    "INSERT INTO activity(id,product_id,name,total_quantity,remaining_quantity,starts_at,ends_at) " +
    "VALUES(700,700,'基线活动',5,5,NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR);\n" +
    "INSERT INTO activity_coupon(id,activity_id,name,threshold_amount,discount_amount,total_quantity,remaining_quantity," +
    "claim_starts_at,claim_ends_at,use_starts_at,use_ends_at) " +
    "VALUES(700,700,'基线优惠券',10,2,12,12,NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR,NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR);")
  const claims = []
  for (const buyer of buyers) {
    const claimed = await request('/api/coupons/700/claim', { actor: buyer, method: 'POST' })
    assert.equal(claimed.status, 200)
    claims.push(json(claimed).id)
  }
  const race = await collect('stock-race', buyers.length, 1, () => Promise.all(buyers.map((buyer, index) =>
    order(buyer, { productId: 700, quantity: 1, requestId: token(), activityId: 700, userCouponId: claims[index] }))))
  assert.deepEqual(race.map((r) => r.status).sort(), [...Array(5).fill(201), ...Array(7).fill(409)])
  const winners = race.flatMap((result, index) => result.status === 201 ? [{ owner: buyers[index], order: json(result) }] : [])
  assert.ok(winners.every(({ order }) => order.totalAmount === 18 && order.discountAmount === 2))
  assert.equal(mysql('SELECT stock FROM product WHERE id=700'), '0')
  assert.equal(mysql('SELECT remaining_quantity FROM activity WHERE id=700'), '0')
  assert.equal(mysql('SELECT COUNT(*) FROM user_coupon WHERE status=\'RESERVED\''), '5')

  const sameInput = { productId: 701, quantity: 1, requestId: token() }
  const retries = await collect('same-key', 8, 1, () => Promise.all(Array.from({ length: 8 }, () => order(replay, sameInput))))
  assert.deepEqual(retries.map((r) => r.status).sort(), [200, 200, 200, 200, 201, 429, 429, 429])
  assert.equal(new Set(retries.filter((r) => r.status < 300).map((r) => json(r).id)).size, 1)
  assert.equal(mysql('SELECT stock FROM product WHERE id=701'), '4')

  const [live, expiredPay, expiredCancel] = winners
  const stateRace = await collect('pay-cancel', 2, 1, () => Promise.all([
    act(live.owner, live.order.id, 'pay'), act(live.owner, live.order.id, 'cancel'),
  ]))
  assert.deepEqual(stateRace.map((r) => r.status).sort(), [200, 409])
  mysql('UPDATE trade_order SET expires_at=NOW()-INTERVAL 1 MINUTE WHERE id IN (' +
    expiredPay.order.id + ',' + expiredCancel.order.id + ')')
  const expired = await collect('expired-pay-cancel-close', 2, 1, () => Promise.all([
    act(expiredPay.owner, expiredPay.order.id, 'pay'), act(expiredCancel.owner, expiredCancel.order.id, 'cancel'),
  ]))
  assert.equal(expired[0].status, 409)
  const expiredIds = [expiredPay.order.id, expiredCancel.order.id].join(',')
  const expireQuery = "SELECT COUNT(*) FROM trade_order WHERE status IN ('CANCELED','CLOSED') AND id IN (" + expiredIds + ')'
  for (let retry = 0; retry < 30 && mysql(expireQuery) !== '2'; retry++) await delay(200)
  assert.equal(mysql(expireQuery), '2')
  assert.equal(mysql('SELECT status FROM trade_order WHERE id=' + expiredPay.order.id), 'CLOSED')

  const releases = Number(mysql("SELECT COUNT(*) FROM trade_order o JOIN order_item i ON i.order_id=o.id " +
    "WHERE i.product_id=700 AND o.status IN ('CANCELED','CLOSED')"))
  assert.equal(Number(mysql('SELECT stock FROM product WHERE id=700')), releases)
  assert.equal(Number(mysql('SELECT remaining_quantity FROM activity WHERE id=700')), releases)
  assert.equal(mysql('SELECT remaining_quantity FROM activity_coupon WHERE id=700'), '0')
  assert.equal(Number(mysql("SELECT COUNT(*) FROM user_coupon WHERE status='AVAILABLE'")), 7 + releases)
  assert.equal(mysql("SELECT COUNT(*) FROM user_coupon c JOIN trade_order o ON o.id=c.order_id WHERE " +
    "(c.status='USED' AND o.status<>'PAID') OR (c.status='RESERVED' AND o.status<>'PENDING_PAYMENT')"), '0')
  assert.equal(mysql('SELECT COUNT(*) FROM order_coupon'), '5')
  assert.equal(mysql('SELECT COUNT(*) FROM trade_order'), '6')
  const sent = "SELECT COUNT(*) FROM order_timeout_outbox WHERE status='SENT'"
  for (let retry = 0; retry < 25 && mysql(sent) !== '6'; retry++) await delay(250)
  assert.equal(mysql(sent), '6')
  baseline.reconciled = { realOrders: 6, couponSnapshots: 5, stockAndQuotaRestored: releases, outboxSent: 6,
    finalStates: mysql('SELECT status,COUNT(*) FROM trade_order GROUP BY status') }
  await persist()

  const catalog = Array.from({ length: 1000 }, (_, i) => "(" + (1000 + i) + ",'Bench product " + i + "',20,1000,'ON_SALE')")
  const pages = Array.from({ length: 80 }, (_, i) => '(' + viewer.id + ",20,'CANCELED','page-" + i +
    "',NOW()-INTERVAL 1 HOUR,NOW()-INTERVAL 45 MINUTE,NOW()-INTERVAL 44 MINUTE)")
  mysql('INSERT INTO product(id,name,price,stock,status) VALUES ' + catalog.join(',') + ';\n' +
    'INSERT INTO trade_order(user_id,total_amount,status,request_id,created_at,expires_at,ended_at) VALUES ' + pages.join(',') + ';\n' +
    "INSERT INTO order_item(order_id,product_id,product_name,unit_price,quantity,line_amount) " +
    "SELECT id,1000,'Bench product 0',20,1,20 FROM trade_order WHERE user_id=" + viewer.id + ';')
  // 夹具直接写入隔离库。通过真实取消请求触发缓存失效后再测列表。
  const idem = json(retries.find((r) => r.status === 201))
  assert.equal((await act(replay, idem.id, 'cancel')).status, 200)
  const sampleCatalog = await request('/api/products')
  assert.equal(sampleCatalog.status, 200)
  assert.equal(json(sampleCatalog).length, 1003)
  const pagesCheck = json(await request('/api/orders?page=4&size=20', { actor: viewer }))
  assert.equal(pagesCheck.total, 80)
  assert.equal(pagesCheck.items.length, 20)
  baseline.data = { onSaleProducts: 1003, syntheticOrdersForPaging: 80, realOrders: 6 }
  const scenarios = [
    ['cached-products', () => request('/api/products')],
    ['search-page', (i) => request('/api/products/search?page=' + (i % 50 + 1) + '&size=20&keyword=Bench')],
    ['owner-order-page', (i) => request('/api/orders?page=' + (i % 4 + 1) + '&size=20', { actor: viewer })],
  ]

  async function readWindow(call, width, start, duration, retain) {
    const results = []
    const until = start + duration
    let sequence = 0
    await Promise.all(Array.from({ length: width }, async () => {
      while (clock() < until) {
        const response = await call(sequence++)
        if (retain) results.push({ status: response.status, started: response.started, ms: response.ms })
        else assert.equal(response.status, 200)
      }
    }))
    return results
  }
  for (const [scenario, call] of scenarios) {
    for (const width of concurrency) {
      for (let repeat = 1; repeat <= repeats; repeat++) {
        await readWindow(call, width, clock(), warmMs, false)
        const values = await collect(scenario, width, repeat,
          (start) => readWindow(call, width, start, measureMs, true), measureMs)
        assert.ok(values.every(({ status }) => status === 200))
      }
    }
  }
  baseline.statementDigests = mysql('SELECT COUNT_STAR,ROUND(SUM_TIMER_WAIT/1000000000,3),DIGEST_TEXT FROM ' +
    'performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME=DATABASE() ORDER BY SUM_TIMER_WAIT DESC LIMIT 12')
  const metrics = (await readFile(jvmLog, 'utf8')).trimEnd().split(/\r?\n/).filter((line) => line.startsWith('{'))
  assert.ok(metrics.length > 0, 'JVM 没有采样结果')
  await writeFile(resolve(output, 'jvm.jsonl'), metrics.join('\n') + '\n')
  baseline.finishedAt = new Date().toISOString()
  await persist()
  console.log('通过：库存、活动名额、券、订单与 Outbox 对账；基线与原始样本已保存')
}
