import type { Order, OrderAction, OrderStatus } from '../types/checkout.ts'

export const orderStatusLabels: Record<OrderStatus, string> = {
  PENDING_PAYMENT: '待支付', PAID: '已支付', CANCELED: '已取消', CLOSED: '已关闭', SHIPPED: '已发货', COMPLETED: '已完成',
}
export const orderActionLabels: Record<OrderAction, string> = { pay: '模拟支付', cancel: '取消订单', receive: '确认收货' }

export function availableOrderActions(order: Order, now: number): OrderAction[] {
  if (order.status === 'PENDING_PAYMENT') {
    // 支付截止不代表已关闭；关闭前仍可取消，最终条件由后端事务判断。
    return now < new Date(order.expiresAt).getTime() ? ['pay', 'cancel'] : ['cancel']
  }
  return order.status === 'SHIPPED' ? ['receive'] : []
}
