import type { Activity } from '../types/catalog.ts'
import type { CouponInput } from '../types/admin.ts'

export const validMoney = (value: string) => /^\d{1,8}(\.\d{1,2})?$/.test(value.trim()) && Number(value) > 0
export const validQuantity = (value: number, allowZero = false) => Number.isInteger(value) && value >= (allowZero ? 0 : 1) && value <= 2147483647
export function validCouponWindow(rule: CouponInput, activity: Activity) {
  const [claimStart, claimEnd, useStart, useEnd, start, end] = [rule.claimStartsAt, rule.claimEndsAt, rule.useStartsAt, rule.useEndsAt, activity.startsAt, activity.endsAt].map(value => new Date(value).getTime())
  return [claimStart, claimEnd, useStart, useEnd, start, end].every(Number.isFinite)
    && claimStart < claimEnd && useStart < useEnd && claimEnd <= useEnd && useStart >= start && useEnd <= end
}
