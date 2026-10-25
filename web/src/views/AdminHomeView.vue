<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError, request } from '../api'
import type { TeachingClassView } from '../types'

const items = ref<TeachingClassView[]>([])
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    items.value = await request<TeachingClassView[]>('/api/admin/teaching-classes')
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '加载失败，请刷新重试'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <h1>管理端</h1>
  <p class="sub">
    M1 只做只读浏览；建班级 / 建课程 / 建教学班 / 导入名单当前走接口（见 docs/ops/部署手册.md 的验收命令）。
  </p>

  <p v-if="loading" class="meta">加载中…</p>
  <p v-if="error" class="error">{{ error }}</p>

  <template v-if="!loading && items.length > 0">
    <h2>全部教学班</h2>
    <table>
      <thead>
        <tr>
          <th>班级</th>
          <th>课程</th>
          <th>教师</th>
          <th>学期</th>
          <th>已入班</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in items" :key="item.id">
          <td>{{ item.className }}</td>
          <td>{{ item.courseName }}</td>
          <td>{{ item.teacherName }}</td>
          <td>{{ item.term }}</td>
          <td>{{ item.studentCount }}</td>
        </tr>
      </tbody>
    </table>
  </template>
  <p v-else-if="!loading" class="empty">还没有教学班。</p>
</template>
