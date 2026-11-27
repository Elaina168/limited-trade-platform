import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { createHash, randomBytes } from 'node:crypto'
import { closeSync, openSync } from 'node:fs'
import { access, mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises'
import http from 'node:http'
import { createConnection } from 'node:net'
import { availableParallelism, cpus, freemem, release, totalmem } from 'node:os'
import { performance } from 'node:perf_hooks'
import { resolve } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'

const frontendDir = fileURLToPath(new URL('../', import.meta.url))
const root = resolve(frontendDir, '..')
const artifact = resolve(root, 'target/limited-trade-platform-0.0.1-SNAPSHOT.jar')
const loopback = '127.0.0.1'
const schema = 'limited_seckill_bench'
const runId = randomBytes(5).toString('hex')
const dbPwd = 'BenchDb_' + randomBytes(12).toString('hex')
const userPwd = 'BuyerPass_2026!'

const mysqlPort = Number(process.env.SECKILL_MYSQL_PORT ?? 23318)
const redisPort = Number(process.env.SECKILL_REDIS_PORT ?? 16388)
const mqPort = Number(process.env.SECKILL_MQ_PORT ?? 5688)
const mqDistPort = Number(process.env.SECKILL_MQ_DIST_PORT ?? 25688)
const javaPort = Number(process.env.SECKILL_JAVA_PORT ?? 28088)
const mysqlServer = process.env.ACCEPTANCE_MYSQLD ?? 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysqld.exe'
const mysqlClient = process.env.ACCEPTANCE_MYSQL ?? 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe'
const javaBin = process.env.ACCEPTANCE_JAVA ?? 'C:/Program Files/Java/jdk-21.0.10/bin/java.exe'

const httpAgent = new http.Agent({ keepAlive: true, maxSockets: 2500, maxFreeSockets: 512 })

function httpRequest(port, path, { method = 'GET', headers = {}, body } = {}) {
  return new Promise((resolvePromise) => {
    const started = performance.now()
    const payload = body === undefined ? null : Buffer.from(body, 'utf8')
    const reqHeaders = { ...headers }
    if (payload) reqHeaders['Content-Length'] = payload.length
    const req = http.request({
      hostname: loopback,
      port,
      path,
      method,
      headers: reqHeaders,
      agent: httpAgent,
      timeout: 15_000,
    }, (res) => {
      const chunks = []
      res.on('data', chunk => chunks.push(chunk))
      res.on('end', () => {
        const ms = performance.now() - started
        const text = Buffer.concat(chunks).toString('utf8')
        const setCookies = res.headers['set-cookie'] ?? []
        resolvePromise({ status: res.statusCode ?? 0, ms, text, setCookies })
      })
    })
    req.on('timeout', () => req.destroy(new Error('HTTP timeout')))
    req.on('error', () => resolvePromise({ status: 0, ms: performance.now() - started, text: '', setCookies: [] }))
    if (payload) req.write(payload)
    req.end()
  })
}

function percentile(sorted, p) {
  if (!sorted.length) return 0
  const idx = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1))
  return Number(sorted[idx].toFixed(2))
}

function redisCommand(host, port, ...parts) {
  return new Promise((resolvePromise, reject) => {
    const socket = createConnection({ host, port })
    let buf = ''
    socket.setTimeout(3000, () => socket.destroy(new Error('Redis timeout')))
    socket.once('error', reject)
    socket.once('connect', () => {
      const cmd = '*' + parts.length + '\r\n' + parts.map(p => '$' + Buffer.byteLength(String(p)) + '\r\n' + p + '\r\n').join('')
      socket.write(cmd)
    })
    socket.on('data', chunk => {
      buf += chunk.toString('utf8')
      const complete = (buf.startsWith('+') || buf.startsWith(':') || buf.startsWith('-')) && buf.endsWith('\r\n')
        || buf.startsWith('$-1\r\n')
        || (buf.startsWith('$') && buf.indexOf('\r\n') > 0 && buf.length >= buf.indexOf('\r\n') + 2 + Number(buf.slice(1, buf.indexOf('\r\n'))) + 2)
      if (complete) {
        socket.end()
        resolvePromise(buf)
      }
    })
  })
}

