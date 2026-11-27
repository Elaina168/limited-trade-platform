<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElDialog, ElMessage } from 'element-plus'
import 'element-plus/es/components/dialog/style/css'
import 'element-plus/es/components/message/style/css'
import { addresses, createAddress } from '../api/checkout.ts'
import { errorMessage, SessionChangedError, sessionRevision } from '../api/http.ts'
import { canReconcileWrite, reconcileWrite } from '../utils/write-reconciliation.ts'
import type { Address, AddressInput } from '../types/checkout.ts'
import { useAuthStore } from '../stores/auth.ts'
import AddressRegionFields from './AddressRegionFields.vue'

const open = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [address: Address, list: Address[]] }>()
const auth = useAuthStore()
const empty = { recipientName: '', phone: '', province: '', city: '', district: '', detailAddress: '' }
const form = reactive<AddressInput>({ ...empty })
const fields = [
  { key: 'recipientName', label: '收货人', max: 50, autocomplete: 'name' },
  { key: 'phone', label: '手机号', max: 11, autocomplete: 'tel' },
  { key: 'province', label: '省份', max: 50 },
  { key: 'city', label: '城市', max: 50 },
  { key: 'district', label: '区县', max: 50 },
  { key: 'detailAddress', label: '详细地址', max: 200, autocomplete: 'street-address' },
] as const
const saving = ref(false)
const error = ref('')
let operation = 0

watch(open, () => {
  operation++
  Object.assign(form, empty)
  saving.value = false
  error.value = ''
})
watch(() => auth.user?.id, () => { open.value = false }, { flush: 'sync' })

async function save() {
  if (!open.value || saving.value || auth.busy) return
  const data = Object.fromEntries(fields.map(({ key }) => [key, form[key].trim()])) as unknown as AddressInput
  if (fields.some(({ key, max }) => !data[key] || data[key].length > max) || !/^1[3-9][0-9]{9}$/.test(data.phone)) {
    error.value = '请完整填写地址；姓名和省市区最多50字，详细地址最多200字，手机号须为有效的11位号码。'
    return
  }
  const current = ++operation
  const revision = sessionRevision()
  const isCurrent = () => open.value && current === operation && revision === sessionRevision()
  error.value = ''
  saving.value = true
  try {
    const before = await addresses()
    if (!isCurrent()) return
    const previous = new Set(before.map(item => item.id))
    let created: Address | undefined
    try {
      created = await createAddress(data)
    } catch (cause) {
      if (!isCurrent() || cause instanceof SessionChangedError) return
      if (!canReconcileWrite(cause)) throw cause
    }
    if (!isCurrent()) return
    // 回执丢失时仅接受本次操作前不存在且字段匹配的地址。
    const result = await reconcileWrite(addresses, rows => {
      if (!created) created = rows.find(item => !previous.has(item.id) && fields.every(({ key }) => item[key] === data[key]))
      return !!created && rows.some(item => item.id === created?.id)
    }, isCurrent)
    if (!isCurrent()) return
    if (!created) {
      error.value = '暂未确认地址是否保存，请稍后再试。'
      return
    }
    emit('saved', created, result?.confirmed ? result.snapshot! : [...before, created])
    open.value = false
    ElMessage.success('地址已保存。')
  } catch (cause) {
    if (isCurrent() && !(cause instanceof SessionChangedError)) error.value = errorMessage(cause)
  } finally {
    if (current === operation) saving.value = false
  }
}
</script>

<template>
  <ElDialog v-model="open" title="新增收货地址" width="min(480px, 92vw)" append-to-body
    :show-close="!saving" :close-on-click-modal="!saving" :close-on-press-escape="!saving">
    <form @submit.prevent="save" novalidate>
      <fieldset class="business-fields" :disabled="saving || auth.busy">
        <div v-for="field in fields.slice(0, 2)" :key="field.key" class="field">
          <label :for="'checkout-address-' + field.key">{{ field.label }}</label>
          <el-input :id="'checkout-address-' + field.key" v-model="form[field.key]" :maxlength="field.max"
            :autocomplete="'autocomplete' in field ? field.autocomplete : undefined" :inputmode="field.key === 'phone' ? 'tel' : undefined" />
        </div>
        <AddressRegionFields id-prefix="checkout-address-" v-model:province="form.province" v-model:city="form.city"
          v-model:district="form.district" :disabled="saving || auth.busy" />
        <div class="field">
          <label for="checkout-address-detailAddress">详细地址</label>
          <el-input id="checkout-address-detailAddress" v-model="form.detailAddress" :maxlength="200" autocomplete="street-address" />
        </div>
      </fieldset>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <el-button type="primary" native-type="submit" :loading="saving" :disabled="auth.busy">保存地址</el-button>
    </form>
  </ElDialog>
</template>
