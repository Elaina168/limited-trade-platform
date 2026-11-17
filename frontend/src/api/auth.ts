import type { CurrentUser, LoginCredentials } from '../types/auth.ts'
import { postForm, readJson, RequestError } from './http.ts'

export async function currentUser(revision: number): Promise<CurrentUser> {
  const user = await readJson<CurrentUser>('/api/auth/me', revision)
  if (!Number.isSafeInteger(user.id) || typeof user.username !== 'string'
      || (user.role !== 'USER' && user.role !== 'ADMIN')) {
    throw new RequestError('登录身份数据无法识别，请联系管理员。')
  }
  return user
}

export function login(credentials: LoginCredentials, revision: number): Promise<void> {
  return postForm('/api/auth/login', { username: credentials.username, password: credentials.password }, revision)
}

export function logout(revision: number): Promise<void> {
  return postForm('/api/auth/logout', {}, revision)
}
