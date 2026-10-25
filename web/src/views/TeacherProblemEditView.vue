<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, getAccessToken, request } from '../api'
import { formatTime, problemStatusText } from '../labels'
import { hydrateAuthImages, renderMarkdown } from '../markdown'
import type { ProblemDetail, TestCase } from '../types'

const route = useRoute()
const router = useRouter()
const id = String(route.params.id)

const detail = ref<ProblemDetail | null>(null)
const cases = ref<TestCase[]>([])
const previewHost = ref<HTMLElement | null>(null)
const previewHtml = ref('')
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const notice = ref('')

const form = ref({
  title: '',
  statementMd: '',
  language: 'java',
  difficulty: 'easy',
  timeLimitMs: 2000,
  memLimitKb: 262144,
  initialCode: ''
})

const isDraft = () => detail.value?.problem.status === 'draft'

async function load() {
  loading.value = true
  try {
    const data = await request<ProblemDetail>(`/api/teacher/problems/${id}`)
    detail.value = data
    cases.value = data.testCases.map((c) => ({ ...c }))
    form.value = {
      title: data.problem.title,
      statementMd: data.problem.statementMd,
      language: data.problem.language,
      difficulty: data.problem.difficulty,
      timeLimitMs: data.problem.timeLimitMs,
      memLimitKb: data.problem.memLimitKb,
      initialCode: data.problem.initialCode ?? ''
    }
    await refreshPreview()
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function refreshPreview() {
  previewHtml.value = renderMarkdown(form.value.statementMd || '（暂无题面）')
  await nextTick()
  if (previewHost.value) {
    await hydrateAuthImages(previewHost.value, getAccessToken())
  }
}

async function saveProblem() {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await request<void>(`/api/teacher/problems/${id}`, {
      method: 'PUT',
      body: JSON.stringify({ ...form.value, courseId: detail.value?.problem.courseId })
    })
    notice.value = '题面已保存'
    await load()
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '保存失败'
  } finally {
    busy.value = false
  }
}

function addCase() {
  cases.value.push({ seq: cases.value.length + 1, input: '', expected: '', hidden: false, score: 10 })
}

function removeCase(index: number) {
  cases.value.splice(index, 1)
  cases.value.forEach((c, i) => (c.seq = i + 1))
}

async function saveCases() {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await request<void>(`/api/teacher/problems/${id}/test-cases`, {
      method: 'PUT',
      body: JSON.stringify({
        cases: cases.value.map((c) => ({
          input: c.input,
          expected: c.expected,
          hidden: c.hidden,
          score: c.score
        }))
      })
    })
    notice.value = '用例已保存'
    await load()
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '用例保存失败'
  } finally {
    busy.value = false
  }
}

async function publish() {
  busy.value = true
  error.value = ''
  try {
    await request<void>(`/api/teacher/problems/${id}/publish`, { method: 'POST' })
    notice.value = '已发布'
    await load()
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '发布失败'
  } finally {
    busy.value = false
  }
}

async function newVersion() {
  busy.value = true
  error.value = ''
  try {
    const created = await request<{ id: string }>(`/api/teacher/problems/${id}/new-version`, { method: 'POST' })
    await router.push(`/teacher/problems/${created.id}`)
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '另存新版本失败'
  } finally {
    busy.value = false
  }
}

async function upload(fileEvent: Event) {
  const input = fileEvent.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) {
    return
  }
  const body = new FormData()
  body.append('file', file)
  body.append('problemId', id)
  error.value = ''
  try {
    const result = await request<{ url: string }>('/api/teacher/files', { method: 'POST', body })
    form.value.statementMd = `${form.value.statementMd}\n\n![](${result.url})\n`
    notice.value = '图片已上传，已插入题面末尾，记得保存'
    await refreshPreview()
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '图片上传失败'
  } finally {
    input.value = ''
  }
}

onMounted(load)
</script>

