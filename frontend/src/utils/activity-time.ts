import type { Activity } from '../types/catalog.ts'

// 服务端活动和优惠券时间是没有偏移量的北京时间。
export function beijingTimestamp(value: string) {
  return Date.parse(value + '+08:00')
}

export function formatActivityTime(value: string) {
  return value.replace('-', '年').replace('-', '月').replace('T', '日 ')
}

export function activityStatus(activity: Pick<Activity, 'startsAt' | 'endsAt' | 'remainingQuantity'>, now: number) {
  if (now < beijingTimestamp(activity.startsAt)) return '即将开始'
  if (now >= beijingTimestamp(activity.endsAt)) return '已结束'
  return activity.remainingQuantity > 0 ? '进行中' : '名额已用完'
}
