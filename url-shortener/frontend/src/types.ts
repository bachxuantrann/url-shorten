export type UrlStatus = 'ACTIVE' | 'DISABLED' | 'EXPIRED' | 'BLOCKED' | 'DELETED'

export interface ApiEnvelope<T> {
  success: boolean
  code: string
  message: string
  data: T
}

export interface User {
  id: string
  username: string
  email: string
  fullName: string
  roles?: string[]
}

export interface LoginData {
  accessToken: string
  refreshToken: string
  expiresIn: number
  user: User
}

export interface UrlItem {
  shortCode: string
  shortUrl: string
  originalUrl: string
  status: UrlStatus
  passwordProtected: boolean
  blockedReason?: string
  expiresAt?: string
  clickCount: number
  createdAt: string
  updatedAt: string
}

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  hasNext: boolean
  hasPrevious: boolean
}
