import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { closeSync, openSync } from 'node:fs'
import { access, mkdir, mkdtemp, readFile, readdir, realpath, rm, writeFile } from 'node:fs/promises'
import { createServer } from 'node:net'
import { dirname, resolve } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'
import { verifyTrade } from './trade.mjs'
import { prepareGateway, verifyGateway } from './gateway.mjs'
import { startModel, verifyAgent } from './model.mjs'

const frontend = fileURLToPath(new URL('../', import.meta.url))
const root = resolve(frontend, '..')
const artifact = resolve(root, 'target/limited-trade-platform-0.0.1-SNAPSHOT.jar')
const loopback = '127.0.0.1'
const schema = 'limited_trade_acceptance'
const password = 'Run_' + randomBytes(18).toString('hex')
const runId = randomBytes(6).toString('hex')
const fixture = { buyer: 'buyer_' + runId, admin: 'admin_' + runId, product: '验收商品_' + runId, password: 'Login_' + randomBytes(18).toString('hex') }
const env = process.env
const nginx = env.ACCEPTANCE_NGINX
const benchmarkMode = process.argv.includes('--benchmark')
const paginationMode = process.argv.includes('--mysql-pagination')
const childEnv = Object.fromEntries(Object.entries(env).filter(([key]) =>
  !/^(SPRING_|APP_|SERVER_|LOGGING_|AGENT_|DEEPSEEK_|MYSQL_|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$|_JAVA_OPTIONS$)/i.test(key)))

let directory
let database
let mysqlEnv
let mysqlReady = false
let browser
let model
let edge
let edgeArgs
let interrupted = false
const applications = []

function redact(message) {
  return message.replaceAll(password, '[凭据]').replaceAll(fixture.password, '[凭据]')
    .replace(/^.*(?:cookie|x-csrf-token):.*$/gim, '[会话凭据]')
}

function port(key, fallback) {
  const value = Number(env[key] ?? fallback)
  assert.ok(Number.isInteger(value) && value >= 1024 && value <= 65535, key + ' 必须为 1024–65535 的端口')
  return value
}

async function checkPort(value) {
  const server = createServer()
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(value, loopback, () => server.close((error) => error ? reject(error) : resolve()))
  })
}

function start(name, executable, args, options) {
  const log = openSync(resolve(directory, name + '.log'), 'w')
  try {
    const child = spawn(executable, args, { ...options, stdio: ['ignore', log, log] })
    const running = { name, child, finished: false, error: undefined }
    running.closed = new Promise((resolve) => child.once('close', () => {
      running.finished = true
      resolve()
    }))
    child.once('error', (error) => { running.error = error })
    return running
  } finally {
    closeSync(log)
  }
}

function run(executable, args, options) {
  const result = spawnSync(executable, args, { timeout: 45_000, encoding: 'utf8', ...options })
  if (result.error) throw result.error
  assert.equal(result.status, 0, redact((result.stderr || result.stdout).trim()))
  return result
}

async function ready(running, probe) {
  const deadline = Date.now() + 45_000
  while (Date.now() < deadline && !interrupted) {
    if (running.error) throw running.error
    if (running.finished) throw new Error(running.name + ' 提前退出')
    if (await probe()) return
    await delay(250)
  }
  throw new Error(running.name + (interrupted ? ' 已中断' : ' 启动超时'))
}

async function stop(running) {
  if (running.finished) return
  running.child.kill()
  await Promise.race([running.closed, delay(5000, undefined, { ref: false })])
  if (running.finished) return
  running.child.kill('SIGKILL')
  await Promise.race([running.closed, delay(5000, undefined, { ref: false })])
  assert.ok(running.finished, '无法停止 ' + running.name)
}

async function probe(origin) {
  try {
    const response = await fetch(origin + '/api/products', { signal: AbortSignal.timeout(1500) })
    return response.ok && (await response.json()).some((product) => product.name === fixture.product)
  } catch (error) {
    if (error.name === 'TimeoutError' || error.cause?.code === 'ECONNREFUSED') return false
    throw error
  }
}

