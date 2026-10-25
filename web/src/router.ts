import { createRouter, createWebHistory } from 'vue-router'
import { ensureSession, homeFor, session } from './session'
import AdminHomeView from './views/AdminHomeView.vue'
import LoginView from './views/LoginView.vue'
import StudentCoursesView from './views/StudentCoursesView.vue'
import TeacherClassesView from './views/TeacherClassesView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/login' },
    { path: '/login', component: LoginView, meta: { public: true } },
    { path: '/student', component: StudentCoursesView, meta: { role: 'student' } },
    { path: '/teacher', component: TeacherClassesView, meta: { role: 'teacher' } },
    { path: '/admin', component: AdminHomeView, meta: { role: 'admin' } },
    { path: '/:pathMatch(.*)*', redirect: '/login' }
  ]
})

router.beforeEach(async (to) => {
  await ensureSession()
  if (to.meta.public) {
    return true
  }
  if (!session.user) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.role && session.user.role !== to.meta.role) {
    return homeFor(session.user.role)
  }
  return true
})

export default router
