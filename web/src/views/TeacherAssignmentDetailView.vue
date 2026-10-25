<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ApiError, request } from '../api'
import { assignmentStatusLabel, caseStatusLabel, formatTime, resultLabel, resultClass } from '../labels'
import type { SubmissionView, TeacherAssignmentView, TeacherSubmission } from '../types'

const route = useRoute()
const id = String(route.params.id)

const view = ref<TeacherAssignmentView | null>(null)
const submissions = ref<TeacherSubmission[]>([])
const detail = ref<SubmissionView | null>(null)
const loading = ref(true)
const error = ref('')
const notice = ref('')

async function load() {
  loading.value = true
  try {
    view.value = await request<TeacherAssignmentView>(`/api/teacher/assignments/${id}`)
    submissions.value = await request<TeacherSubmission[]>(
      `/api/teacher/assignments/${id}/submissions`
    )
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function openSubmission(submissionId: string) {
  try {
    detail.value = await request<SubmissionView>(`/api/teacher/submissions/${submissionId}`)
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '打开提交失败'
  }
}

async function rejudge(submissionId: string) {
  error.value = ''
  notice.value = ''
  try {
    const result = await request<{ id: string }>(`/api/teacher/submissions/${submissionId}/rejudge`, { method: 'POST' })
    notice.value = `已重新排队，新提交 ${result.id.slice(0, 8)}…`
    await load()
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '重判失败'
  }
}

onMounted(load)
</script>

<template>
  <p><router-link to="/teacher/assignments">← 返回作业列表</router-link></p>

  <p v-if="loading" class="meta">加载中…</p>
  <p v-if="error" class="error">{{ error }}</p>
  <p v-if="notice" class="info">{{ notice }}</p>

  <template v-if="view">
    <h1>
      {{ view.assignment.title }}
      <span class="pill" :class="view.assignment.status === 'published' ? 'ok' : ''">
        {{ assignmentStatusLabel(view.assignment.status) }}
      </span>
    </h1>
    <p class="sub">截止 {{ formatTime(view.assignment.deadline) }}</p>
    <p v-if="view.assignment.description" class="meta">{{ view.assignment.description }}</p>

    <div class="stat-row">
      <div class="stat"><b>{{ view.progress.expectedCount }}</b><span>应交人数</span></div>
      <div class="stat"><b>{{ view.progress.submittedCount }}</b><span>已提交人数</span></div>
      <div class="stat"><b>{{ view.progress.passedCount }}</b><span>通过人数</span></div>
      <div class="stat"><b>{{ view.progress.passRate }}%</b><span>通过率(人)</span></div>
      <div class="stat"><b>{{ view.progress.passedSubmissionRate }}%</b><span>通过提交数÷总提交数</span></div>
    </div>

    <h2>题目</h2>
    <table>
      <thead><tr><th>#</th><th>题目</th><th>语言</th><th>分值</th><th>题号</th></tr></thead>
      <tbody>
        <tr v-for="item in view.problems" :key="item.problemId">
          <td>{{ item.seq }}</td>
          <td>{{ item.title }}</td>
          <td>{{ item.language }}</td>
          <td>{{ item.score }}</td>
          <td class="meta">{{ item.problemId.slice(0, 8) }}…</td>
        </tr>
      </tbody>
    </table>

    <h2>学生提交（{{ submissions.length }}）</h2>
    <table v-if="submissions.length > 0">
      <thead>
        <tr><th>学生</th><th>题目</th><th>结果</th><th>通过</th><th>时间</th><th></th></tr>
      </thead>
      <tbody>
        <tr v-for="item in submissions" :key="item.id">
          <td>{{ item.studentName }}<span class="meta"> · {{ item.studentEmail }}</span></td>
          <td>{{ item.problemTitle }}</td>
          <td><span :class="resultClass(item.result)">{{ item.result ? resultLabel(item.result) : item.status }}</span></td>
          <td>{{ item.passedCases }}/{{ item.totalCases }}</td>
          <td class="meta">{{ formatTime(item.createdAt) }}</td>
          <td class="right">
            <button class="link" type="button" @click="openSubmission(item.id)">看代码</button>
            <button class="link" type="button" @click="rejudge(item.id)">重判</button>
          </td>
        </tr>
      </tbody>
    </table>
    <div v-else class="empty">还没有学生提交。</div>

    <div v-if="detail" class="card">
      <h2>提交详情（教师视角，含隐藏用例）</h2>
      <p class="meta">
        {{ formatTime(detail.createdAt) }} · {{ detail.language }} ·
        <span :class="resultClass(detail.result)">{{ detail.result ? resultLabel(detail.result) : detail.status }}</span>
        · 通过 {{ detail.passedCases }}/{{ detail.totalCases }} · {{ detail.score }} 分
      </p>
      <pre v-if="detail.compileOutput" class="compile-error">{{ detail.compileOutput }}</pre>
      <table v-if="detail.cases.length > 0" class="nested">
        <thead><tr><th>用例</th><th>结果</th><th>耗时</th><th>期望</th><th>实际</th></tr></thead>
        <tbody>
          <tr v-for="item in detail.cases" :key="item.seq">
            <td>第 {{ item.seq }} 组{{ item.hidden ? '（隐藏）' : '' }}</td>
            <td>{{ caseStatusLabel(item.status) }}</td>
            <td>{{ item.timeMs }} ms</td>
            <td><pre class="tiny">{{ item.expected }}</pre></td>
            <td><pre class="tiny">{{ item.actual }}</pre></td>
          </tr>
        </tbody>
      </table>
      <h3>代码</h3>
      <pre>{{ detail.sourceCode }}</pre>
    </div>
  </template>
</template>
