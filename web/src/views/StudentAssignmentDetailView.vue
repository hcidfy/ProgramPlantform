<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ApiError, request } from '../api'
import { formatTime, problemStatusText } from '../labels'
import type { StudentAssignmentView } from '../types'

const route = useRoute()
const view = ref<StudentAssignmentView | null>(null)
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  try {
    view.value = await request<StudentAssignmentView>(`/api/student/assignments/${route.params.id}`)
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '加载失败'
  } finally {
    loading.value = false
  }
}

function statusOf(problemId: string): string {
  return view.value?.statuses.find((s) => s.problemId === problemId)?.status ?? 'not_started'
}

onMounted(load)
</script>

<template>
  <p><router-link to="/student/assignments">← 返回我的作业</router-link></p>

  <p v-if="loading" class="meta">加载中…</p>
  <p v-if="error" class="error">{{ error }}</p>

  <template v-if="view">
    <h1>{{ view.assignment.title }}</h1>
    <p class="sub">
      截止 {{ formatTime(view.assignment.deadline) }}
      <span v-if="view.expired" class="pill bad">已截止</span>
    </p>
    <p v-if="view.assignment.description" class="meta">{{ view.assignment.description }}</p>

    <table>
      <thead>
        <tr>
          <th>题目</th>
          <th>语言</th>
          <th>限制</th>
          <th>分值</th>
          <th>我的状态</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="problem in view.problems" :key="problem.problemId">
          <td>{{ problem.seq }}. {{ problem.title }}</td>
          <td>{{ problem.language }}</td>
          <td class="meta">{{ problem.timeLimitMs }} ms / {{ Math.round(problem.memLimitKb / 1024) }} MB</td>
          <td>{{ problem.score }}</td>
          <td>
            <span
              class="pill"
              :class="statusOf(problem.problemId) === 'passed' ? 'ok' : statusOf(problem.problemId) === 'submitted' ? 'warn' : ''"
            >{{ problemStatusText(statusOf(problem.problemId)) }}</span>
          </td>
          <td class="right">
            <router-link :to="view.expired ? `/student/problems/${problem.problemId}` : `/student/problems/${problem.problemId}?assignmentId=${view.assignment.id}`">
              {{ view.expired ? '练习（不计入作业）' : '去做题' }}
            </router-link>
          </td>
        </tr>
      </tbody>
    </table>
  </template>
</template>
