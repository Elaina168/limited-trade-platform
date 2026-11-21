import { postJson, readJson } from './http.ts'
import type { Order, OrderAction, OrderPage, OrderStatus } from '../types/checkout.ts'

export function listOrders(page: number, status: OrderStatus | '') {
  const query = new URLSearchParams({ page: String(page), size: '10' })
  if (status) query.set('status', status)
  return readJson<OrderPage>(`/api/orders?${query}`)
}
export const changeOrder = (id: number, action: OrderAction) => postJson<Order>(`/api/orders/${id}/${action}`)
