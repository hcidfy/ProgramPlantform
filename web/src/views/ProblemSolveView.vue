<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import AiAnswer from '../components/AiAnswer.vue'
import CodeEditor from '../components/CodeEditor.vue'
import { ApiError, getAccessToken, request } from '../api'
import { caseStatusLabel, formatTime, resultLabel, resultClass, submissionStatusLabel } from '../labels'
import { hydrateAuthImages, renderMarkdown } from '../markdown'
import type { StudentProblemView, SubmissionView } from '../types'
import type { AiAnswer as AiAnswerData, AiStatus } from '../ai-types'

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

const aiStatus = ref<AiStatus | null>(null)
const aiAnswer = ref<AiAnswerData | null>(null)
const aiBusy = ref(false)
const aiError = ref('')

let pollTimer: number | null = null

const languageForEditor = (language?: string) => (language === 'python3' ? 'python' : 'java')

const canHint = (level: number) =>
  !!aiStatus.value && !aiStatus.value.passed && level <= aiStatus.value.hintLevelsUnlocked + 1

const aiRemainingText = computed(() => {
  if (!aiStatus.value) {
    return ''
  }
  return `本题 ${aiStatus.value.perProblemCalls}/${aiStatus.value.perProblemCallLimit} 次 · 今日 ${aiStatus.value.dailyTokens}/${aiStatus.value.dailyTokenLimit} tokens`
})

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

async function loadAiStatus() {
  try {
    aiStatus.value = await request<AiStatus>(`/api/student/problems/${problemId}/ai/status`)
  } catch {
    aiStatus.value = null
  }
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
        await loadAiStatus()
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

async function callAi(task: 'explain' | 'hint' | 'analyze', extra: Record<string, unknown> = {}, forceRefresh = false) {
  aiBusy.value = true
  aiError.value = ''
  try {
    const body = {
      submissionId: submission.value?.id ?? null,
      forceRefresh,
      ...extra
    }
    aiAnswer.value = await request<AiAnswerData>(`/api/student/problems/${problemId}/ai/${task}`, {
      method: 'POST',
      body: JSON.stringify(body)
    })
    await loadAiStatus()
  } catch (err) {
    aiError.value = err instanceof ApiError ? err.message : 'AI 调用失败'
  } finally {
    aiBusy.value = false
  }
}

async function refreshAiAnswer() {
  if (!aiAnswer.value) {
    return
  }
  const level = aiAnswer.value.task === 'hint' ? Number(aiAnswer.value.payload.level ?? 1) : undefined
  await callAi(aiAnswer.value.task as 'explain' | 'hint' | 'analyze', level ? { level } : {}, true)
}

onMounted(async () => {
  await loadProblem()
  await loadAiStatus()
})
onBeforeUnmount(stopPolling)
</script>

<template>
  <p>
    <router-link :to="assignmentId ? `/student/assignments/${assignmentId}` : '/student/assignments'">← 返回作业</router-link>
  </p>

  <p v-if="error" class="error">{{ error }}</p>
  <p v-if="!problem && !error" class="meta">加载中…</p>

  <template v-if="problem">
    <h1>{{ problem.title }}</h1>
    <p class="sub">
      {{ problem.language }} · 时间限制 {{ problem.timeLimitMs }} ms · 内存限制
      {{ Math.round(problem.memLimitKb / 1024) }} MB
    </p>

    <div ref="statementHost" class="card statement" v-html="statementHtml" />

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

    <!-- 时间闸门：提交前这里不渲染任何 AI 入口（FR-AI-00） -->
    <div v-if="aiStatus" class="card ai-panel">
      <h2>和 AI 讨论这道题</h2>

      <p v-if="!aiStatus.hasSubmission" class="meta">
        提交后才能与 AI 讨论你的代码。先交一次，回来看看哪里可以改。
      </p>

      <template v-else>
        <p v-if="aiStatus.reason" class="pill warn">{{ aiStatus.reason }}</p>
        <div class="ai-actions">
          <button class="primary small" type="button" :disabled="aiBusy" @click="callAi('explain')">
            问 AI 为什么错
          </button>
          <button class="primary small" type="button" :disabled="aiBusy" @click="callAi('analyze')">
            分析正确结果
          </button>
          <button
            v-for="level in 3"
            :key="level"
            class="link"
            type="button"
            :disabled="aiBusy || !canHint(level)"
            @click="callAi('hint', { level })"
          >
            思路 L{{ level }}{{ aiStatus.hintLevelsUnlocked >= level ? '（已解锁）' : '' }}
          </button>
          <span class="meta">{{ aiRemainingText }}</span>
        </div>
        <p v-if="aiStatus.passed" class="meta">已通过：思路提示已关闭，仍可问「为什么」或看正确结果分析。</p>
        <p v-if="aiError" class="error">{{ aiError }}</p>

        <div v-if="aiAnswer">
          <AiAnswer :answer="aiAnswer" />
          <button class="link" type="button" :disabled="aiBusy" @click="refreshAiAnswer">强制刷新一次</button>
        </div>
      </template>
    </div>
  </template>
</template>
