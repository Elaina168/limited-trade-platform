import { test } from 'node:test'
import assert from 'node:assert/strict'
import { advanceSession, HttpError, RequestError, SessionChangedError } from '../src/api/http.ts'
import { canReconcileWrite, reconcileWrite } from '../src/utils/write-reconciliation.ts'

test('有待核对的写入只读取，读取最终状态后结束', async () => {
  let reads = 0
  const result = await reconcileWrite(async () => {
    reads++
    if (reads === 1) throw new RequestError('读取暂时失败')
    return reads === 2 ? '待支付' : '已支付'
  }, status => status === '已支付', () => true)
  assert.deepEqual(result, { snapshot: '已支付', confirmed: true })
  assert.equal(reads, 3)
})

test('有限次读取后保留最后一次可用状态，但不确认写入', async () => {
  let reads = 0
  const result = await reconcileWrite(async () => {
    if (++reads === 1) return '待支付'
    throw new HttpError(503, null)
  }, status => status === '已支付', () => true)
  assert.deepEqual(result, { snapshot: '待支付', confirmed: false })
  assert.equal(reads, 3)
})

test('会话或页面发生变化时丢弃已读取的结果', async () => {
  const switched = await reconcileWrite(async () => {
    advanceSession()
    return '已支付'
  }, () => true, () => true)
  assert.equal(switched, undefined)

  let current = true
  const departed = await reconcileWrite(async () => {
    current = false
    return '已支付'
  }, () => true, () => current)
  assert.equal(departed, undefined)
})

test('可核对网络故障、服务错误和业务冲突，立即保留明确的校验或会话错误', () => {
  assert.equal(canReconcileWrite(new RequestError('响应丢失')), true)
  assert.equal(canReconcileWrite(new HttpError(503, null)), true)
  assert.equal(canReconcileWrite(new HttpError(409, null)), true)
  assert.equal(canReconcileWrite(new HttpError(400, null)), false)
  assert.equal(canReconcileWrite(new SessionChangedError()), false)
})
