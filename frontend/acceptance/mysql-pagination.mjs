import assert from 'node:assert/strict'
import { createHash, randomUUID } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const columns = 'id, user_id, order_type, total_amount, original_amount, discount_amount, status, request_id, ' +
  'created_at, expires_at, paid_at, ended_at'
const original = (user, offset, size = 20, status) => 'SELECT ' + columns + ' FROM trade_order ' +
  'WHERE user_id = ' + user + (status ? " AND status = '" + status + "'" : '') +
  ' ORDER BY id DESC LIMIT ' + size + ' OFFSET ' + offset
const delayed = (user, offset) => 'SELECT ' + columns + ' FROM trade_order JOIN (' +
  'SELECT id AS page_order_id FROM trade_order WHERE user_id = ' + user +
  ' ORDER BY id DESC LIMIT 20 OFFSET ' + offset + ') chosen ON chosen.page_order_id = trade_order.id ORDER BY id DESC'

export async function verifyPagination({ root, backend, mysql, runId, artifact }) {
  const output = resolve(root, 'tools/mysql-pagination', runId)
  await mkdir(output, { recursive: true })
  console.log('分页证据 ' + output)
  const digest = createHash('sha256').update(await readFile(artifact)).digest('hex')
  const password = 'Page_' + randomUUID()
  const buyers = []
  for (const label of ['owner', 'other']) {
    let cookie = ''
    async function request(path, method = 'GET', body, headers = {}) {
      if (cookie) headers.Cookie = cookie
      const response = await fetch(backend + path, { method, body, headers, signal: AbortSignal.timeout(20_000) })
      const session = response.headers.getSetCookie().find((value) => value.startsWith('JSESSIONID='))
      if (session) cookie = session.split(';')[0]
      return response
    }

    const credentials = { username: 'page_' + runId + '_' + label, password }
    let csrf = await (await request('/api/auth/csrf')).json()
    const registration = await request('/api/auth/register', 'POST', JSON.stringify(credentials), {
      'Content-Type': 'application/json', [csrf.headerName]: csrf.token,
    })
    assert.equal(registration.status, 201)
    const { id } = await registration.json()
    csrf = await (await request('/api/auth/csrf')).json()
    const login = await request('/api/auth/login', 'POST', new URLSearchParams(credentials), { [csrf.headerName]: csrf.token })
    assert.equal(login.status, 204)
    buyers.push({ id, request })
  }
  const owner = buyers[0].id
  const other = buyers[1].id
  const product = Number(mysql('SELECT id FROM product'))
  const digits = Array.from({ length: 10 }, (_, i) => 'SELECT ' + i).join(' UNION ALL ')
  mysql('INSERT INTO trade_order(id,user_id,total_amount,status,request_id,created_at,expires_at,paid_at,ended_at) ' +
    'WITH d(n) AS (' + digits + '), numbers AS (' +
    'SELECT a.n+10*b.n+100*c.n+1000*e.n+10000*f.n+100000*g.n AS n ' +
    'FROM d a CROSS JOIN d b CROSS JOIN d c CROSS JOIN d e CROSS JOIN d f CROSS JOIN d g WHERE g.n < 2) ' +
    'SELECT 1000+n,IF(MOD(n,5)=0,' + other + ',' + owner + "),20,ELT(1+MOD(n,3),'PAID','CANCELED','CLOSED')," +
    "CONCAT('page-',n),'2026-01-01 00:00:00','2026-01-01 00:15:00'," +
    "IF(MOD(n,3)=0,'2026-01-01 00:05:00',NULL),IF(MOD(n,3)<>0,'2026-01-01 00:15:00',NULL) " +
    'FROM numbers WHERE n<150000 ORDER BY n;')
  mysql('INSERT INTO order_item(order_id,product_id,product_name,unit_price,quantity,line_amount) ' +
    "SELECT id," + product + ",'分页样本商品',20,1,20 FROM trade_order ORDER BY id;")
  mysql('ANALYZE TABLE trade_order, order_item')
  assert.equal(mysql('SELECT user_id,COUNT(*) FROM trade_order GROUP BY user_id ORDER BY user_id').replaceAll('\r\n', '\n'), [owner + '\t120000', other + '\t30000'].join('\n'))

  const apiCases = []
  const cases = [
    [0, 1, 20], [0, 2, 20], [0, 50, 20], [0, 51, 20], [0, 1000, 20], [0, 1001, 20], [0, 5001, 20],
    [0, 6000, 20], [0, 6001, 20], [0, 2147483647, 100],
    [0, 1001, 20, 'PAID'], [0, 51, 20, 'SHIPPED'], [1, 1001, 20],
  ]
  for (const [index, page, size, status] of cases) {
    const buyer = buyers[index]
    const query = new URLSearchParams({ page: String(page), size: String(size), userId: String(buyers[1 - index].id) })
    if (status) query.set('status', status)
    const response = await buyer.request('/api/orders?' + query)
    assert.equal(response.status, 200)
    const value = await response.json()
    assert.deepEqual([value.page, value.size], [page, size])
    assert.equal(value.total, status === 'SHIPPED' ? 0 : (index === 0 ? 120000 : 30000) / (status ? 3 : 1))
    assert.ok(value.items.every((item) => item.items.length === 1 && item.items[0].productName === '分页样本商品' &&
      item.totalAmount === 20 && (!status || item.status === status)))
    apiCases.push({ owner: index, page, size, status, total: value.total, ids: value.items.map((item) => item.id) })
  }
  // 先保存 API 实际执行的 SQL 摘要，避免后面的对比查询混入。
  const mapperDigests = mysql("SELECT COUNT_STAR,DIGEST_TEXT FROM performance_schema.events_statements_summary_by_digest " +
    "WHERE SCHEMA_NAME=DATABASE() AND DIGEST_TEXT LIKE 'SELECT %' AND DIGEST_TEXT LIKE '%trade_order%' " +
    "AND DIGEST_TEXT LIKE '%ORDER BY%LIMIT%' ORDER BY DIGEST_TEXT")
  for (const item of apiCases) {
    const intended = mysql(original(buyers[item.owner].id, (item.page - 1) * item.size, item.size, item.status))
    const ids = intended ? intended.split(/\r?\n/).map((row) => Number(row.split('\t')[0])) : []
    assert.deepEqual(item.ids, ids)
  }
  console.log('通过：真实分页 API、归属、总数、状态、明细及越界检查')

  const configuration = mysql('SELECT @@version,@@transaction_isolation,@@innodb_buffer_pool_size,@@performance_schema')
  const indexes = mysql('SHOW INDEX FROM trade_order')
  const tableSize = mysql('SET SESSION information_schema_stats_expiry=0; ' +
    "SELECT DATA_LENGTH,INDEX_LENGTH FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='trade_order'")
  const plans = []
  const measurements = []
  for (const offset of [0, 20, 980, 1000, 20000, 100000, 119980, 120000]) {
    const queries = [original(owner, offset), delayed(owner, offset)]
    assert.equal(mysql(queries[0]), mysql(queries[1]), '原查询和候选查询完整行不一致，偏移 ' + offset)
    if ([0, 1000, 100000, 120000].includes(offset)) {
      for (let variant = 0; variant < queries.length; variant++) {
        plans.push({ offset, variant, sql: queries[variant], plan: mysql('EXPLAIN ANALYZE ' + queries[variant]).replaceAll('\\n', '\n') })
      }
    }
    const trace = "SELECT 'timing',TIMER_WAIT/1000000000,ROWS_EXAMINED,ROWS_SENT " +
      "FROM performance_schema.events_statements_history WHERE THREAD_ID=@bench_thread AND SQL_TEXT LIKE 'SELECT id, user_id, order_type,%' " +
      'ORDER BY EVENT_ID DESC LIMIT 1;'
    const batch = ['SELECT THREAD_ID INTO @bench_thread FROM performance_schema.threads WHERE PROCESSLIST_ID=CONNECTION_ID();']
    const variants = []
    for (let repeat = 0; repeat < 15; repeat++) {
      for (const variant of repeat % 2 === 0 ? [0, 1] : [1, 0]) {
        batch.push(queries[variant] + ';')
        if (repeat >= 3) {
          batch.push(trace)
          variants.push(variant)
        }
      }
    }
    const result = mysql(batch.join('\n')).split(/\r?\n/).filter((line) => line.startsWith('timing\t'))
    assert.equal(result.length, 24, 'Performance Schema 未采到完整 SQL 样本')
    for (let i = 0; i < result.length; i++) {
      const [ms, examined, sent] = result[i].split('\t').slice(1).map(Number)
      assert.ok(ms > 0 && Number.isInteger(examined) && Number.isInteger(sent))
      measurements.push({ offset, variant: variants[i], ms, examined, sent })
    }
  }
  await writeFile(resolve(output, 'results.json'), JSON.stringify({
    jarSha256: digest, configuration, indexes, tableSize, apiCases, mapperDigests, measurements, plans,
  }, null, 2) + '\n')
  console.log('通过：8 组偏移完整行相同，保存 192 条 MySQL 语句采样与执行计划')
}
