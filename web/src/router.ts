import { createRouter, createWebHistory } from 'vue-router'
import { ensureSession, homeFor, session } from './session'
import AdminHomeView from './views/AdminHomeView.vue'
import LoginView from './views/LoginView.vue'
import StudentCoursesView from './views/StudentCoursesView.vue'
import StudentAssignmentsView from './views/StudentAssignmentsView.vue'
import StudentAssignmentDetailView from './views/StudentAssignmentDetailView.vue'
import StudentSubmissionsView from './views/StudentSubmissionsView.vue'
import StudentKnowledgeView from './views/StudentKnowledgeView.vue'
import TeacherClassesView from './views/TeacherClassesView.vue'
import TeacherProblemsView from './views/TeacherProblemsView.vue'
import TeacherProblemEditView from './views/TeacherProblemEditView.vue'
import TeacherAssignmentsView from './views/TeacherAssignmentsView.vue'
import TeacherAssignmentDetailView from './views/TeacherAssignmentDetailView.vue'
import TeacherKnowledgeView from './views/TeacherKnowledgeView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/login' },
    { path: '/login', component: LoginView, meta: { public: true } },

    { path: '/student', component: StudentCoursesView, meta: { role: 'student' } },
    { path: '/student/assignments', component: StudentAssignmentsView, meta: { role: 'student' } },
    { path: '/student/assignments/:id', component: StudentAssignmentDetailView, meta: { role: 'student' } },
    // Monaco 很大，做题页按需加载（首屏不被 4MB 编辑器拖慢）
    {
      path: '/student/problems/:id',
      component: () => import('./views/ProblemSolveView.vue'),
      meta: { role: 'student' }
    },
    { path: '/student/submissions', component: StudentSubmissionsView, meta: { role: 'student' } },
    { path: '/student/knowledge', component: StudentKnowledgeView, meta: { role: 'student' } },

    { path: '/teacher', component: TeacherClassesView, meta: { role: 'teacher' } },
    { path: '/teacher/problems', component: TeacherProblemsView, meta: { role: 'teacher' } },
    { path: '/teacher/problems/:id', component: TeacherProblemEditView, meta: { role: 'teacher' } },
    { path: '/teacher/assignments', component: TeacherAssignmentsView, meta: { role: 'teacher' } },
    { path: '/teacher/assignments/:id', component: TeacherAssignmentDetailView, meta: { role: 'teacher' } },
    { path: '/teacher/knowledge', component: TeacherKnowledgeView, meta: { role: 'teacher' } },

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
