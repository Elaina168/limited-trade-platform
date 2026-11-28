import { readJson, writeJson } from './http.ts'
import type { Address, AddressInput, CartItem, Order, SeckillStatusResponse } from '../types/checkout.ts'

export const cartItems = () => readJson<CartItem[]>('/api/cart/items')
export const addCartQuantity = (productId: number, delta: number) => writeJson<CartItem>('/api/cart/items', 'POST', { productId, quantity: delta })
export const setCartQuantity = (id: number, quantity: number) => writeJson(`/api/cart/items/${id}`, 'PUT', { quantity })
export const removeCartItem = (id: number) => writeJson(`/api/cart/items/${id}`, 'DELETE')
export const addresses = () => readJson<Address[]>('/api/addresses')
export const createAddress = (data: AddressInput) => writeJson<Address>('/api/addresses', 'POST', data)
export const updateAddress = (id: number, data: AddressInput) => writeJson<Address>(`/api/addresses/${id}`, 'PUT', data)
export const deleteAddress = (id: number) => writeJson(`/api/addresses/${id}`, 'DELETE')
export const setDefaultAddress = (id: number) => writeJson(`/api/addresses/${id}/default`, 'PUT')
export const readOrder = (id: number, expected?: number, signal?: AbortSignal) => readJson<Order>(`/api/orders/${id}`, expected, signal)
export const readSeckillResult = (id: string, expected: number, signal: AbortSignal) =>
  readJson<SeckillStatusResponse>(`/api/activities/seckill/result/${encodeURIComponent(id)}`, expected, signal)
