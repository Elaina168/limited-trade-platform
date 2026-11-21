<script setup lang="ts">
import { onUnmounted, reactive, ref, watch } from 'vue'
import { useAuthStore } from '../stores/auth.ts'
import { addresses, createAddress, updateAddress, deleteAddress, setDefaultAddress } from '../api/checkout.ts'
import { errorMessage, SessionChangedError } from '../api/http.ts'
import type { Address, AddressInput } from '../types/checkout.ts'

const auth = useAuthStore()
const list = ref<Address[]>([])
const editing = ref<number | null>(null)
const form = reactive<AddressInput>({ recipientName: '', phone: '', province: '', city: '', district: '', detailAddress: '' })
const busy = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')
let sequence = 0
let operation = 0
function reset() {
  editing.value = null
  Object.assign(form, { recipientName: '', phone: '', province: '', city: '', district: '', detailAddress: '' })
}
function edit(item: Address) {
  editing.value = item.id
  for (const key of Object.keys(form) as (keyof AddressInput)[]) form[key] = item[key]
  error.value = ''
}
async function load() {
  const current = ++sequence
  busy.value = true
  error.value = ''
  list.value = []
  try { const data = await addresses(); if (current === sequence) list.value = data }
  catch (cause) { if (current === sequence && !(cause instanceof SessionChangedError)) error.value = errorMessage(cause) }
  finally { if (current === sequence) busy.value = false }
}
async function mutate(action: () => Promise<unknown>, message: string, clearForm = false) {
  if (saving.value || auth.busy) return
  const current = ++operation
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    await action()
    if (current !== operation) return
    if (clearForm) reset()
    notice.value = message
    await load()
  } catch (cause) {
    if (current === operation && !(cause instanceof SessionChangedError)) error.value = errorMessage(cause)
  } finally { if (current === operation) saving.value = false }
}
function save() {
  const data = Object.fromEntries(Object.entries(form).map(([key, value]) => [key, value.trim()])) as unknown as AddressInput
  if (!data.recipientName || !data.province || !data.city || !data.district || !data.detailAddress
      || [data.recipientName, data.province, data.city, data.district].some(value => value.length > 50)
      || data.detailAddress.length > 200 || !/^1[3-9][0-9]{9}$/.test(data.phone)) {
    error.value = '请完整填写地址；姓名和省市区最多50字，详细地址最多200字，手机号须为有效的11位号码。'
    return
  }
  const id = editing.value
  void mutate(() => id === null ? createAddress(data) : updateAddress(id, data), '地址已保存。', true)
}
function remove(item: Address) {
  if (!window.confirm(`删除「${item.recipientName}」的这条收货地址？`)) return
  void mutate(() => deleteAddress(item.id), '地址已删除。', editing.value === item.id)
}
watch(() => auth.user?.id, () => {
  operation++; sequence++; saving.value = false; list.value = []; notice.value = ''; reset()
}, { flush: 'sync' })
watch(() => auth.status, status => { if (status === 'authenticated') void load() }, { immediate: true })
onUnmounted(() => { sequence++; operation++ })
</script>

<template>
  <p class="section-kicker">DELIVERY ADDRESS</p><h1 class="page-title">收货地址</h1>
  <p class="page-description">管理本人地址，订单创建时保存地址快照。</p>
  <p v-if="error" class="form-error" role="alert">{{ error }}</p>
  <p v-if="notice" class="operation-notice" role="status">{{ notice }}</p>
  <div class="section-heading"><h2>我的地址</h2><el-button :disabled="saving || auth.busy" @click="load">刷新地址</el-button></div>
  <p v-if="busy" role="status">正在加载地址…</p>
  <p v-else-if="!list.length && !error" class="empty-state">还没有收货地址，请在下方添加。</p>
  <div class="market-grid">
    <article v-for="item in list" :key="item.id" class="market-card">
      <h2>{{ item.recipientName }} <span v-if="item.defaultAddress" class="role-badge">默认地址</span></h2>
      <p>{{ item.phone }}</p><p class="muted">{{ item.province }} {{ item.city }} {{ item.district }} {{ item.detailAddress }}</p>
      <div class="business-actions">
        <el-button :disabled="saving || auth.busy" @click="edit(item)">编辑</el-button>
        <el-button :disabled="saving || auth.busy || item.defaultAddress" @click="mutate(() => setDefaultAddress(item.id), '默认地址已更新。')">设为默认</el-button>
        <el-button :disabled="saving || auth.busy" @click="remove(item)">删除</el-button>
      </div>
    </article>
  </div>
  <section class="market-card address-editor">
    <h2>{{ editing === null ? '新增地址' : '编辑地址' }}</h2>
    <form @submit.prevent="save" novalidate>
      <fieldset :disabled="saving || auth.busy" class="business-fields">
        <div class="field"><label for="recipientName">收货人</label><input id="recipientName" v-model="form.recipientName" maxlength="50" autocomplete="name" /></div>
        <div class="field"><label for="phone">手机号</label><input id="phone" v-model="form.phone" maxlength="11" inputmode="tel" autocomplete="tel" /></div>
        <div class="field"><label for="province">省份</label><input id="province" v-model="form.province" maxlength="50" /></div>
        <div class="field"><label for="city">城市</label><input id="city" v-model="form.city" maxlength="50" /></div>
        <div class="field"><label for="district">区县</label><input id="district" v-model="form.district" maxlength="50" /></div>
        <div class="field"><label for="detailAddress">详细地址</label><input id="detailAddress" v-model="form.detailAddress" maxlength="200" autocomplete="street-address" /></div>
      </fieldset>
      <div class="business-actions"><el-button type="primary" native-type="submit" :loading="saving" :disabled="auth.busy">保存地址</el-button><el-button v-if="editing !== null" :disabled="saving || auth.busy" @click="reset">取消编辑</el-button></div>
    </form>
  </section>
</template>
