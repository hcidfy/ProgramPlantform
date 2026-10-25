import { reactive } from 'vue'
import { bootstrapSession } from './api'
import type { Role, UserView } from './types'

export const session = reactive({
  user: null as UserView | null,
  ready: false
})

export function homeFor(role: Role | undefined): string {
  switch (role) {
    case 'student':
      return '/student'
    case 'teacher':
      return '/teacher'
    case 'admin':
      return '/admin'
    default:
      return '/login'
  }
}

/** 只在首次导航时跑一次：拿 refresh cookie 换 access token。 */
export async function ensureSession(): Promise<void> {
  if (session.ready) {
    return
  }
  session.ready = true
  try {
    session.user = await bootstrapSession()
  } catch {
    session.user = null
  }
}
