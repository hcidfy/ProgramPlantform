<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import CodeEditor from '../components/CodeEditor.vue'
import { ApiError, getAccessToken, request } from '../api'
import { caseStatusLabel, formatTime, resultLabel, resultClass, submissionStatusLabel } from '../labels'
import { hydrateAuthImages, renderMarkdown } from '../markdown'
import type { StudentProblemView, SubmissionView } from '../types'

const route = useRoute()
const problemId = String(route.params.id)
const assignmentId = typeof route.query.assignmentId === 'string' ? route.query.assignmentId : ''

const problem = ref<StudentProblemView | null>(null)
const statementHtml = ref('')
const statementHost = ref<HTMLElement | null>(null)
const code = ref('')
const submission = ref<SubmissionView | null>(null)
const busy = ref(false)
const error = ref('')
const notice = ref('')
let pollTimer: number | null = null

const languageForEditor = (language?: string) => (language === 'python3' ? 'python' : 'java')

async function loadProblem() {
  try {
    const data = await request<StudentProblemView>(`/api/student/problems/${problemId}`)
    problem.value = data
    code.value = data.initialCode || defaultTemplate(data.language)
    statementHtml.value = renderMarkdown(data.statementMd)
    await nextTick()
    if (statementHost.value) {
      await hydrateAuthImages(statementHost.value, getAccessToken())
    }
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '题目加载失败'
  }
}

function defaultTemplate(language: string): string {
  if (language === 'python3') {
    return 'a, b = map(int, input().split())\nprint(a + b)\n'
  }
  return `import java.util.Scanner;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner sc = new Scanner(System.in);\n        // TODO\n    }\n}\n`
}

async function submit() {
  if (!problem.value) {
    return
  }
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const created = await request<{ id: string }>('/api/student/submissions', {
      method: 'POST',
      body: JSON.stringify({
        problemId: problem.value.id,
        assignmentId: assignmentId || null,
        sourceCode: code.value
      })
    })
    notice.value = '已提交，正在判题…'
    submission.value = await request<SubmissionView>(`/api/student/submissions/${created.id}`)
    startPolling(created.id)
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '提交失败'
  } finally {
    busy.value = false
  }
}

function startPolling(id: string) {
  stopPolling()
  pollTimer = window.setInterval(async () => {
    try {
      const view = await request<SubmissionView>(`/api/student/submissions/${id}`)
      submission.value = view
      if (view.status === 'done' || view.status === 'judge_error') {
        stopPolling()
        notice.value = ''
      }
    } catch {
      stopPolling()
    }
  }, 800)
}

function stopPolling() {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

watch(() => problem.value?.language, () => {
  /* 语言由题目决定，客户端不可改 */
})

onMounted(loadProblem)
onBeforeUnmount(stopPolling)
</script>

<template>
  <p><router-link :to="assignmentId ? `/student/assignments/${assignmentId}` : '/student/assignments'">← 返回作业</router-link></p>

  <p v-if="error" class="error">{{ error }}</p>
  <p v-if="!problem && !error" class="meta">加载中…</p>

  <template v-if="problem">
    <h1>{{ problem.title }}</h1>
    <p class="sub">
      {{ problem.language }} · 时间限制 {{ problem.timeLimitMs }} ms · 内存限制
      {{ Math.round(problem.memLimitKb / 1024) }} MB
    </p>

    <div class="card statement" ref="statementHost" v-html="statementHtml" />

    <div class="toolbar">
      <h2>我的代码</h2>
      <button class="primary small" type="button" :disabled="busy" @click="submit">
        {{ busy ? '提交中…' : '提交判题' }}
      </button>
    </div>
    <CodeEditor v-model="code" :language="languageForEditor(problem.language)" />

    <p v-if="notice" class="info">{{ notice }}</p>

    <div v-if="submission" class="card">
      <h2>判题结果</h2>
      <p>
        <span :class="resultClass(submission.result)">{{ submissionStatusLabel(submission.status) }}</span>
        <span v-if="submission.result" :class="resultClass(submission.result)">{{ resultLabel(submission.result) }}</span>
        <span class="meta">
          通过 {{ submission.passedCases }}/{{ submission.totalCases }} · {{ submission.score }} 分
          <span v-if="submission.status === 'queued'"> · 排在第 {{ submission.queuePosition }} 位</span>
          · {{ formatTime(submission.finishedAt || submission.createdAt) }}
        </span>
      </p>

      <pre v-if="submission.compileOutput" class="compile-error">{{ submission.compileOutput }}</pre>

      <div v-if="submission.firstFailedCase" class="fail-case">
        <h3>
          首个未通过用例：第 {{ submission.firstFailedCase.seq }} 组
          <span class="pill warn">{{ caseStatusLabel(submission.firstFailedCase.status) }}</span>
        </h3>
        <template v-if="!submission.firstFailedCase.hidden">
          <div class="kv"><b>输入</b><pre>{{ submission.firstFailedCase.input }}</pre></div>
          <div class="kv"><b>期望输出</b><pre>{{ submission.firstFailedCase.expected }}</pre></div>
          <div class="kv"><b>实际输出</b><pre>{{ submission.firstFailedCase.actual }}</pre></div>
        </template>
        <p v-else class="meta">隐藏用例，不显示输入与期望输出。</p>
      </div>

      <table v-if="submission.cases.length > 0" class="nested">
        <thead>
          <tr><th>用例</th><th>结果</th><th>耗时</th><th>可见性</th></tr>
        </thead>
        <tbody>
          <tr v-for="item in submission.cases" :key="item.seq">
            <td>第 {{ item.seq }} 组</td>
            <td>{{ caseStatusLabel(item.status) }}</td>
            <td>{{ item.timeMs }} ms</td>
            <td>{{ item.hidden ? '隐藏' : '公开' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </template>
</template>
