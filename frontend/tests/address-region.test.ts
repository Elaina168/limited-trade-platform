
import assert from 'node:assert/strict'
import test from 'node:test'
import { addressRegionCodes, addressRegionFromCodes, addressRegionOptions } from '../src/utils/address-region.ts'

test('地址级联使用省市区完整代码所对应的名称', () => {
  const region = { province: '浙江省', city: '杭州市', district: '西湖区' }
  const path = ['330000', '330100', '330106']
  assert.deepEqual(addressRegionCodes(region), path)
  assert.deepEqual(addressRegionFromCodes(path), region)
  assert.equal(addressRegionFromCodes(['360000', '330100', '330106']), null)
})

test('直辖市保留三个地址字段，支持重复的省市名称', () => {
  const region = { province: '北京市', city: '北京市', district: '海淀区' }
  const path = addressRegionCodes(region)
  assert.equal(path?.length, 3)
  assert.equal(path?.at(-1), '110108')
  assert.deepEqual(addressRegionFromCodes(path), region)
})

test('缺少标准区县的地区和旧自定义地址走文本填写', () => {
  const custom = { province: '广东省', city: '东莞市', district: '自定义街道' }
  assert.equal(addressRegionCodes(custom), null)
  assert.equal(addressRegionFromCodes(['440000', '441900']), null)
  assert.equal(addressRegionOptions.length, 34)
  assert.equal(addressRegionOptions.find(province => province.name === '台湾省')?.disabled, true)
})
