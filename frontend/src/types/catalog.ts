export interface Category { id: number; name: string }
export interface Product { id: number; name: string; price: number; stock: number; status: string }
export interface ProductSearchItem extends Product { categoryId: number | null; categoryName: string | null }
export interface ProductPage { page: number; size: number; total: number; items: ProductSearchItem[] }
export interface Activity {
  id: number; productId: number; name: string; totalQuantity: number; remainingQuantity: number
  startsAt: string; endsAt: string
}
export interface ActivityCoupon {
  id: number; activityId: number; name: string; thresholdAmount: number; discountAmount: number
  totalQuantity: number; remainingQuantity: number
  claimStartsAt: string; claimEndsAt: string; useStartsAt: string; useEndsAt: string
}
export interface UserCoupon {
  id: number; status: string; claimedAt: string; orderId: number | null; coupon: ActivityCoupon; expired: boolean
}