const mysqlPort = port('ACCEPTANCE_MYSQL_PORT', 13306)
const javaPort = port('ACCEPTANCE_JAVA_PORT', 18080)
const webPort = port('ACCEPTANCE_WEB_PORT', 14173)
const redisPort = benchmarkMode ? port('BENCHMARK_REDIS_PORT', 16379) : undefined
const mqPort = benchmarkMode ? port('BENCHMARK_MQ_PORT', 5679) : undefined
const backend = 'http://' + loopback + ':' + javaPort
const base = 'http://' + loopback + ':' + webPort
const mysqlClient = env.ACCEPTANCE_MYSQL ?? 'mysql'
const mysqlServer = env.ACCEPTANCE_MYSQLD ?? 'mysqld'
const mysqlArgs = [
  '--no-defaults', '--protocol=TCP', '--host=' + loopback, '--port=' + mysqlPort, '--user=root',
  '--default-character-set=utf8mb4', '--batch', '--skip-column-names', '--connect-timeout=2',
]

function mysql(sql, databaseName = schema) {
  const args = databaseName ? [...mysqlArgs, databaseName] : mysqlArgs
  return run(mysqlClient, args, { input: sql, env: mysqlEnv }).stdout.trim()
}

const onSignal = () => {
  interrupted = true
  if (browser?.isConnected()) void browser.close()
}
process.once('SIGINT', onSignal)
process.once('SIGTERM', onSignal)

