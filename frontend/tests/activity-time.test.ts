import test from 'node:test'
import assert from 'node:assert/strict'
import { activityStatus, beijingTimestamp, formatActivityTime } from '../src/utils/activity-time.ts'

test('按北京时间比较活动开始与截止，不受浏览器时区影响', () => {
  const activity = { startsAt: '2026-11-24T11:00:00', endsAt: '2026-11-24T12:00:00', remainingQuantity: 32 }
  assert.equal(beijingTimestamp(activity.endsAt), Date.UTC(2026, 10, 24, 4))
  assert.equal(activityStatus(activity, Date.UTC(2026, 10, 24, 2, 59, 59)), '即将开始')
  assert.equal(activityStatus(activity, Date.UTC(2026, 10, 24, 3)), '进行中')
  assert.equal(activityStatus(activity, Date.UTC(2026, 10, 24, 4)), '已结束')
})

test('能区分本年与次年同月同日的活动', () => {
  const activity = { startsAt: '2026-11-24T11:00:00', endsAt: '2027-11-24T12:00:00', remainingQuantity: 32 }
  assert.equal(activityStatus(activity, Date.UTC(2026, 10, 25, 8)), '进行中')
  assert.equal(formatActivityTime(activity.endsAt), '2027年11月24日 12:00:00')
})
