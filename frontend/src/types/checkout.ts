import type { Product } from './catalog.ts'

export interface CartItem extends Omit<Product, 'id'> { productId: number; quantity: number }
export interface AddressInput {
  recipientName: string; phone: string; province: string; city: string; district: string; detailAddress: string
}
export interface Address extends AddressInput { id: number; defaultAddress: boolean }
export interface DirectPurchase {
  productId: number; quantity: number; activityId: number | null; userCouponId: number | null; addressId: number
}
export interface CartCheckout { productIds: number[]; addressId: number }
export type OrderIntent =
  | { path: '/api/orders'; data: DirectPurchase; requestId: string }
  | { path: '/api/orders/checkout'; data: CartCheckout; requestId: string }
export interface OrderItem {
  productId: number; productName: string; unitPrice: number; quantity: number; lineAmount: number; activityId: number | null
}
export type OrderStatus = 'PENDING_PAYMENT' | 'PAID' | 'CANCELED' | 'CLOSED' | 'SHIPPED' | 'COMPLETED'
export type OrderAction = 'pay' | 'cancel' | 'receive'
export interface OrderPage { page: number; size: number; total: number; items: Order[] }
export interface Order {
  id: number; totalAmount: number; originalAmount: number; discountAmount: number; status: OrderStatus; requestId: string
  createdAt: string; expiresAt: string; paidAt: string | null; endedAt: string | null
  items: OrderItem[]; address: AddressInput | null
  shipment: { carrier: string; trackingNo: string; shippedAt: string; receivedAt: string | null } | null
  coupon: { userCouponId: number; couponId: number; activityId: number; name: string; thresholdAmount: number; discountAmount: number; useStartsAt: string; useEndsAt: string } | null
}
