import { readOrder } from './checkout.ts'
import { errorMessage, postJson, readJson, SessionChangedError } from './http.ts'
import { orderStatusLabels } from '../utils/orders.ts'
import { canReconcileWrite, reconcileWrite } from '../utils/write-reconciliation.ts'
import type { Order, OrderAction, OrderPage, OrderStatus } from '../types/checkout.ts'

export type OrderFilter = OrderStatus | 'CANCELED_OR_CLOSED' | ''

export function listOrders(page: number, status: OrderFilter) {
  const query = new URLSearchParams({ page: String(page), size: '10' })
  if (status) query.set('status', status)
  return readJson<OrderPage>(`/api/orders?${query}`)
}
export const changeOrder = (id: number, action: OrderAction) => postJson<Order>(`/api/orders/${id}/${action}`)

export async function submitOrderAction(id: number, action: OrderAction, isCurrent: () => boolean, onChecking: () => void):
  Promise<{ snapshot?: Order; notice?: string; error?: string } | undefined> {
  const success = action === 'pay' ? '模拟支付成功。' : action === 'cancel' ? '订单已取消，库存与优惠券占用已释放。' : '已确认收货。'
  try {
    const snapshot = await changeOrder(id, action)
    if (isCurrent()) return { snapshot, notice: success }
  } catch (cause) {
    if (!isCurrent() || cause instanceof SessionChangedError) return
    if (!canReconcileWrite(cause)) return { error: errorMessage(cause) }
    onChecking()
    const result = await reconcileWrite(() => readOrder(id), data =>
      action === 'pay' ? ['PAID', 'SHIPPED', 'COMPLETED'].includes(data.status)
        : data.status === (action === 'cancel' ? 'CANCELED' : 'COMPLETED'), isCurrent)
    if (!result || !isCurrent()) return
    if (!result.snapshot) return { notice: '暂时无法核对订单状态。' }
    return {
      snapshot: result.snapshot,
      notice: result.confirmed ? success : `已更新订单状态为「${orderStatusLabels[result.snapshot.status]}」，未确认本次操作完成。`,
    }
  }
}
