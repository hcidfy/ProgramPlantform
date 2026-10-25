<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, login, register } from '../api'
import { homeFor, session } from '../session'

const router = useRouter()
const route = useRoute()

const mode = ref<'login' | 'register'>('login')
const email = ref('')
const password = ref('')
const displayName = ref('')
const busy = ref(false)
const error = ref('')
const notice = ref('')

function switchMode(next: 'login' | 'register') {
  mode.value = next
  error.value = ''
  notice.value = ''
}

async function onSubmit() {
  error.value = ''
  notice.value = ''
  busy.value = true
  try {
    if (mode.value === 'login') {
      const user = await login(email.value.trim(), password.value)
      session.user = user
      const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : ''
      await router.replace(redirect || homeFor(user.role))
    } else {
      await register(email.value.trim(), displayName.value.trim(), password.value)
      notice.value = '注册成功，请用刚设置的密码登录'
      mode.value = 'login'
      displayName.value = ''
    }
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '网络异常，请稍后再试'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <form class="auth" @submit.prevent="onSubmit">
    <h1>在线代码学习与知识问答平台</h1>
    <p class="sub">登录后查看你的课程与作业</p>

    <div class="tabs">
      <button type="button" :class="{ active: mode === 'login' }" @click="switchMode('login')">登录</button>
      <button type="button" :class="{ active: mode === 'register' }" @click="switchMode('register')">学生注册</button>
    </div>

    <label>
      <span>邮箱</span>
      <input v-model="email" type="email" autocomplete="username" required />
    </label>

    <label v-if="mode === 'register'">
      <span>姓名</span>
      <input v-model="displayName" type="text" maxlength="64" required />
    </label>

    <label>
      <span>密码</span>
      <input v-model="password" type="password" autocomplete="current-password" required />
    </label>

    <p v-if="mode === 'register'" class="meta">密码 8~64 位，需同时包含字母与数字</p>

    <button class="primary" type="submit" :disabled="busy">
      {{ busy ? '处理中…' : mode === 'login' ? '登录' : '注册' }}
    </button>

    <p v-if="error" class="error">{{ error }}</p>
    <p v-if="notice" class="info">{{ notice }}</p>
  </form>
</template>
