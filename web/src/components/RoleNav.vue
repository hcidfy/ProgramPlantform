<script setup lang="ts">
import { computed } from 'vue'
import { session } from '../session'

const links = computed(() => {
  switch (session.user?.role) {
    case 'student':
      return [
        { to: '/student', label: '我的课程' },
        { to: '/student/assignments', label: '我的作业' },
        { to: '/student/submissions', label: '我的提交' },
        { to: '/student/knowledge', label: '课程问答' }
      ]
    case 'teacher':
      return [
        { to: '/teacher', label: '我的教学班' },
        { to: '/teacher/problems', label: '题库' },
        { to: '/teacher/assignments', label: '作业' },
        { to: '/teacher/knowledge', label: '知识库' }
      ]
    case 'admin':
      return [
        { to: '/admin/classes', label: '组织管理' },
        { to: '/admin/ai', label: 'AI 配额与用量' },
        { to: '/admin/audit-logs', label: '操作日志' }
      ]
    default:
      return []
  }
})
</script>

<template>
  <nav class="role-nav">
    <router-link v-for="link in links" :key="link.to" :to="link.to">{{ link.label }}</router-link>
  </nav>
</template>
