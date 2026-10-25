<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError, request } from '../api'
import { session } from '../session'
import type { StudentCourses, TeachingClassView } from '../types'

const data = ref<StudentCourses | null>(null)
const selected = ref<TeachingClassView | null>(null)
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await request<StudentCourses>('/api/student/courses')
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '加载失败，请刷新重试'
  } finally {
    loading.value = false
  }
}

/** 点卡片拿详情：走 /api/student/courses/{id}，顺带验证越权 id → 404 */
async function openDetail(item: TeachingClassView) {
  error.value = ''
  try {
    selected.value = await request<TeachingClassView>(`/api/student/courses/${item.id}`)
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '打开课程详情失败'
  }
}

onMounted(load)
</script>

<template>
  <h1>我的课程</h1>
  <p class="sub">
    {{ session.user?.displayName }} 所在的行政班下，教师开设的全部教学班
  </p>

  <div v-if="data?.needClass" class="warn">
    你还没有被分配到行政班，暂时看不到课程。请联系管理员把你加进班级。
  </div>

  <p v-if="loading" class="meta">加载中…</p>
  <p v-if="error" class="error">{{ error }}</p>

  <template v-if="!loading && data">
    <div v-if="data.items.length === 0" class="empty">
      <p>本班还没有教师开设课程。</p>
      <p class="meta">如果应该有条目，请联系管理员检查你的班级与教学班绑定。</p>
    </div>

    <div
      v-for="item in data.items"
      :key="item.id"
      class="card clickable"
      @click="openDetail(item)"
    >
      <div class="card-title">{{ item.courseName }}（{{ item.courseCode }}）</div>
      <div class="meta">
        {{ item.className }} · 任课教师 {{ item.teacherName }} · 学期 {{ item.term }}
      </div>
    </div>

    <div v-if="selected" class="card">
      <h2>课程详情</h2>
      <div class="card-title">{{ selected.courseName }}（{{ selected.courseCode }}）</div>
      <p class="meta">{{ selected.courseDescription || '暂无课程简介' }}</p>
      <p class="meta">
        班级 {{ selected.className }} · 教师 {{ selected.teacherName }} · 学期 {{ selected.term }} ·
        已入班 {{ selected.studentCount }} 人
      </p>
    </div>
  </template>
</template>
