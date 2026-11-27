<script setup lang="ts">
import { ref } from 'vue'
import { categories } from '../api/catalog.ts'
import { createCategory, renameCategory } from '../api/admin.ts'
import { HttpError, errorMessage } from '../api/http.ts'
import { useAdminPage } from '../composables/useAdminPage.ts'
import type { Category } from '../types/catalog.ts'
const name = ref('')
const editing = ref<number | null>(null)
function resetDraft() { name.value = ''; editing.value = null }
const { data, busy, saving, blocked, error, notice, load, mutate } = useAdminPage(categories, resetDraft, cause => {
  if (cause instanceof HttpError && cause.status === 409) return `分类名称已存在，请换一个名称。${cause.traceId ? ` 追踪编号 ${cause.traceId}` : ''}`
  return errorMessage(cause)
})
function edit(item: Category) { editing.value = item.id; name.value = item.name; error.value = '' }
function save() {
  const value = name.value.trim()
  if (!value || value.length > 50) { error.value = '分类名称须为1至50个字符。'; return }
  const id = editing.value
  void mutate(() => id === null ? createCategory(value) : renameCategory(id, value), '分类已保存。', resetDraft,
    id === null ? undefined : list => list.some(item => item.id === id && item.name === value))
}
</script>
<template>
  <h1 class="page-title">分类管理</h1><p class="page-description">新增分类或重命名已有分类。</p>
  <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="notice" class="operation-notice" role="status">{{ notice }}</p>
  <el-button :loading="busy" :disabled="saving" @click="load">刷新分类</el-button>
  <section class="market-card address-editor"><h2>{{ editing === null ? '新增分类' : '重命名分类' }}</h2>
    <form @submit.prevent="save" novalidate><div class="field"><label for="category-name">分类名称</label><el-input id="category-name" v-model="name" class="admin-input" maxlength="50" :disabled="blocked" /></div>
      <div class="business-actions"><el-button type="primary" native-type="submit" :disabled="blocked" :loading="saving">保存分类</el-button><el-button v-if="editing !== null" :disabled="blocked" @click="resetDraft">取消编辑</el-button></div>
    </form>
  </section>
  <p v-if="busy && !data" role="status">正在加载分类…</p><p v-else-if="data && !data.length" class="empty-state">暂无分类。</p>
  <div class="market-grid admin-grid"><article v-for="item in data" :key="item.id" class="market-card"><h2>{{ item.name }}</h2><p class="muted">分类 #{{ item.id }}</p><el-button :disabled="blocked" @click="edit(item)">重命名</el-button></article></div>
</template>
