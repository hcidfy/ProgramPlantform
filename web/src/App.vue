<script setup lang="ts">
import { useRouter } from 'vue-router'
import RoleNav from './components/RoleNav.vue'
import { logout } from './api'
import { session } from './session'

const router = useRouter()

const roleLabel = (role?: string) =>
  role === 'student' ? '学生' : role === 'teacher' ? '教师' : role === 'admin' ? '管理员' : ''

async function onLogout() {
  await logout()
  session.user = null
  router.replace('/login')
}
</script>

<template>
  <header class="topbar">
    <div class="brand">在线代码学习与知识问答平台</div>
    <div v-if="session.user" class="who">
      <RoleNav v-if="session.user" />
      <span class="badge">{{ roleLabel(session.user.role) }}</span>
      <span class="name">{{ session.user.displayName }}</span>
      <button class="link" type="button" @click="onLogout">退出登录</button>
    </div>
  </header>
  <main class="page">
    <router-view />
  </main>
</template>