<template>
  <p><router-link to="/teacher/problems">← 返回题库</router-link></p>

  <p v-if="loading" class="meta">加载中…</p>
  <p v-if="error" class="error">{{ error }}</p>
  <p v-if="notice" class="info">{{ notice }}</p>

  <template v-if="detail">
    <h1>
      {{ detail.problem.title }}
      <span class="pill" :class="detail.problem.status === 'published' ? 'ok' : ''">{{ problemStatusText(detail.problem.status) }}</span>
      <span class="meta">v{{ detail.problem.version }}</span>
    </h1>
    <p class="sub">创建于 {{ formatTime(detail.problem.createdAt) }}</p>

    <div class="toolbar">
      <button class="primary small" type="button" :disabled="busy || !isDraft()" @click="saveProblem">保存题面</button>
      <button class="primary small" type="button" :disabled="busy || !isDraft()" @click="publish">发布</button>
      <button class="link" v-if="!isDraft()" type="button" :disabled="busy" @click="newVersion">另存为新版本</button>
      <span class="meta" v-if="!isDraft()">已发布题目不能直接改题面，请用「另存为新版本」</span>
    </div>

    <div class="card">
      <div class="form-grid">
        <label><span>标题</span><input v-model="form.title" :disabled="!isDraft()" /></label>
        <label>
          <span>语言</span>
          <select v-model="form.language" :disabled="!isDraft()">
            <option value="java">Java</option>
            <option value="python3">Python 3</option>
          </select>
        </label>
        <label>
          <span>难度</span>
          <select v-model="form.difficulty" :disabled="!isDraft()">
            <option value="easy">简单</option>
            <option value="medium">中等</option>
            <option value="hard">困难</option>
          </select>
        </label>
        <label><span>时间限制 (ms)</span><input v-model.number="form.timeLimitMs" type="number" :disabled="!isDraft()" /></label>
        <label><span>内存限制 (KB)</span><input v-model.number="form.memLimitKb" type="number" :disabled="!isDraft()" /></label>
      </div>
      <label><span>题面（Markdown）</span><textarea v-model="form.statementMd" rows="10" :disabled="!isDraft()" /></label>
      <label><span>初始代码模板</span><textarea v-model="form.initialCode" rows="8" :disabled="!isDraft()" /></label>
      <label v-if="isDraft()"><span>上传题面图片（PNG/JPEG/GIF/WebP，≤5 MB）</span>
        <input type="file" accept="image/png,image/jpeg,image/gif,image/webp" @change="upload" />
      </label>
      <button class="link" type="button" @click="refreshPreview">刷新预览</button>
      <div class="statement card" ref="previewHost" v-html="previewHtml" />
    </div>

    <h2>测试用例（{{ cases.length }}）</h2>
    <p class="meta">隐藏用例的内容不会下发给学生，判题失败时只显示"第 N 组未通过"</p>
    <table>
      <thead>
        <tr><th>#</th><th>输入</th><th>期望输出</th><th>隐藏</th><th>分值</th><th></th></tr>
      </thead>
      <tbody>
        <tr v-for="(item, index) in cases" :key="index">
          <td>{{ index + 1 }}</td>
          <td><textarea v-model="item.input" rows="3" :disabled="!isDraft()" /></td>
          <td><textarea v-model="item.expected" rows="3" :disabled="!isDraft()" /></td>
          <td><input v-model="item.hidden" type="checkbox" :disabled="!isDraft()" /></td>
          <td><input v-model.number="item.score" type="number" class="narrow" :disabled="!isDraft()" /></td>
          <td class="right"><button class="link" type="button" :disabled="!isDraft()" @click="removeCase(index)">删除</button></td>
        </tr>
      </tbody>
    </table>
    <div class="toolbar">
      <button class="link" type="button" :disabled="!isDraft()" @click="addCase">+ 添加用例</button>
      <button class="primary small" type="button" :disabled="busy || !isDraft()" @click="saveCases">保存用例</button>
    </div>
  </template>
</template>
