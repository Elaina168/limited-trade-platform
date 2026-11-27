import { readJson, postJson } from './http.ts'
import type { Activity, ActivityCoupon, Category, Product, ProductPage, ProductSearchFilters, UserCoupon } from '../types/catalog.ts'

export const categories = () => readJson<Category[]>('/api/categories')
export function searchProducts(page: number, keyword: string, categoryId: string, filters: ProductSearchFilters = {}) {
  const query = new URLSearchParams({ page: String(page), size: '12' })
  if (keyword.trim()) query.set('keyword', keyword.trim())
  if (categoryId) query.set('categoryId', categoryId)
  if (filters.minPrice?.trim()) query.set('minPrice', filters.minPrice.trim())
  if (filters.maxPrice?.trim()) query.set('maxPrice', filters.maxPrice.trim())
  if (filters.inStockOnly) query.set('inStockOnly', 'true')
  if (filters.sort) query.set('sort', filters.sort)
  return readJson<ProductPage>(`/api/products/search?${query}`)
}
export function suggestProducts(prefix: string, signal?: AbortSignal) {
  const clean = prefix.trim()
  if (!clean) return Promise.resolve<string[]>([])
  const query = new URLSearchParams({ prefix: clean, limit: '8' })
  return readJson<string[]>(`/api/products/suggest?${query}`, undefined, signal)
}
export const products = () => readJson<Product[]>('/api/products')
export const productById = (id: number) => readJson<Product>(`/api/products/${id}`)
export const activities = () => readJson<Activity[]>('/api/activities')
export const activity = (id: number) => readJson<Activity>(`/api/activities/${id}`)
export const activityCoupons = (id: number) => readJson<ActivityCoupon[]>(`/api/activities/${id}/coupons`)
export const myCoupons = () => readJson<UserCoupon[]>('/api/coupons/mine')
export const claimCoupon = (id: number) => postJson<UserCoupon>(`/api/coupons/${id}/claim`)
