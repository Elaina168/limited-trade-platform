import { onScopeDispose, ref } from 'vue'
import { cartItems, setCartQuantity } from '../api/checkout.ts'
import { errorMessage, SessionChangedError } from '../api/http.ts'
import { canReconcileWrite, reconcileWrite } from '../utils/write-reconciliation.ts'

export function useCartQuantitySave() {
  const saving = ref(false)
  const error = ref('')
  const notice = ref('')
  let operation = 0

  function resetCartSave() {
    operation++
    saving.value = false; error.value = ''; notice.value = ''
  }

  async function saveQuantity(productId: number, quantity: number) {
    if (saving.value) return
    const current = ++operation
    const success = '已加入购物车。'
    saving.value = true; error.value = ''; notice.value = ''
    try {
      await setCartQuantity(productId, quantity)
      if (current === operation) return success
    } catch (cause) {
      if (current !== operation || cause instanceof SessionChangedError) return
      if (!canReconcileWrite(cause)) { error.value = errorMessage(cause); return }
      notice.value = '正在核对购物车数量…'
      const result = await reconcileWrite(cartItems, cart => cart.some(item => item.productId === productId && item.quantity === quantity),
        () => current === operation)
      if (current !== operation) return
      if (result?.confirmed) { notice.value = ''; return success }
      const actual = result?.snapshot?.find(item => item.productId === productId)?.quantity
      notice.value = !result ? '' : result.snapshot
        ? (actual === undefined ? '购物车中暂未查到该商品。' : `购物车中该商品当前数量为 ${actual}。`)
        : '暂时无法核对购物车数量。'
    } finally { if (current === operation) saving.value = false }
  }

  onScopeDispose(resetCartSave)
  return { saving, error, notice, saveQuantity, resetCartSave }
}
