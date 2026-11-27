import type { Product } from './catalog.ts'
import type { Order } from './checkout.ts'

export interface AdminProduct extends Product { categoryId: number | null }
export type AdminProductStatus = 'ON_SALE' | 'OFF_SALE'
export interface AdminProductPage { page: number; size: number; total: number; items: AdminProduct[] }
export interface ProductInput { name: string; price: number; categoryId: number | null }
export interface NewProductInput extends ProductInput { stock: number }
export interface ActivityInput {
  productId: number; name: string; totalQuantity: number; perUserLimit?: number | null
  startsAt: string; endsAt: string
}
export interface CouponInput {
  activityId: number; name: string; thresholdAmount: number; discountAmount: number; totalQuantity: number
  claimStartsAt: string; claimEndsAt: string; useStartsAt: string; useEndsAt: string
}
export interface AdminOrderItem extends Omit<Order, 'address'> { userId: number }
export interface AdminOrderPage { page: number; size: number; total: number; items: AdminOrderItem[] }
export interface ShipmentInput { carrier: string; trackingNo: string }
