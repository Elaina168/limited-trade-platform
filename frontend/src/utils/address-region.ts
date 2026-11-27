import regionData from '../data/china-address-regions.json' with { type: 'json' }
import type { AddressInput } from '../types/checkout.ts'

type CodeOption = { code: string; name: string; disabled?: boolean; children?: CodeOption[] }
type AddressRegion = Pick<AddressInput, 'province' | 'city' | 'district'>

export const addressRegionOptions: CodeOption[] = regionData

export function addressRegionCodes(region: AddressRegion): string[] | null {
  const province = addressRegionOptions.find(option => option.name === region.province)
  const city = province?.children?.find(option => option.name === region.city)
  const district = city?.children?.find(option => option.name === region.district)
  return province && city && district ? [province.code, city.code, district.code] : null
}

export function addressRegionFromCodes(path: unknown): AddressRegion | null {
  if (!Array.isArray(path) || path.length !== 3) return null
  const province = addressRegionOptions.find(option => option.code === path[0])
  const city = province?.children?.find(option => option.code === path[1])
  const district = city?.children?.find(option => option.code === path[2])
  return province && city && district
    ? { province: province.name, city: city.name, district: district.name }
    : null
}