try {
  const ports = benchmarkMode || paginationMode ? [mysqlPort, javaPort] : [mysqlPort, javaPort, webPort]
  assert.equal(new Set(ports).size, ports.length, '本次启动的服务端口必须互不相同')
  await Promise.all([access(artifact),
    ...(benchmarkMode || paginationMode ? [] : [access(resolve(frontend, 'dist/index.html'))]),
    ...ports.map(checkPort)])
  const parent = await realpath(resolve(root, 'target'))
  directory = await mkdtemp(resolve(parent, 'browser-acceptance-'))
  console.log('隔离目录 ' + directory)
  const data = resolve(directory, 'mysql')
  const config = resolve(directory, 'config')
  const launchSql = resolve(directory, 'mysql-init.sql')
  mysqlEnv = { ...childEnv, MYSQL_PWD: password, MYSQL_TEST_LOGIN_FILE: resolve(directory, 'unused.mylogin.cnf') }
  await mkdir(config)
  await writeFile(resolve(config, 'db.properties'), '')
  await writeFile(resolve(config, 'rabbitmq.properties'), '')
  await writeFile(launchSql, "ALTER USER 'root'@'localhost' IDENTIFIED BY '" + password + "';\n" +
    'CREATE DATABASE ' + schema + ' CHARACTER SET utf8mb4;\n', { mode: 0o600 })
  run(mysqlServer, ['--no-defaults', '--initialize-insecure', '--datadir=' + data], { cwd: directory, env: childEnv })
  database = start('mysql-console', mysqlServer, [
    '--no-defaults', '--datadir=' + data, '--bind-address=' + loopback, '--port=' + mysqlPort,
    '--mysqlx=OFF', '--general-log=OFF', '--skip-log-bin', '--socket=' + resolve(directory, 'mysql.sock'),
    '--pid-file=' + resolve(directory, 'mysql.pid'), '--log-error=' + resolve(directory, 'mysql.log'),
    '--init-file=' + launchSql,
  ], { cwd: directory, env: childEnv })
  await ready(database, () => {
    const result = spawnSync(mysqlClient, [...mysqlArgs, schema], { input: 'SELECT 1', timeout: 3000, env: mysqlEnv })
    if (result.error && result.error.code !== 'ETIMEDOUT') throw result.error
    return result.status === 0
  })
  mysqlReady = true
  await rm(launchSql)

  const sqlDir = resolve(root, 'sql')
  const migrations = (await readdir(sqlDir)).filter((name) => /^\d{3}_.+\.sql$/.test(name)).sort()
  const setup = await Promise.all(['schema.sql', ...migrations].map((name) => readFile(resolve(sqlDir, name), 'utf8')))
  mysql(setup.join('\n') + "\nINSERT INTO product(name,price,stock,status) VALUES ('" + fixture.product + "',19.90,10,'ON_SALE');")

  // Windows 的 PATH 可能是 Oracle 转发程序。启动实际 JVM 才能等待它退出并释放文件。
  const javaName = process.platform === 'win32' ? 'java.exe' : 'java'
  const javaCommand = env.ACCEPTANCE_JAVA ?? (env.JAVA_HOME ? resolve(env.JAVA_HOME, 'bin', javaName) : javaName)
  const settings = run(javaCommand, ['-XshowSettings:properties', '-version'], { env: childEnv, timeout: 10_000 })
  const javaHome = /^\s*java\.home\s*=\s*(.+)$/m.exec(settings.stderr)?.[1].trim()
  assert.ok(javaHome, 'JVM 未报告 java.home')
  if (nginx && !benchmarkMode && !paginationMode) model = await startModel()
  const java = start('java', resolve(javaHome, 'bin', javaName), [
    ...(benchmarkMode ? ['-Xms256m', '-Xmx768m', '-Duser.timezone=Asia/Shanghai'] : []),
    '-jar', artifact, '--server.address=' + loopback, '--server.port=' + javaPort,
    ...(model
      ? ['--app.agent.enabled=true', '--app.agent.base-url=' + model.url, '--app.agent.model-timeout=20s',
        '--app.agent.budget.monthly-yuan=0.08']
      : ['--app.agent.enabled=false']),
    '--app.agent.rag.enabled=false',
    ...(benchmarkMode ? [
      '--spring.datasource.hikari.register-mbeans=true', '--spring.datasource.hikari.maximum-pool-size=10',
      '--spring.data.redis.port=' + redisPort, '--app.order-timeout.scan-delay-ms=1000',
      '--app.product-cache.key=bench:' + runId + ':catalog',
      '--app.order-rate-limit.key-prefix=bench:' + runId + ':limit',
      '--app.order-message.name-prefix=bench-' + runId,
    ] : [
      '--app.product-cache.enabled=false', '--app.order-rate-limit.enabled=false',
      '--app.order-message.enabled=false', '--app.order-message.monitor.enabled=false', '--app.scheduling.enabled=false',
    ]),
  ], { cwd: directory, env: {
    ...childEnv, DB_URL: 'jdbc:mysql://' + loopback + ':' + mysqlPort + '/' + schema + '?allowPublicKeyRetrieval=true&sslMode=DISABLED',
    ...(model ? { DEEPSEEK_API_KEY: 'acceptance-local-only' } : {}),
    DB_USERNAME: 'root', DB_PASSWORD: password, MQ_HOST: loopback,
    MQ_PORT: benchmarkMode ? String(mqPort) : '5672',
    MQ_USERNAME: benchmarkMode ? 'guest' : 'unused',
    MQ_PASSWORD: benchmarkMode ? 'guest' : 'unused',
    MQ_VIRTUAL_HOST: benchmarkMode ? '/' : 'unused',
  } })
  applications.push(java)
  await ready(java, () => probe(backend))

  if (benchmarkMode) {
    const { benchmark } = await import('./benchmark.mjs')
    const sampler = start('jvm', resolve(javaHome, 'bin', javaName),
      [resolve(frontend, 'acceptance/JvmSample.java'), String(java.child.pid)], { cwd: directory, env: childEnv })
    applications.push(sampler)
    const jvmLog = resolve(directory, 'jvm.log')
    await ready(sampler, async () => (await readFile(jvmLog, 'utf8')).startsWith('{'))
    await benchmark({ root, backend, mysql, redisPort, mqPort, runId, artifact, jvmLog })
  } else if (paginationMode) {
    const { verifyPagination } = await import('./mysql-pagination.mjs')
    await verifyPagination({ root, backend, mysql, runId, artifact })
  } else {
    if (nginx) {
      edgeArgs = await prepareGateway(directory, root, webPort, javaPort)
      run(nginx, [...edgeArgs, '-t'], { cwd: directory, env: childEnv })
      edge = start('nginx', nginx, [...edgeArgs, '-g', 'daemon off;'], { cwd: directory, env: childEnv })
      await ready(edge, () => probe(base))
      await verifyGateway(base, backend)
    } else {
      const vite = start('vite', process.execPath, [
        resolve(frontend, 'node_modules/vite/bin/vite.js'), 'preview', '--host', loopback, '--port', String(webPort), '--strictPort',
      ], { cwd: frontend, env: { ...childEnv, BACKEND_URL: backend } })
      applications.push(vite)
      await ready(vite, () => probe(base))
    }
    browser = await chromium.launch({
      channel: env.ACCEPTANCE_BROWSER_CHANNEL,
      executablePath: env.ACCEPTANCE_BROWSER_EXECUTABLE,
    })
    if (interrupted) throw new Error('验收已中断')
    await verifyTrade(browser, base, backend, fixture, mysql)
    if (model) await verifyAgent(browser, base, fixture, model, mysql)
  }
} catch (error) {
  process.exitCode = 1
  console.error(redact(String(error.stack ?? error)))
  if (directory) {
    const files = await readdir(directory)
    for (const name of ['mysql.log', 'mysql-console.log', 'java.log', 'vite.log', 'nginx.log', 'jvm.log']) {
      if (!files.includes(name)) continue
      const lines = (await readFile(resolve(directory, name), 'utf8')).trim().split('\n')
      if (lines[0]) console.error(name + ' 末尾\n' + redact(lines.slice(-6).join('\n')))
    }
  }
} finally {
  const cleanup = await Promise.allSettled([browser?.close(), model?.close()])
  if (edge && !edge.finished) {
    try {
      run(nginx, [...edgeArgs, '-s', 'quit'], { cwd: directory, env: childEnv, timeout: 10_000 })
      await Promise.race([edge.closed, delay(8000, undefined, { ref: false })])
      assert.ok(edge.finished, 'Nginx 尚未退出，保留本轮运行目录')
    } catch (reason) {
      cleanup.push({ status: 'rejected', reason })
    }
  }
  cleanup.push(...await Promise.allSettled(applications.map(stop)))
  if (database && !database.finished) {
    if (mysqlReady) {
      try {
        mysql('SHUTDOWN;', '')
        await Promise.race([database.closed, delay(5000, undefined, { ref: false })])
      } catch (reason) {
        cleanup.push({ status: 'rejected', reason })
      }
    }
    cleanup.push(...await Promise.allSettled([stop(database)]))
  }
  if (directory && (!edge || edge.finished) && [...applications, ...(database ? [database] : [])].every((running) => running.finished)) {
    try {
      const resolved = await realpath(directory)
      assert.equal(dirname(resolved), await realpath(resolve(root, 'target')))
      assert.equal(resolved, directory)
      await rm(resolved, { recursive: true, maxRetries: 5, retryDelay: 200 })
      console.log('已清理 ' + directory)
    } catch (reason) {
      cleanup.push({ status: 'rejected', reason })
    }
  }
  for (const result of cleanup) {
    if (result.status === 'rejected') {
      process.exitCode = 1
      console.error('清理失败：' + redact(String(result.reason)))
    }
  }
  if (interrupted && cleanup.every((result) => result.status === 'fulfilled')) process.exitCode = 130
}
