<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElCascader, ElFormItem } from 'element-plus'
import 'element-plus/es/components/cascader/style/css'
import 'element-plus/es/components/form-item/style/css'
import { addressRegionCodes, addressRegionFromCodes, addressRegionOptions } from '../utils/address-region.ts'

const props = withDefaults(defineProps<{ idPrefix?: string; disabled: boolean }>(), { idPrefix: '' })
const province = defineModel<string>('province', { required: true })
const city = defineModel<string>('city', { required: true })
const district = defineModel<string>('district', { required: true })
const preferredMode = ref<'picker' | 'manual' | null>(null)
const cascaderProps = { value: 'code', label: 'name' }
const codes = computed(() => addressRegionCodes({
  province: province.value, city: city.value, district: district.value,
}))
const regionText = computed(() => [province.value, city.value, district.value].filter(Boolean).join(' / '))
const manual = computed(() => preferredMode.value === 'manual'
  || preferredMode.value === null && !!regionText.value && !codes.value)

function selectRegion(path: unknown) {
  const region = addressRegionFromCodes(path)
  if (!region) return
  province.value = region.province
  city.value = region.city
  district.value = region.district
}
</script>

<template>
  <ElFormItem v-if="!manual" class="region-field" label="省市区" label-position="top">
    <ElCascader class="region-picker" :model-value="codes ?? undefined" :options="addressRegionOptions" :props="cascaderProps"
      :placeholder="regionText || '请选择省市区'" :disabled="props.disabled" filterable
      @update:model-value="selectRegion" />
  </ElFormItem>
  <template v-else>
    <div class="field"><label :for="props.idPrefix + 'province'">省份</label><el-input :id="props.idPrefix + 'province'" v-model="province" :maxlength="50" :disabled="props.disabled" /></div>
    <div class="field"><label :for="props.idPrefix + 'city'">城市</label><el-input :id="props.idPrefix + 'city'" v-model="city" :maxlength="50" :disabled="props.disabled" /></div>
    <div class="field"><label :for="props.idPrefix + 'district'">区县</label><el-input :id="props.idPrefix + 'district'" v-model="district" :maxlength="50" :disabled="props.disabled" /></div>
  </template>
  <div class="business-actions">
    <el-button native-type="button" :disabled="props.disabled" @click="preferredMode = manual ? 'picker' : 'manual'">
      {{ manual ? '从列表选择' : '手动填写' }}
    </el-button>
  </div>
</template>

<style scoped>
.region-field { margin-bottom: 0; }
.region-picker { width: 100%; }
</style>
