import { onScopeDispose, ref } from 'vue'
import { addCartQuantity, cartItems, setCartQuantity } from '../api/checkout.ts'
import { errorMessage, HttpError, isUncertainWrite, onSessionChange, SessionChangedError } from '../api/http.ts'
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

  async function addQuantity(productId: number, delta: number) {
    if (saving.value) return
    const current = ++operation
    saving.value = true; error.value = ''; notice.value = ''
    try {
      // 先读取该商品的当前数量，累加写入丢失回执时只能读取核对。
      const before = (await cartItems()).find(item => item.productId === productId)
      if (current !== operation) return
      const expected = (before?.quantity ?? 0) + delta
      try {
        const saved = await addCartQuantity(productId, delta)
        if (current === operation) return { item: saved, confirmed: true }
      } catch (cause) {
        if (current !== operation || cause instanceof SessionChangedError) return
        if (cause instanceof HttpError && cause.status === 409) {
          error.value = '无法继续加购，商品可能已售罄、下架或达到可购数量上限。'
          return
        }
        if (!isUncertainWrite(cause)) { error.value = errorMessage(cause); return }
        notice.value = '正在核对购物车数量…'
        const result = await reconcileWrite(cartItems, cart => cart.some(row => row.productId === productId && row.quantity === expected),
          () => current === operation)
        if (current !== operation || !result) return
        const item = result.snapshot?.find(row => row.productId === productId)
        notice.value = result.confirmed ? '' : '暂未确认本次加购，请以购物车当前数量为准。'
        return { item, snapshot: result.snapshot, confirmed: result.confirmed }
      }
    } catch (cause) {
      if (current === operation && !(cause instanceof SessionChangedError)) error.value = errorMessage(cause)
    } finally { if (current === operation) saving.value = false }
  }

  const unsubscribe = onSessionChange(resetCartSave)
  onScopeDispose(() => { unsubscribe(); resetCartSave() })
  return { saving, error, notice, saveQuantity, addQuantity, resetCartSave }
}
