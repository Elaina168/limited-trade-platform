import { readJson, writeJson } from './http.ts'
import type { Activity, ActivityCoupon, Category } from '../types/catalog.ts'
import type { Order, OrderStatus } from '../types/checkout.ts'
import type { ActivityInput, AdminOrderPage, AdminProduct, AdminProductPage, AdminProductStatus, CouponInput, NewProductInput, ProductInput, ShipmentInput } from '../types/admin.ts'

export const adminProducts = () => readJson<AdminProduct[]>('/api/admin/products')
export function adminProductsPage(page: number, keyword: string, categoryId: string, status: AdminProductStatus | '', expected?: number) {
  const query = new URLSearchParams({ page: String(page), size: '20' })
  if (keyword.trim()) query.set('keyword', keyword.trim())
  if (categoryId) query.set('categoryId', categoryId)
  if (status) query.set('status', status)
  return readJson<AdminProductPage>(`/api/admin/products/page?${query}`, expected)
}
export const adminProduct = (id: number, expected?: number) => readJson<AdminProduct>(`/api/admin/products/${id}`, expected)
export const createProduct = (data: NewProductInput) => writeJson<AdminProduct>('/api/admin/products', 'POST', data)
export const updateProduct = (id: number, data: ProductInput) => writeJson<AdminProduct>(`/api/admin/products/${id}`, 'PUT', data)
export const updateProductStatus = (id: number, status: AdminProductStatus) => writeJson<AdminProduct>(`/api/admin/products/${id}/status`, 'PATCH', { status })
export const createCategory = (name: string) => writeJson<Category>('/api/admin/categories', 'POST', { name })
export const renameCategory = (id: number, name: string) => writeJson<Category>(`/api/admin/categories/${id}`, 'PUT', { name })
export const createActivity = (data: ActivityInput) => writeJson<Activity>('/api/admin/activities', 'POST', data)
export const createCoupon = (data: CouponInput) => writeJson<ActivityCoupon>('/api/admin/coupons', 'POST', data)
export function adminOrders(page: number, status: OrderStatus | '', userId: string) {
  const query = new URLSearchParams({ page: String(page), size: '10' })
  if (status) query.set('status', status)
  if (userId) query.set('userId', userId)
  return readJson<AdminOrderPage>(`/api/admin/orders?${query}`)
}
export const adminOrder = (id: number) => readJson<Order>(`/api/admin/orders/${id}`)
export const shipOrder = (id: number, data: ShipmentInput) => writeJson<Order>(`/api/admin/orders/${id}/ship`, 'POST', data)
