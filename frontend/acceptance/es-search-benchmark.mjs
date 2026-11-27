import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { createHash, randomBytes } from 'node:crypto'
import { closeSync, openSync } from 'node:fs'
import { access, cp, mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises'
import http from 'node:http'
import { availableParallelism, cpus, freemem, release, totalmem } from 'node:os'
import { performance } from 'node:perf_hooks'
import { resolve } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'

const frontendDir = fileURLToPath(new URL('../', import.meta.url))
const root = resolve(frontendDir, '..')
const artifact = resolve(root, 'target/limited-trade-platform-0.0.1-SNAPSHOT.jar')
const esHome = resolve(root, 'tools/elasticsearch-8.17.2')
const esBat = resolve(esHome, 'bin/elasticsearch.bat')
const loopback = '127.0.0.1'
const schema = 'limited_es_bench'
const runId = randomBytes(5).toString('hex')
const dbPwd = 'EsBenchDb_' + randomBytes(12).toString('hex')
const adminPwd = 'AdminPass_2026!'

const mysqlPort = Number(process.env.ES_BENCH_MYSQL_PORT ?? 23319)
const esPort = Number(process.env.ES_BENCH_HTTP_PORT ?? 19219)
const esTransportPort = Number(process.env.ES_BENCH_TCP_PORT ?? 19319)
const javaMysqlPort = Number(process.env.ES_BENCH_JAVA_MYSQL_PORT ?? 28089)
const javaEsPort = Number(process.env.ES_BENCH_JAVA_ES_PORT ?? 28090)

const mysqlServer = process.env.ACCEPTANCE_MYSQLD ?? 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysqld.exe'
const mysqlClient = process.env.ACCEPTANCE_MYSQL ?? 'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe'
const javaBin = process.env.ACCEPTANCE_JAVA ?? 'C:/Program Files/Java/jdk-21.0.10/bin/java.exe'

const httpAgent = new http.Agent({ keepAlive: true, maxSockets: 256, maxFreeSockets: 64 })

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
        resolvePromise({
          status: res.statusCode ?? 0,
          ms: performance.now() - started,
          text: Buffer.concat(chunks).toString('utf8'),
          setCookies: res.headers['set-cookie'] ?? [],
        })
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

function startProc(dir, name, exe, args, options = {}) {
  const logFd = openSync(resolve(dir, name + '.log'), 'w')
  try {
    const child = spawn(exe, args, { ...options, stdio: ['ignore', logFd, logFd] })
    const proc = { name, child, finished: false }
    proc.closed = new Promise(r => child.once('close', () => { proc.finished = true; r() }))
    return proc
  } finally {
    closeSync(logFd)
  }
}

async function waitReady(label, check, timeoutMs = 90_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      if (await check()) return
    } catch {}
    await delay(300)
  }
  throw new Error(label + ' 启动超时')
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

async function closedLoopBench(port, path, concurrency, warmMs = 800, measureMs = 2500) {
  const runWindow = async (durationMs) => {
    const samples = []
    const stopAt = performance.now() + durationMs
    const workers = Array.from({ length: concurrency }, async () => {
      while (performance.now() < stopAt) {
        const res = await httpRequest(port, path)
        assert.equal(res.status, 200)
        samples.push(res.ms)
      }
    })
    const t0 = performance.now()
    await Promise.all(workers)
    const elapsedSec = (performance.now() - t0) / 1000
    samples.sort((a, b) => a - b)
    return {
      requests: samples.length,
      qps: Number((samples.length / elapsedSec).toFixed(1)),
      p50Ms: percentile(samples, 50),
      p95Ms: percentile(samples, 95),
      p99Ms: percentile(samples, 99),
    }
  }
  await runWindow(warmMs)
  return await runWindow(measureMs)
}

let workDir
let mysqlProc
let esProc
let javaMysqlProc
let javaEsProc

