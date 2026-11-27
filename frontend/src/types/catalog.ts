export interface Category { id: number; name: string }
export interface CategoryFacet { categoryId: number | null; categoryName: string | null; count: number }
export interface Product { id: number; name: string; price: number; stock: number; status: string }
export interface ProductSearchItem extends Product {
  categoryId: number | null
  categoryName: string | null
  highlightedName?: string | null
}
export interface ProductPage {
  page: number
  size: number
  total: number
  items: ProductSearchItem[]
  categoryFacets?: CategoryFacet[]
  engine?: string
  degraded?: boolean
}
export interface ProductSearchFilters {
  minPrice?: string
  maxPrice?: string
  inStockOnly?: boolean
  sort?: '' | 'relevance' | 'price_asc' | 'price_desc'
}
export interface Activity {
  id: number; productId: number; name: string; totalQuantity: number; remainingQuantity: number
  perUserLimit?: number | null
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
