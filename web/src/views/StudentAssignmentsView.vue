<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError, request } from '../api'
import { formatTime, problemStatusText } from '../labels'
import { session } from '../session'
import type { StudentAssignmentView } from '../types'

const items = ref<StudentAssignmentView[]>([])
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  try {
    items.value = await request<StudentAssignmentView[]>('/api/student/assignments')
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '加载失败'
  } finally {
    loading.value = false
  }
}

function statusOf(view: StudentAssignmentView, problemId: string): string {
  return view.statuses.find((s) => s.problemId === problemId)?.status ?? 'not_started'
}

onMounted(load)
</script>

<template>
  <h1>我的作业</h1>
  <p class="sub">{{ session.user?.displayName }} 所在班级的已发布作业</p>

  <p v-if="loading" class="meta">加载中…</p>
  <p v-if="error" class="error">{{ error }}</p>

  <template v-if="!loading">
    <div v-if="items.length === 0" class="empty">还没有布置作业。</div>

    <div v-for="view in items" :key="view.assignment.id" class="card">
      <div class="card-title">
        {{ view.assignment.title }}
        <span v-if="view.expired" class="pill bad">已截止</span>
      </div>
      <div class="meta">
        截止 {{ formatTime(view.assignment.deadline) }}
        <span v-if="view.assignment.description"> · {{ view.assignment.description }}</span>
      </div>

      <table class="nested">
        <tbody>
          <tr v-for="problem in view.problems" :key="problem.problemId">
            <td>{{ problem.seq }}. {{ problem.title }}</td>
            <td class="meta">{{ problem.language }} · {{ problem.timeLimitMs }} ms · {{ problem.score }} 分</td>
            <td>
              <span
                class="pill"
                :class="statusOf(view, problem.problemId) === 'passed' ? 'ok' : statusOf(view, problem.problemId) === 'submitted' ? 'warn' : ''"
              >
                {{ problemStatusText(statusOf(view, problem.problemId)) }}
              </span>
            </td>
            <td class="right">
              <router-link :to="`/student/assignments/${view.assignment.id}`">查看作业</router-link>
              ·
              <router-link :to="`/student/problems/${problem.problemId}?assignmentId=${view.assignment.id}`">
                去做题
              </router-link>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </template>
</template>
