export interface CurrentUser {
  id: number
  username: string
  role: 'USER' | 'ADMIN'
}

export interface CsrfToken {
  headerName: string
  parameterName: string
  token: string
}

export interface LoginCredentials {
  username: string
  password: string
}

export type AuthStatus = 'unloaded' | 'loading' | 'anonymous' | 'authenticated' | 'failed'
