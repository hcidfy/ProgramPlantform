<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError, request } from '../api'
import { session } from '../session'
import type { TeacherClassDetail, TeachingClassView } from '../types'

const items = ref<TeachingClassView[]>([])
const detail = ref<TeacherClassDetail | null>(null)
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    items.value = await request<TeachingClassView[]>('/api/teacher/teaching-classes')
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '加载失败，请刷新重试'
  } finally {
    loading.value = false
  }
}

/** 换一个 id 试探别人的教学班 → 404（PRD §13.3 不泄露存在性） */
async function openDetail(item: TeachingClassView) {
  error.value = ''
  try {
    detail.value = await request<TeacherClassDetail>(`/api/teacher/teaching-classes/${item.id}`)
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '打开教学班详情失败'
  }
}

onMounted(load)
</script>

<template>
  <h1>我的教学班</h1>
  <p class="sub">{{ session.user?.displayName }} 任课的教学班（别人的教学班看不到）</p>

  <p v-if="loading" class="meta">加载中…</p>
  <p v-if="error" class="error">{{ error }}</p>

  <template v-if="!loading">
    <div v-if="items.length === 0" class="empty">
      <p>你还没有教学班。</p>
      <p class="meta">由管理员在「班级 × 课程 × 教师 × 学期」里创建后即可见。</p>
    </div>

    <div v-for="item in items" :key="item.id" class="card clickable" @click="openDetail(item)">
      <div class="card-title">{{ item.className }} · {{ item.courseName }}</div>
      <div class="meta">学期 {{ item.term }} · 已入班 {{ item.studentCount }} 人</div>
    </div>

    <div v-if="detail" class="card">
      <h2>{{ detail.teachingClass.className }} · {{ detail.teachingClass.courseName }}</h2>
      <p class="meta">
        学期 {{ detail.teachingClass.term }} · 学生 {{ detail.students.length }} 人
      </p>
      <table v-if="detail.students.length > 0">
        <thead>
          <tr>
            <th>姓名</th>
            <th>邮箱</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="student in detail.students" :key="student.id">
            <td>{{ student.displayName }}</td>
            <td>{{ student.email }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="meta">该班级还没有学生入班。</p>
    </div>
  </template>
</template>
