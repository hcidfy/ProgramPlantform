import type { UserView } from './types'

/**
 * access token 只放内存，不落 localStorage：
 * 刷新页面时用 httpOnly 的 refresh cookie 换新 token（PRD §13.1）。
 */
let accessToken: string | null = null
let refreshing: Promise<boolean> | null = null

export class ApiError extends Error {
  status: number
  code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

export function setAccessToken(token: string | null): void {
  accessToken = token
}

export function getAccessToken(): string | null {
  return accessToken
}

async function doRefresh(): Promise<boolean> {
  if (!refreshing) {
    refreshing = (async () => {
      try {
        const res = await fetch('/api/auth/refresh', { method: 'POST', credentials: 'include' })
        if (!res.ok) {
          accessToken = null
          return false
        }
        const body = (await res.json()) as { accessToken: string }
        accessToken = body.accessToken
        return true
      } catch {
        accessToken = null
        return false
      }
    })()
    refreshing.then(() => {
      refreshing = null
    })
  }
  return refreshing
}

export async function request<T>(path: string, init: RequestInit = {}, retry = true): Promise<T> {
  const headers = new Headers(init.headers)
  // 只有字符串 body（JSON）才补 Content-Type；FormData 必须让浏览器自己带 boundary
  if (typeof init.body === 'string' && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (accessToken) {
    headers.set('Authorization', `Bearer ${accessToken}`)
  }
  const res = await fetch(path, { ...init, headers, credentials: 'include' })
  if (res.status === 401 && retry && !path.startsWith('/api/auth/')) {
    if (await doRefresh()) {
      return request<T>(path, init, false)
    }
  }
  if (!res.ok) {
    let code = `HTTP_${res.status}`
    let message = res.statusText || '请求失败'
    try {
      const body = (await res.json()) as { code?: string; message?: string }
      code = body.code ?? code
      message = body.message ?? message
    } catch {
      /* 响应体不是 JSON，保留默认文案 */
    }
    throw new ApiError(res.status, code, message)
  }
  if (res.status === 204) {
    return undefined as T
  }
  return (await res.json()) as T
}

export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
  user: UserView
}

export async function login(email: string, password: string): Promise<UserView> {
  const body = await request<LoginResponse>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password })
  }, false)
  accessToken = body.accessToken
  return body.user
}

export async function register(email: string, displayName: string, password: string): Promise<UserView> {
  return request<UserView>('/api/auth/register', {
    method: 'POST',
    body: JSON.stringify({ email, displayName, password })
  }, false)
}

export async function logout(): Promise<void> {
  try {
    await request<void>('/api/auth/logout', { method: 'POST' })
  } finally {
    accessToken = null
  }
}

/** 刷新页面后恢复登录态：refresh cookie → 新 access token → /me。 */
export async function bootstrapSession(): Promise<UserView | null> {
  const ok = await doRefresh()
  if (!ok) {
    return null
  }
  try {
    return await request<UserView>('/api/auth/me', {}, false)
  } catch {
    return null
  }
}
