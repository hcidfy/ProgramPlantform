<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError, request } from '../api'
import { formatTime, problemStatusText } from '../labels'
import type { Page, ProblemAdmin, TeachingClassView } from '../types'

const router = useRouter()
const classes = ref<TeachingClassView[]>([])
const selectedCourseId = ref('')
const problems = ref<ProblemAdmin[]>([])
const total = ref(0)
const loading = ref(true)
const error = ref('')
const notice = ref('')

const courses = computed(() => {
  const seen = new Map<string, string>()
  for (const item of classes.value) {
    if (!seen.has(item.courseId)) {
      seen.set(item.courseId, item.courseName)
    }
  }
  return Array.from(seen.entries()).map(([id, name]) => ({ id, name }))
})

const form = ref({
  title: '',
  statementMd: '',
  language: 'java',
  difficulty: 'easy',
  timeLimitMs: 2000,
  memLimitKb: 262144,
  initialCode: ''
})

async function loadClasses() {
  try {
    classes.value = await request<TeachingClassView[]>('/api/teacher/teaching-classes')
    if (courses.value.length > 0) {
      selectedCourseId.value = courses.value[0].id
      await loadProblems()
    }
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '加载教学班失败'
  } finally {
    loading.value = false
  }
}

async function loadProblems() {
  if (!selectedCourseId.value) {
    return
  }
  try {
    const data = await request<Page<ProblemAdmin>>(
      `/api/teacher/problems?courseId=${selectedCourseId.value}&size=100`
    )
    problems.value = data.items
    total.value = data.total
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '加载题库失败'
  }
}

async function create() {
  error.value = ''
  notice.value = ''
  if (!selectedCourseId.value) {
    error.value = '请先选择课程'
    return
  }
  if (!form.value.title.trim() || !form.value.statementMd.trim()) {
    error.value = '标题与题面都要填'
    return
  }
  try {
    const created = await request<{ id: string }>('/api/teacher/problems', {
      method: 'POST',
      body: JSON.stringify({ ...form.value, courseId: selectedCourseId.value })
    })
    notice.value = '已创建草稿，接下来填用例并发布'
    await router.push(`/teacher/problems/${created.id}`)
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '创建失败'
  }
}

onMounted(loadClasses)
</script>

<template>
  <h1>题库</h1>
  <p class="sub">题目属于课程：同一门课的教师共享题库</p>

  <p v-if="loading" class="meta">加载中…</p>
  <p v-if="error" class="error">{{ error }}</p>
  <p v-if="notice" class="info">{{ notice }}</p>

  <div class="toolbar">
    <label class="inline">
      <span>课程</span>
      <select v-model="selectedCourseId" @change="loadProblems">
        <option v-for="course in courses" :key="course.id" :value="course.id">{{ course.name }}</option>
      </select>
    </label>
    <span class="meta">共 {{ total }} 道题</span>
  </div>

  <table v-if="problems.length > 0">
    <thead>
      <tr>
        <th>标题</th>
        <th>语言</th>
        <th>难度</th>
        <th>状态</th>
        <th>版本</th>
        <th>创建时间</th>
        <th></th>
      </tr>
    </thead>
    <tbody>
      <tr v-for="item in problems" :key="item.id">
        <td>{{ item.title }}</td>
        <td>{{ item.language }}</td>
        <td>{{ item.difficulty }}</td>
        <td><span class="pill" :class="item.status === 'published' ? 'ok' : ''">{{ problemStatusText(item.status) }}</span></td>
        <td>v{{ item.version }}</td>
        <td class="meta">{{ formatTime(item.createdAt) }}</td>
        <td class="right"><router-link :to="`/teacher/problems/${item.id}`">编辑</router-link></td>
      </tr>
    </tbody>
  </table>
  <div v-else-if="!loading" class="empty">该课程还没有题目。</div>

  <h2>新建题目</h2>
  <div class="card">
    <label><span>标题</span><input v-model="form.title" maxlength="200" /></label>
    <div class="form-grid">
      <label>
        <span>语言</span>
        <select v-model="form.language">
          <option value="java">Java</option>
          <option value="python3">Python 3</option>
        </select>
      </label>
      <label>
        <span>难度</span>
        <select v-model="form.difficulty">
          <option value="easy">简单</option>
          <option value="medium">中等</option>
          <option value="hard">困难</option>
        </select>
      </label>
      <label><span>时间限制 (ms)</span><input v-model.number="form.timeLimitMs" type="number" min="100" max="10000" /></label>
      <label><span>内存限制 (KB)</span><input v-model.number="form.memLimitKb" type="number" min="16384" max="1048576" /></label>
    </div>
    <label><span>题面（Markdown，可用「上传图片」插入图片）</span><textarea v-model="form.statementMd" rows="6" /></label>
    <label><span>初始代码模板（可选）</span><textarea v-model="form.initialCode" rows="5" /></label>
    <button class="primary" type="button" @click="create">创建草稿</button>
  </div>
</template>
