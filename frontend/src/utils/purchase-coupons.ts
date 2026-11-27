import type { UserCoupon } from '../types/catalog.ts'
import { beijingTimestamp } from './activity-time.ts'

export function pickBestCoupon(usable: readonly UserCoupon[]): UserCoupon | undefined {
  return [...usable].sort((a, b) => Number(b.coupon.discountAmount) - Number(a.coupon.discountAmount)
    || beijingTimestamp(a.coupon.useEndsAt) - beijingTimestamp(b.coupon.useEndsAt))[0]
}