function startProc(dir, name, exe, args, options = {}) {
  const logFd = openSync(resolve(dir, name + '.log'), 'w')
  try {
    // 保持 stdin 管道打开，防止 wsl.exe 在标准输入关闭时自动终止子会话。
    const child = spawn(exe, args, { ...options, stdio: ['pipe', logFd, logFd] })
    const proc = { name, child, finished: false }
    proc.closed = new Promise(r => child.once('close', () => { proc.finished = true; r() }))
    return proc
  } finally {
    closeSync(logFd)
  }
}

async function waitReady(label, check, timeoutMs = 60_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      if (await check()) return
    } catch {}
    await delay(250)
  }
  throw new Error(label + ' 启动就绪超时')
}

async function stopProc(proc) {
  if (!proc || proc.finished) return
  if (process.platform === 'win32' && proc.child.pid) {
    spawnSync('taskkill.exe', ['/PID', String(proc.child.pid), '/T', '/F'], { stdio: 'ignore' })
  } else {
    proc.child.kill('SIGKILL')
  }
  await Promise.race([proc.closed, delay(5000)])
}

let workDir
let mysqlProc
let redisProc
let mqProc
let javaProc
let mqEnv

try {
  await access(artifact)
  const targetDir = resolve(root, 'target')
  workDir = await mkdtemp(resolve(targetDir, 'seckill-bench-'))
  console.log('隔离压测运行目录: ' + workDir)

  // 1. 启动隔离 WSL Redis（直传参数避免 bash 引号被 Windows 转义拆分，并直连 WSL 虚拟网卡 IP）
  const redisHost = spawnSync('wsl.exe', ['-d', 'Ubuntu-22.04', '--', 'hostname', '-I'], { encoding: 'utf8' })
    .stdout.trim().split(/\s+/)[0] || loopback
  redisProc = startProc(workDir, 'redis', 'wsl.exe', [
    '-d', 'Ubuntu-22.04', '--', 'redis-server',
    '--bind', '0.0.0.0', '--protected-mode', 'no', '--port', String(redisPort),
    '--dir', '/tmp', '--save', '', '--appendonly', 'no',
  ])
  await waitReady('Redis', async () => (await redisCommand(redisHost, redisPort, 'PING')).includes('+PONG'), 25_000)
  console.log(`Redis 已就绪于 ${redisHost}:${redisPort}`)

  // 2. 启动隔离便携版 RabbitMQ
  const mqBase = resolve(workDir, 'rabbitmq-base')
  await mkdir(mqBase, { recursive: true })
  await writeFile(resolve(mqBase, 'rabbitmq.conf'), [
    `listeners.tcp.local = 127.0.0.1:${mqPort}`,
    `distribution.listener.port_range.min = ${mqDistPort}`,
    `distribution.listener.port_range.max = ${mqDistPort}`,
  ].join('\n') + '\n')
  await writeFile(resolve(mqBase, 'enabled_plugins'), '[].\n')
  const nodeName = `seckill_${runId}@localhost`
  mqEnv = {
    ...process.env,
    ERLANG_HOME: resolve(root, 'tools/Erlang OTP'),
    RABBITMQ_BASE: mqBase,
    RABBITMQ_CONFIG_FILE: resolve(mqBase, 'rabbitmq'),
    RABBITMQ_ENABLED_PLUGINS_FILE: resolve(mqBase, 'enabled_plugins'),
    RABBITMQ_NODENAME: nodeName,
    ERL_EPMD_ADDRESS: '127.0.0.1',
    RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS: '+S 4:4 -kernel inet_dist_use_interface {127,0,0,1}',
  }
  mqProc = startProc(workDir, 'rabbitmq', 'cmd.exe', [
    '/c', resolve(root, 'tools/rabbitmq_server-4.3.6/sbin/rabbitmq-server.bat'),
  ], { env: mqEnv })
  await waitReady('RabbitMQ', () => new Promise(r => {
    const s = createConnection({ host: loopback, port: mqPort }, () => { s.end(); r(true) })
    s.on('error', () => r(false))
  }), 60_000)
  console.log('RabbitMQ 已就绪于端口 ' + mqPort)

  // 3. 启动隔离 MySQL
  const mysqlData = resolve(workDir, 'mysql-data')
  const initSql = resolve(workDir, 'init.sql')
  await mkdir(resolve(workDir, 'config'), { recursive: true })
  await writeFile(resolve(workDir, 'config/db.properties'), '')
  await writeFile(resolve(workDir, 'config/rabbitmq.properties'), '')
  await writeFile(initSql, `ALTER USER 'root'@'localhost' IDENTIFIED BY '${dbPwd}';\nCREATE DATABASE ${schema} CHARACTER SET utf8mb4;\n`)
  const initRes = spawnSync(mysqlServer, ['--no-defaults', '--initialize-insecure', '--datadir=' + mysqlData], { cwd: workDir, encoding: 'utf8' })
  assert.equal(initRes.status, 0, initRes.stderr)
  mysqlProc = startProc(workDir, 'mysql', mysqlServer, [
    '--no-defaults', '--datadir=' + mysqlData, '--bind-address=' + loopback, '--port=' + mysqlPort,
    '--mysqlx=OFF', '--skip-log-bin', '--max-connections=300', '--innodb-buffer-pool-size=256M',
    '--performance-schema=ON', '--init-file=' + initSql,
  ], { cwd: workDir })

  const mysqlArgs = ['--no-defaults', '--protocol=TCP', '--host=' + loopback, '--port=' + mysqlPort, '--user=root', '--default-character-set=utf8mb4', '--batch', '--skip-column-names']
  const mysqlExec = (sql, db = schema) => {
    const r = spawnSync(mysqlClient, db ? [...mysqlArgs, db] : mysqlArgs, {
      input: sql, encoding: 'utf8', env: { ...process.env, MYSQL_PWD: dbPwd }, timeout: 30_000,
    })
    assert.equal(r.status, 0, r.stderr)
    return r.stdout.trim()
  }
  await waitReady('MySQL', () => {
    const r = spawnSync(mysqlClient, [...mysqlArgs, schema], { input: 'SELECT 1', env: { ...process.env, MYSQL_PWD: dbPwd } })
    return r.status === 0
  })
  await rm(initSql, { force: true })

  const sqlDir = resolve(root, 'sql')
  const migrations = (await readdir(sqlDir)).filter(n => /^\d{3}_.+\.sql$/.test(n)).sort()
  const ddl = await Promise.all(['schema.sql', ...migrations].map(n => readFile(resolve(sqlDir, n), 'utf8')))
  mysqlExec(ddl.join('\n'))
  console.log('MySQL 已完成 schema 与 002..017 迁移初始化')

  // 4. 启动 Java 应用（开启 Redis 预热秒杀 + RabbitMQ 异步消费；提高单用户限流阈值以便同账号防刷专项直接命中秒杀 Lua 限购）
  javaProc = startProc(workDir, 'java', javaBin, [
    '-Xms512m', '-Xmx1024m', '-Duser.timezone=Asia/Shanghai',
    '-jar', artifact,
    '--server.address=' + loopback,
    '--server.port=' + javaPort,
    '--server.tomcat.threads.max=250',
    '--server.tomcat.accept-count=2500',
    '--spring.datasource.hikari.maximum-pool-size=24',
    '--spring.data.redis.host=' + redisHost,
    '--spring.data.redis.port=' + redisPort,
    '--app.agent.enabled=false',
    '--app.agent.rag.enabled=false',
    '--app.product-cache.enabled=true',
    '--app.product-cache.key=seckillbench:' + runId + ':catalog',
    '--app.order-rate-limit.enabled=true',
    '--app.order-rate-limit.max-requests=100',
    '--app.order-rate-limit.window-seconds=10',
    '--app.order-rate-limit.key-prefix=seckillbench:' + runId + ':limit',
    '--app.order-message.enabled=true',
    '--app.order-message.monitor.enabled=false',
    '--app.order-message.name-prefix=seckillbench-' + runId,
    '--app.seckill.enabled=true',
    '--app.seckill.key-prefix=seckillbench:' + runId + ':sk',
  ], {
    cwd: workDir,
    env: {
      ...process.env,
      DB_URL: `jdbc:mysql://${loopback}:${mysqlPort}/${schema}?allowPublicKeyRetrieval=true&sslMode=DISABLED`,
      DB_USERNAME: 'root',
      DB_PASSWORD: dbPwd,
      MQ_HOST: loopback,
      MQ_PORT: String(mqPort),
      MQ_USERNAME: 'guest',
      MQ_PASSWORD: 'guest',
      MQ_VIRTUAL_HOST: '/',
    },
  })
  await waitReady('Java', async () => (await httpRequest(javaPort, '/api/ping')).status === 200)
  console.log('Java 应用已就绪于端口 ' + javaPort)

  // 5. 注册 1 个标准账号提取 BCrypt 哈希，随后批量写入 2000 名买家与收货地址并并发登录拿会话与 CSRF
  const seedSession = {}
  const c0 = await httpRequest(javaPort, '/api/auth/csrf')
  const cookie0 = (c0.setCookies.find(c => c.startsWith('JSESSIONID=')) ?? '').split(';')[0]
  const csrf0 = JSON.parse(c0.text)
  const reg0 = await httpRequest(javaPort, '/api/auth/register', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      [csrf0.headerName]: csrf0.token,
      ...(cookie0 ? { Cookie: cookie0 } : {}),
    },
    body: JSON.stringify({ username: 'seed_buyer', password: userPwd }),
  })
  assert.equal(reg0.status, 201)
  const passwordHash = mysqlExec("SELECT password_hash FROM app_user WHERE username='seed_buyer'")
  assert.ok(passwordHash.startsWith('$2'))

  const totalBuyers = 2000
  const userRows = []
  const addrRows = []
  for (let i = 1; i <= totalBuyers; i++) {
    const uid = 10000 + i
    userRows.push(`(${uid},'buyer_${i}','${passwordHash}','USER')`)
    addrRows.push(`(${uid},${uid},'买家${i}','13800138000','浙江省','杭州市','西湖区','压测路${i}号',1)`)
  }
  for (let offset = 0; offset < totalBuyers; offset += 500) {
    mysqlExec('INSERT INTO app_user(id,username,password_hash,role) VALUES ' + userRows.slice(offset, offset + 500).join(','))
    mysqlExec('INSERT INTO user_address(id,user_id,recipient_name,phone,province,city,district,detail_address,is_default) VALUES ' + addrRows.slice(offset, offset + 500).join(','))
  }

  async function loginBuyer(index) {
    const uid = 10000 + index
    const c1 = await httpRequest(javaPort, '/api/auth/csrf')
    const cookie1 = (c1.setCookies.find(c => c.startsWith('JSESSIONID=')) ?? '').split(';')[0]
    const token1 = JSON.parse(c1.text)
    const form = new URLSearchParams({ username: `buyer_${index}`, password: userPwd }).toString()
    const lRes = await httpRequest(javaPort, '/api/auth/login', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        [token1.headerName]: token1.token,
        ...(cookie1 ? { Cookie: cookie1 } : {}),
      },
      body: form,
    })
    assert.equal(lRes.status, 204)
    const sessionCookie = (lRes.setCookies.find(c => c.startsWith('JSESSIONID=')) ?? cookie1).split(';')[0]
    const c2 = await httpRequest(javaPort, '/api/auth/csrf', { headers: { Cookie: sessionCookie } })
    const token2 = JSON.parse(c2.text)
    return { id: uid, addressId: uid, cookie: sessionCookie, csrfHeader: token2.headerName, csrfToken: token2.token }
  }

  console.log('正在并发建立 2,000 个独立认证会话...')
  const buyers = new Array(totalBuyers)
  const batchSize = 40
  for (let start = 0; start < totalBuyers; start += batchSize) {
    const tasks = []
    for (let j = start; j < Math.min(totalBuyers, start + batchSize); j++) {
      tasks.push(loginBuyer(j + 1).then(actor => { buyers[j] = actor }))
    }
    await Promise.all(tasks)
  }
  console.log('2,000 个买家会话准备完毕')

  function sqlStats() {
    const raw = mysqlExec("SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits','Innodb_row_lock_time')")
    return Object.fromEntries(raw.split(/\r?\n/).map(line => {
      const [k, v] = line.split('\t')
      return [k, Number(v)]
    }))
  }

  // JVM 与连接预热
  mysqlExec("INSERT INTO product(id,name,price,stock,status) VALUES (900,'预热商品',9.90,100,'ON_SALE');" +
    "INSERT INTO activity(id,product_id,name,total_quantity,remaining_quantity,per_user_limit,starts_at,ends_at) VALUES " +
    "(900,900,'预热活动',20,20,1,DATE_SUB(NOW(),INTERVAL 10 MINUTE),DATE_ADD(NOW(),INTERVAL 1 HOUR));")
  await Promise.all(buyers.slice(0, 10).map((b, i) => httpRequest(javaPort, '/api/activities/900/seckill', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Cookie: b.cookie, [b.csrfHeader]: b.csrfToken },
    body: JSON.stringify({ productId: 900, quantity: 1, addressId: b.addressId, requestId: `warm-${i}` }),
  })))
  await waitReady('预热队列落库', async () => mysqlExec('SELECT COUNT(*) FROM order_item WHERE activity_id=900') === '10')

  async function runSyncBaseline(concurrency, quota, actId, prodId) {
    mysqlExec(`INSERT INTO product(id,name,price,stock,status) VALUES (${prodId},'同步秒杀商品_${concurrency}',99.00,${quota * 2},'ON_SALE');` +
      `INSERT INTO activity(id,product_id,name,total_quantity,remaining_quantity,per_user_limit,starts_at,ends_at) VALUES ` +
      `(${actId},${prodId},'同步秒杀_${concurrency}',${quota},${quota},1,DATE_SUB(NOW(),INTERVAL 10 MINUTE),DATE_ADD(NOW(),INTERVAL 1 HOUR));`)
    const beforeSql = sqlStats()
    const t0 = performance.now()
    const responses = await Promise.all(buyers.slice(0, concurrency).map((b, idx) =>
      httpRequest(javaPort, '/api/orders', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Cookie: b.cookie, [b.csrfHeader]: b.csrfToken },
        body: JSON.stringify({ productId: prodId, activityId: actId, quantity: 1, addressId: b.addressId, requestId: `sync-${concurrency}-${idx}` }),
      })
    ))
    const wallMs = performance.now() - t0
    const afterSql = sqlStats()
    const latencies = responses.map(r => r.ms).sort((a, b) => a - b)
    const statusCounts = {}
    for (const r of responses) statusCounts[r.status] = (statusCounts[r.status] ?? 0) + 1
    const dbOrders = Number(mysqlExec(`SELECT COUNT(*) FROM order_item WHERE activity_id=${actId}`))
    const dbRemaining = Number(mysqlExec(`SELECT remaining_quantity FROM activity WHERE id=${actId}`))
    const dbStock = Number(mysqlExec(`SELECT stock FROM product WHERE id=${prodId}`))
    const distinctBuyers = Number(mysqlExec(`SELECT COUNT(DISTINCT o.user_id) FROM trade_order o JOIN order_item oi ON o.id=oi.order_id WHERE oi.activity_id=${actId}`))
    assert.equal(dbOrders, quota, `同步压测成交数应恰为 ${quota}，实际 ${dbOrders}`)
    assert.equal(dbRemaining, 0)
    assert.equal(dbStock, quota)
    assert.equal(distinctBuyers, quota)
    return {
      mode: 'mysql-for-update-sync',
      concurrency,
      quota,
      httpWallMs: Number(wallMs.toFixed(2)),
      throughputReqPerSec: Number((concurrency / (wallMs / 1000)).toFixed(1)),
      p50Ms: percentile(latencies, 50),
      p95Ms: percentile(latencies, 95),
      p99Ms: percentile(latencies, 99),
      statusCounts,
      innodbRowLockWaitsDelta: afterSql.Innodb_row_lock_waits - beforeSql.Innodb_row_lock_waits,
      innodbRowLockTimeMsDelta: afterSql.Innodb_row_lock_time - beforeSql.Innodb_row_lock_time,
      reconciliation: { dbOrders, dbRemaining, dbStock, distinctBuyers },
    }
  }

  async function runAsyncSeckill(concurrency, quota, actId, prodId) {
    mysqlExec(`INSERT INTO product(id,name,price,stock,status) VALUES (${prodId},'异步秒杀商品_${concurrency}',99.00,${quota * 2},'ON_SALE');` +
      `INSERT INTO activity(id,product_id,name,total_quantity,remaining_quantity,per_user_limit,starts_at,ends_at) VALUES ` +
      `(${actId},${prodId},'异步秒杀_${concurrency}',${quota},${quota},1,DATE_SUB(NOW(),INTERVAL 10 MINUTE),DATE_ADD(NOW(),INTERVAL 1 HOUR));`)
    // 先调用一次查询触发读路径，再由首个秒杀或预热脚本装载 Redis
    await httpRequest(javaPort, `/api/activities/${actId}`)
    const beforeSql = sqlStats()
    const t0 = performance.now()
    const responses = await Promise.all(buyers.slice(0, concurrency).map((b, idx) =>
      httpRequest(javaPort, `/api/activities/${actId}/seckill`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Cookie: b.cookie, [b.csrfHeader]: b.csrfToken },
        body: JSON.stringify({ productId: prodId, quantity: 1, addressId: b.addressId, requestId: `async-${concurrency}-${idx}` }),
      })
    ))
    const httpWallMs = performance.now() - t0
    const afterBurstSql = sqlStats()

    await waitReady(`异步秒杀_${concurrency}落库完成`, async () =>
      Number(mysqlExec(`SELECT COUNT(*) FROM activity_purchase_reservation WHERE activity_id=${actId} AND status='CREATED'`)) === quota,
    30_000)
    const drainWallMs = performance.now() - t0

    const latencies = responses.map(r => r.ms).sort((a, b) => a - b)
    const statusCounts = {}
    for (const r of responses) statusCounts[r.status] = (statusCounts[r.status] ?? 0) + 1
    assert.equal(statusCounts[202], quota, `异步秒杀 202 预扣数应恰为 ${quota}`)
    assert.equal(statusCounts[409], concurrency - quota, `其余请求应全部被 Lua 原子拦截返回 409`)

    const dbOrders = Number(mysqlExec(`SELECT COUNT(*) FROM order_item WHERE activity_id=${actId}`))
    const dbRemaining = Number(mysqlExec(`SELECT remaining_quantity FROM activity WHERE id=${actId}`))
    const dbStock = Number(mysqlExec(`SELECT stock FROM product WHERE id=${prodId}`))
    const distinctBuyers = Number(mysqlExec(`SELECT COUNT(DISTINCT o.user_id) FROM trade_order o JOIN order_item oi ON o.id=oi.order_id WHERE oi.activity_id=${actId}`))
    const redisQuotaRaw = await redisCommand(redisHost, redisPort, 'GET', `seckillbench:${runId}:sk:act:${actId}:quota`)
    const redisInflightRaw = await redisCommand(redisHost, redisPort, 'HLEN', `seckillbench:${runId}:sk:act:${actId}:inflight`)
    assert.equal(dbOrders, quota)
    assert.equal(dbRemaining, 0)
    assert.equal(dbStock, quota)
    assert.equal(distinctBuyers, quota)
    assert.match(redisQuotaRaw, /\r\n0\r\n/)
    assert.match(redisInflightRaw, /:0\r\n/)

    return {
      mode: 'redis-lua-rabbitmq-async',
      concurrency,
      quota,
      httpWallMs: Number(httpWallMs.toFixed(2)),
      throughputReqPerSec: Number((concurrency / (httpWallMs / 1000)).toFixed(1)),
      asyncDrainTotalMs: Number(drainWallMs.toFixed(2)),
      p50Ms: percentile(latencies, 50),
      p95Ms: percentile(latencies, 95),
      p99Ms: percentile(latencies, 99),
      statusCounts,
      innodbRowLockWaitsDuringHttpBurst: afterBurstSql.Innodb_row_lock_waits - beforeSql.Innodb_row_lock_waits,
      innodbRowLockTimeMsDuringHttpBurst: afterBurstSql.Innodb_row_lock_time - beforeSql.Innodb_row_lock_time,
      reconciliation: { dbOrders, dbRemaining, dbStock, distinctBuyers, redisQuota: 0, redisInflight: 0 },
    }
  }

  console.log('运行 500 并发抢 50 件对比实验...')
  const sync500 = await runSyncBaseline(500, 50, 1001, 1001)
  const async500 = await runAsyncSeckill(500, 50, 1002, 1002)

  console.log('运行 2,000 并发（40 倍竞争）抢 50 件对比实验...')
  const sync2000 = await runSyncBaseline(2000, 50, 2001, 2001)
  const async2000 = await runAsyncSeckill(2000, 50, 2002, 2002)

  // 专项验证：同一用户 30 并发不同 requestId 刷单（一人一单拦截）+ 取消订单自动回补重购
  mysqlExec("INSERT INTO product(id,name,price,stock,status) VALUES (3001,'防刷测试商品',50.00,20,'ON_SALE');" +
    "INSERT INTO activity(id,product_id,name,total_quantity,remaining_quantity,per_user_limit,starts_at,ends_at) VALUES " +
    "(3001,3001,'防刷一人一单活动',10,10,1,DATE_SUB(NOW(),INTERVAL 10 MINUTE),DATE_ADD(NOW(),INTERVAL 1 HOUR));")
  const scalper = buyers[0]
  const scalperRes = await Promise.all(Array.from({ length: 30 }, (_, i) =>
    httpRequest(javaPort, '/api/activities/3001/seckill', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Cookie: scalper.cookie, [scalper.csrfHeader]: scalper.csrfToken },
      body: JSON.stringify({ productId: 3001, quantity: 1, addressId: scalper.addressId, requestId: `scalper-${i}` }),
    })
  ))
  const scalperAccepted = scalperRes.filter(r => r.status === 202).length
  const scalperRejected = scalperRes.filter(r => r.status === 409).length
  assert.equal(scalperAccepted, 1, '同用户 30 并发刷单只能有 1 次 202 通过')
  assert.equal(scalperRejected, 29)
  await waitReady('防刷单落库', async () =>
    Number(mysqlExec("SELECT COUNT(*) FROM activity_purchase_reservation WHERE activity_id=3001 AND status='CREATED'")) === 1)
  const createdOrderId = Number(mysqlExec('SELECT order_id FROM activity_purchase_reservation WHERE activity_id=3001 AND status=\'CREATED\''))
  const cancelRes = await httpRequest(javaPort, `/api/orders/${createdOrderId}/cancel`, {
    method: 'POST',
    headers: { Cookie: scalper.cookie, [scalper.csrfHeader]: scalper.csrfToken },
  })
  assert.equal(cancelRes.status, 200)
  assert.equal(Number(mysqlExec('SELECT remaining_quantity FROM activity WHERE id=3001')), 10)
  const rebuyRes = await httpRequest(javaPort, '/api/activities/3001/seckill', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Cookie: scalper.cookie, [scalper.csrfHeader]: scalper.csrfToken },
    body: JSON.stringify({ productId: 3001, quantity: 1, addressId: scalper.addressId, requestId: 'scalper-rebuy-after-cancel' }),
  })
  assert.equal(rebuyRes.status, 202, '取消订单释放 Redis 名额与限购资格后应允许重新购买')
  await waitReady('重购单落库', async () =>
    Number(mysqlExec("SELECT COUNT(*) FROM activity_purchase_reservation WHERE activity_id=3001 AND request_id='scalper-rebuy-after-cancel' AND status='CREATED'")) === 1)

  const summary = {
    measuredAt: new Date().toISOString(),
    environment: {
      os: release(),
      cpu: cpus()[0].model,
      logicalCpu: availableParallelism(),
      totalRamGiB: Number((totalmem() / 1024 ** 3).toFixed(2)),
      freeRamGiB: Number((freemem() / 1024 ** 3).toFixed(2)),
      node: process.version,
      jarSha256: createHash('sha256').update(await readFile(artifact)).digest('hex'),
    },
    comparisons: [sync500, async500, sync2000, async2000],
    antiScalperAndCancelCompensation: {
      concurrentRequestsFromSingleUser: 30,
      accepted202: scalperAccepted,
      rejected409LimitExceeded: scalperRejected,
      cancelRestoredQuotaTo: 10,
      rebuyAfterCancelSucceeded: true,
    },
  }

  const outDir = resolve(root, 'tools/seckill-benchmark/' + runId)
  await mkdir(outDir, { recursive: true })
  await writeFile(resolve(outDir, 'summary.json'), JSON.stringify(summary, null, 2) + '\n')
  console.log('=== 秒杀对比压测完成，结果已保存至 ' + resolve(outDir, 'summary.json') + ' ===')
  console.log(JSON.stringify(summary.comparisons, null, 2))
} finally {
  httpAgent.destroy()
  await stopProc(javaProc)
  if (mysqlProc && !mysqlProc.finished) {
    spawnSync(mysqlClient, ['--no-defaults', '--protocol=TCP', '--host=' + loopback, '--port=' + mysqlPort, '--user=root', 'SHUTDOWN'],
      { input: 'SHUTDOWN;', env: { ...process.env, MYSQL_PWD: dbPwd }, timeout: 5000 })
    await stopProc(mysqlProc)
  }
  if (mqProc && !mqProc.finished && mqEnv) {
    spawnSync('cmd.exe', ['/c', resolve(root, 'tools/rabbitmq_server-4.3.6/sbin/rabbitmqctl.bat'), 'stop'], { env: mqEnv, timeout: 10_000 })
    await stopProc(mqProc)
  }
  await stopProc(redisProc)
  if (workDir) {
    await rm(workDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 300 }).catch(() => {})
  }
}
