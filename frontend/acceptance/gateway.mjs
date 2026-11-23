import assert from 'node:assert/strict'
import { cp, mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'

export async function prepareGateway(directory, root, webPort, javaPort) {
  const prefix = resolve(directory, 'deploy/nginx-vue')
  const conf = resolve(prefix, 'nginx.conf')
  await Promise.all([
    ...['nginx.conf', 'api-proxy.conf', 'vue-http.conf', 'vue-server.conf'].map((name) =>
      cp(resolve(root, 'deploy/nginx-vue', name), resolve(prefix, name))),
    cp(resolve(root, 'deploy/nginx/http-common.conf'), resolve(directory, 'deploy/nginx/http-common.conf')),
    cp(resolve(root, 'frontend/dist'), resolve(directory, 'frontend/dist'), { recursive: true }),
    mkdir(resolve(prefix, 'logs'), { recursive: true }),
    mkdir(resolve(prefix, 'temp'), { recursive: true }),
  ])
  const settings = await readFile(conf, 'utf8')
  assert.ok(settings.includes('listen 127.0.0.1:18083;') && settings.includes('server 127.0.0.1:8080;'))
  await writeFile(conf, settings
    .replace('listen 127.0.0.1:18083;', 'listen 127.0.0.1:' + webPort + ';')
    .replace('server 127.0.0.1:8080;', 'server 127.0.0.1:' + javaPort + ';'))
  return ['-p', prefix.replaceAll('\\', '/') + '/', '-c', 'nginx.conf']
}

export async function verifyGateway(base, backend) {
  const root = await fetch(base)
  assert.equal(root.status, 200)
  assert.equal(root.headers.get('cache-control'), 'no-cache')
  const html = await root.text()
  assert.match(html, /id="app"/)
  for (const path of ['/products', '/orders/1', '/admin/orders/1', '/agent']) {
    const deepLink = await fetch(base + path)
    assert.equal(deepLink.status, 200)
    assert.equal(deepLink.headers.get('cache-control'), 'no-cache')
    assert.equal(await deepLink.text(), html)
  }
  for (const [extension, mime] of [['js', 'application/javascript'], ['css', 'text/css']]) {
    const match = new RegExp('"/assets/[^"]+\\.' + extension + '"').exec(html)
    assert.ok(match, '入口缺少 ' + extension + ' 资源')
    const asset = await fetch(base + JSON.parse(match[0]))
    assert.equal(asset.status, 200)
    assert.equal(asset.headers.get('content-type'), mime)
    assert.equal(asset.headers.get('cache-control'), 'public, max-age=31536000, immutable')
    const etag = asset.headers.get('etag')
    assert.ok(etag)
    await asset.arrayBuffer()
    const unchanged = await fetch(asset.url, { headers: { 'If-None-Match': etag } })
    assert.equal(unchanged.status, 304)
    assert.equal(unchanged.headers.get('cache-control'), 'public, max-age=31536000, immutable')
  }
  const missing = await fetch(base + '/assets/missing-AbCd1234.js')
  assert.equal(missing.status, 404)
  assert.equal(missing.headers.get('cache-control'), 'no-store')
  assert.notEqual(await missing.text(), html)

  const guests = await fetch(base + '/api/auth/me')
  assert.equal(guests.status, 401)
  assert.equal(guests.headers.get('cache-control'), 'no-store')
  assert.ok(guests.headers.get('x-trace-id'))
  const csrf = await fetch(base + '/api/auth/csrf')
  assert.equal(csrf.status, 200)
  assert.equal(csrf.headers.get('cache-control'), 'no-store')
  assert.ok(csrf.headers.get('set-cookie'))
  const native = await fetch(backend)
  assert.equal(native.status, 200)
  assert.match(await native.text(), /<title>限量集市 · 商品与活动<\/title>/)
  console.log('通过：SPA 深链接、静态缓存与 304、资源 404、个人响应 no-store、原生 Java 入口')
}