try {
  await access(artifact)
  await access(esBat)
  workDir = await mkdtemp(resolve(root, 'target/es-bench-'))
  console.log('ES 检索对比实验目录: ' + workDir)

  // 1. 启动隔离便携版 Elasticsearch 8.17.2
  const esConf = resolve(workDir, 'es-config')
  const esData = resolve(workDir, 'es-data')
  const esLogs = resolve(workDir, 'es-logs')
  await cp(resolve(esHome, 'config'), esConf, { recursive: true })
  await mkdir(esData, { recursive: true })
  await mkdir(esLogs, { recursive: true })
  await writeFile(resolve(esConf, 'elasticsearch.yml'), [
    `cluster.name: limited-es-${runId}`,
    'node.name: node-1',
    `path.data: "${esData.replaceAll('\\', '/')}"`,
    `path.logs: "${esLogs.replaceAll('\\', '/')}"`,
    `network.host: ${loopback}`,
    `http.port: ${esPort}`,
    `transport.port: ${esTransportPort}`,
    'discovery.type: single-node',
    'xpack.security.enabled: false',
    'xpack.ml.enabled: false',
  ].join('\n') + '\n')

  esProc = startProc(workDir, 'elasticsearch', 'cmd.exe', ['/c', esBat], {
    cwd: esHome,
    env: {
      ...process.env,
      ES_PATH_CONF: esConf,
      ES_JAVA_HOME: 'C:/Program Files/Java/jdk-21.0.10',
      ES_JAVA_OPTS: '-Xms512m -Xmx512m',
    },
  })
  await waitReady('Elasticsearch', async () => {
    const r = await httpRequest(esPort, '/_cluster/health')
    if (r.status !== 200) return false
    const health = JSON.parse(r.text)
    return health.status === 'green' || health.status === 'yellow'
  }, 90_000)
  const esVersion = JSON.parse((await httpRequest(esPort, '/')).text).version.number
  console.log(`Elasticsearch ${esVersion} 已就绪于端口 ${esPort}`)

  // 2. 启动隔离 MySQL 并灌入 12,000 件真实感电商商品数据
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
    '--mysqlx=OFF', '--skip-log-bin', '--max-connections=200', '--innodb-buffer-pool-size=256M', '--init-file=' + initSql,
  ], { cwd: workDir })

  const mysqlArgs = ['--no-defaults', '--protocol=TCP', '--host=' + loopback, '--port=' + mysqlPort, '--user=root', '--default-character-set=utf8mb4', '--batch', '--skip-column-names']
  const mysqlExec = (sql, db = schema) => {
    const r = spawnSync(mysqlClient, db ? [...mysqlArgs, db] : mysqlArgs, {
      input: sql, encoding: 'utf8', env: { ...process.env, MYSQL_PWD: dbPwd }, timeout: 45_000,
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

  const catNames = ['数码外设', '智能穿戴', '影音娱乐', '电脑整机', '家居生活', '个护健康', '户外运动', '潮玩手办', '摄影器材', '厨房电器', '图书文创', '箱包服饰']
  mysqlExec('INSERT INTO product_category(id,name) VALUES ' + catNames.map((n, i) => `(${i + 1},'${n}')`).join(','))

  const brands = ['极客造物', '星云声学', '云端智造', '极光实验室', '青禾生活', '钛度工坊', '森海行者', '光影纪元']
  const adjectives = ['客制化三模', '超轻碳纤维', '静谧主动降噪', '限量联名款', '旗舰高刷', '便携快充', '人体工学', '全金属CNC']
  const nouns = ['无线机械键盘', '真无线蓝牙耳机', '电竞游戏鼠标', '智能运动手表', '便携咖啡机', '机械轴体套装', '桌面显示器挂灯', '高速固态移动硬盘']
  const totalProducts = 12_000
  for (let batch = 0; batch < totalProducts; batch += 1000) {
    const rows = []
    for (let i = batch + 1; i <= batch + 1000; i++) {
      const catId = (i % 12) + 1
      const brand = brands[i % brands.length]
      const adj = adjectives[(i * 3) % adjectives.length]
      const noun = nouns[(i * 7) % nouns.length]
      const price = ((i % 45) * 25 + 39.9).toFixed(2)
      const stock = i % 10 === 0 ? 0 : (i % 80) + 1
      const status = i % 25 === 0 ? 'OFF_SALE' : 'ON_SALE'
      rows.push(`(${i},'${brand} ${adj}${noun} #${String(i).padStart(5, '0')}',${price},${stock},'${status}',${catId})`)
    }
    mysqlExec('INSERT INTO product(id,name,price,stock,status,category_id) VALUES ' + rows.join(','))
  }
  const onSaleCount = Number(mysqlExec("SELECT COUNT(*) FROM product WHERE status='ON_SALE'"))
  console.log(`已写入 ${totalProducts} 件商品（在售 ${onSaleCount} 件）`)

  function launchJava(name, port, searchMode) {
    return startProc(workDir, name, javaBin, [
      '-Xms384m', '-Xmx768m', '-Duser.timezone=Asia/Shanghai',
      '-jar', artifact,
      '--server.address=' + loopback,
      '--server.port=' + port,
      '--spring.datasource.hikari.maximum-pool-size=16',
      '--app.agent.enabled=false',
      '--app.agent.rag.enabled=false',
      '--app.product-cache.enabled=false',
      '--app.order-rate-limit.enabled=false',
      '--app.order-message.enabled=false',
      '--app.order-message.monitor.enabled=false',
      '--app.scheduling.enabled=false',
      '--app.seckill.enabled=false',
      '--app.search.mode=' + searchMode,
      '--app.search.elasticsearch.base-url=http://' + loopback + ':' + esPort,
      '--app.search.elasticsearch.index-name=bench_products_' + runId,
    ], {
      cwd: workDir,
      env: {
        ...process.env,
        DB_URL: `jdbc:mysql://${loopback}:${mysqlPort}/${schema}?allowPublicKeyRetrieval=true&sslMode=DISABLED`,
        DB_USERNAME: 'root',
        DB_PASSWORD: dbPwd,
        MQ_HOST: loopback,
        MQ_PORT: '5672',
        MQ_USERNAME: 'unused',
        MQ_PASSWORD: 'unused',
        MQ_VIRTUAL_HOST: 'unused',
      },
    })
  }

  // 3. 启动 MySQL 检索实例与 ES 检索实例（ES 实例启动时自动全量建索引）
  javaMysqlProc = launchJava('java-mysql', javaMysqlPort, 'mysql')
  javaEsProc = launchJava('java-es', javaEsPort, 'es')
  await Promise.all([
    waitReady('Java-MySQL', async () => (await httpRequest(javaMysqlPort, '/api/ping')).status === 200),
    waitReady('Java-ES-Ping', async () => (await httpRequest(javaEsPort, '/api/ping')).status === 200),
  ])
  await waitReady('Java-ES-Indexed', async () => {
    const res = await httpRequest(javaEsPort, '/api/products/search?page=1&size=1')
    if (res.status !== 200) return false
    const page = JSON.parse(res.text)
    return page.engine === 'elasticsearch' && page.total === onSaleCount
  }, 45_000)
  console.log('Java(MySQL 模式) 与 Java(ES 模式 + 11,520 在售文档索引) 均已就绪')

  const qKeyword = '/api/products/search?page=1&size=20&keyword=' + encodeURIComponent('无线机械键盘')
  const qFiltered = '/api/products/search?page=1&size=20&keyword=' + encodeURIComponent('蓝牙耳机') + '&minPrice=100&maxPrice=800&inStockOnly=true&sort=price_asc'
  const qSuggest = '/api/products/suggest?prefix=' + encodeURIComponent('客制化') + '&limit=8'

  // 校验高亮、分类聚合与结果语义
  const esSample = JSON.parse((await httpRequest(javaEsPort, qKeyword)).text)
  assert.equal(esSample.engine, 'elasticsearch')
  assert.equal(esSample.degraded, false)
  assert.ok(esSample.total > 0)
  assert.ok(esSample.items[0].highlightedName?.includes('<mark>'))
  assert.ok(esSample.categoryFacets.length > 0)

  // 4. 运行并发 1 / 8 / 24 性能对照
  const benchmarks = []
  for (const [scenario, path] of [['keyword_and_facets', qKeyword], ['filtered_price_sort', qFiltered], ['prefix_suggest', qSuggest]]) {
    for (const c of [1, 8, 24]) {
      const mysqlRes = await closedLoopBench(javaMysqlPort, path, c)
      const esRes = await closedLoopBench(javaEsPort, path, c)
      benchmarks.push({ scenario, concurrency: c, mysql: mysqlRes, elasticsearch: esRes })
      console.log(`${scenario} [并发=${c}] MySQL: ${mysqlRes.qps} req/s (P95 ${mysqlRes.p95Ms}ms) | ES: ${esRes.qps} req/s (P95 ${esRes.p95Ms}ms)`)
    }
  }

  // 5. 验证事务提交后（AFTER_COMMIT）增量同步：新建(OFF_SALE) -> 上架(ON_SALE) -> 改价 -> 下架(OFF_SALE)
  const c0 = await httpRequest(javaEsPort, '/api/auth/csrf')
  const cookie0 = (c0.setCookies.find(c => c.startsWith('JSESSIONID=')) ?? '').split(';')[0]
  const csrf0 = JSON.parse(c0.text)
  const regAdmin = await httpRequest(javaEsPort, '/api/auth/register', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      [csrf0.headerName]: csrf0.token,
      ...(cookie0 ? { Cookie: cookie0 } : {}),
    },
    body: JSON.stringify({ username: 'es_admin', password: adminPwd }),
  })
  assert.equal(regAdmin.status, 201)
  const adminId = JSON.parse(regAdmin.text).id
  mysqlExec(`UPDATE app_user SET role='ADMIN' WHERE id=${adminId}`)
  const c1 = await httpRequest(javaEsPort, '/api/auth/csrf')
  const cookie1 = (c1.setCookies.find(c => c.startsWith('JSESSIONID=')) ?? '').split(';')[0]
  const token1 = JSON.parse(c1.text)
  const loginRes = await httpRequest(javaEsPort, '/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', [token1.headerName]: token1.token, Cookie: cookie1 },
    body: new URLSearchParams({ username: 'es_admin', password: adminPwd }).toString(),
  })
  assert.equal(loginRes.status, 204)
  const adminCookie = (loginRes.setCookies.find(c => c.startsWith('JSESSIONID=')) ?? cookie1).split(';')[0]
  const c2 = await httpRequest(javaEsPort, '/api/auth/csrf', { headers: { Cookie: adminCookie } })
  const adminCsrf = JSON.parse(c2.text)
  const adminHeaders = { 'Content-Type': 'application/json', Cookie: adminCookie, [adminCsrf.headerName]: adminCsrf.token }

  const uniqueToken = '增量同步专属星际键帽_' + runId
  const createdResp = await httpRequest(javaEsPort, '/api/admin/products', {
    method: 'POST',
    headers: adminHeaders,
    body: JSON.stringify({ name: uniqueToken, price: 299.00, stock: 15, categoryId: 1 }),
  })
  assert.equal(createdResp.status, 201)
  const newProdId = JSON.parse(createdResp.text).id
  // 初始 OFF_SALE 不应出现在公开 ES 搜索
  const beforeOnSale = JSON.parse((await httpRequest(javaEsPort, '/api/products/search?keyword=' + encodeURIComponent(uniqueToken))).text)
  assert.equal(beforeOnSale.total, 0)

  // 上架 -> ES 增量同步立即可搜
  const onSaleResp = await httpRequest(javaEsPort, `/api/admin/products/${newProdId}/status`, {
    method: 'PATCH',
    headers: adminHeaders,
    body: JSON.stringify({ status: 'ON_SALE' }),
  })
  assert.equal(onSaleResp.status, 200)
  const afterOnSale = JSON.parse((await httpRequest(javaEsPort, '/api/products/search?keyword=' + encodeURIComponent(uniqueToken))).text)
  assert.equal(afterOnSale.engine, 'elasticsearch')
  assert.equal(afterOnSale.total, 1)
  assert.equal(Number(afterOnSale.items[0].price), 299)

  // 改价 -> ES 增量更新立即可见
  const updateResp = await httpRequest(javaEsPort, `/api/admin/products/${newProdId}`, {
    method: 'PUT',
    headers: adminHeaders,
    body: JSON.stringify({ name: uniqueToken, price: 259.00, categoryId: 1 }),
  })
  assert.equal(updateResp.status, 200)
  const afterUpdate = JSON.parse((await httpRequest(javaEsPort, '/api/products/search?keyword=' + encodeURIComponent(uniqueToken))).text)
  assert.equal(Number(afterUpdate.items[0].price), 259)

  // 下架 -> ES 增量删除立即不可见
  await httpRequest(javaEsPort, `/api/admin/products/${newProdId}/status`, {
    method: 'PATCH',
    headers: adminHeaders,
    body: JSON.stringify({ status: 'OFF_SALE' }),
  })
  const afterOffSale = JSON.parse((await httpRequest(javaEsPort, '/api/products/search?keyword=' + encodeURIComponent(uniqueToken))).text)
  assert.equal(afterOffSale.total, 0)

  // 6. 验证故障自动熔断降级：强制停止 Elasticsearch 后请求搜索应自动回退 MySQL 且返回 degraded=true
  await stopProc(esProc)
  const fallbackStart = performance.now()
  const fallbackHttp = await httpRequest(javaEsPort, qKeyword)
  const fallbackMs = Number((performance.now() - fallbackStart).toFixed(2))
  assert.equal(fallbackHttp.status, 200)
  const fallbackPage = JSON.parse(fallbackHttp.text)
  assert.equal(fallbackPage.degraded, true)
  assert.equal(fallbackPage.engine, 'mysql')
  assert.ok(fallbackPage.total > 0)
  // 冷却期内第二次请求不再等待连接超时，直接毫秒级走 MySQL
  const cooldownHttp = await httpRequest(javaEsPort, qKeyword)
  assert.equal(cooldownHttp.status, 200)
  assert.equal(cooldownHttp.ms < 100, true)

  const summary = {
    measuredAt: new Date().toISOString(),
    elasticsearchVersion: esVersion,
    dataset: { totalProducts, onSaleProducts: onSaleCount, categories: 12 },
    environment: {
      os: release(),
      cpu: cpus()[0].model,
      logicalCpu: availableParallelism(),
      totalRamGiB: Number((totalmem() / 1024 ** 3).toFixed(2)),
      freeRamGiB: Number((freemem() / 1024 ** 3).toFixed(2)),
      node: process.version,
      jarSha256: createHash('sha256').update(await readFile(artifact)).digest('hex'),
    },
    benchmarks,
    incrementalSyncVerified: true,
    circuitBreakerFallback: {
      firstDegradedTripMs: fallbackMs,
      cooldownFastFallbackMs: Number(cooldownHttp.ms.toFixed(2)),
      returnedTotal: fallbackPage.total,
    },
  }

  const outDir = resolve(root, 'tools/es-benchmark/' + runId)
  await mkdir(outDir, { recursive: true })
  await writeFile(resolve(outDir, 'summary.json'), JSON.stringify(summary, null, 2) + '\n')
  console.log('=== ES 万级商品检索与降级实验完成，结果已保存至 ' + resolve(outDir, 'summary.json') + ' ===')
} catch (err) {
  process.exitCode = 1
  console.error(err)
  if (workDir) {
    for (const f of ['java-es.log', 'java-mysql.log', 'elasticsearch.log']) {
      const content = await readFile(resolve(workDir, f), 'utf8').catch(() => '')
      if (!content) continue
      const warnLines = content.split('\n').filter(l => l.includes('"WARN"') || l.includes('"ERROR"') || l.includes('elasticsearch_'))
      if (warnLines.length) console.error(`--- ${f} warnings ---\n` + warnLines.slice(0, 10).join('\n'))
      console.error(`--- ${f} tail ---\n` + content.split('\n').slice(-10).join('\n'))
    }
  }
} finally {
  httpAgent.destroy()
  await Promise.allSettled([stopProc(javaEsProc), stopProc(javaMysqlProc), stopProc(esProc)])
  if (mysqlProc && !mysqlProc.finished) {
    spawnSync(mysqlClient, ['--no-defaults', '--protocol=TCP', '--host=' + loopback, '--port=' + mysqlPort, '--user=root', 'SHUTDOWN'],
      { input: 'SHUTDOWN;', env: { ...process.env, MYSQL_PWD: dbPwd }, timeout: 5000 })
    await stopProc(mysqlProc)
  }
  if (workDir) {
    await rm(workDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 300 }).catch(() => {})
  }
}
