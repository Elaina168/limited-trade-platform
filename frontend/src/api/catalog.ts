import { readJson, postJson } from './http.ts'
import type { Activity, ActivityCoupon, Category, Product, ProductPage, UserCoupon } from '../types/catalog.ts'

export const categories = () => readJson<Category[]>('/api/categories')
export function searchProducts(page: number, keyword: string, categoryId: string) {
  const query = new URLSearchParams({ page: String(page), size: '12' })
  if (keyword.trim()) query.set('keyword', keyword.trim())
  if (categoryId) query.set('categoryId', categoryId)
  return readJson<ProductPage>(`/api/products/search?${query}`)
}
export const products = () => readJson<Product[]>('/api/products')
export const productById = (id: number) => readJson<Product>(`/api/products/${id}`)
export const activities = () => readJson<Activity[]>('/api/activities')
export const activity = (id: number) => readJson<Activity>(`/api/activities/${id}`)
export const activityCoupons = (id: number) => readJson<ActivityCoupon[]>(`/api/activities/${id}/coupons`)
export const myCoupons = () => readJson<UserCoupon[]>('/api/coupons/mine')
export const claimCoupon = (id: number) => postJson<UserCoupon>(`/api/coupons/${id}/claim`)
