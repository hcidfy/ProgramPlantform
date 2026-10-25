<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError, request } from '../api'
import { caseStatusLabel, formatTime, resultLabel, resultClass, submissionStatusLabel } from '../labels'
import type { Page, SubmissionSummary, SubmissionView } from '../types'

const items = ref<SubmissionSummary[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(true)
const error = ref('')
const detail = ref<SubmissionView | null>(null)

async function load() {
  loading.value = true
  try {
    const data = await request<Page<SubmissionSummary>>(`/api/student/submissions?page=${page.value}&size=${size}`)
    items.value = data.items
    total.value = data.total
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function open(id: string) {
  try {
    detail.value = await request<SubmissionView>(`/api/student/submissions/${id}`)
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '打开提交失败'
  }
}

function prev() {
  if (page.value > 1) {
    page.value--
    load()
  }
}

function next() {
  if (page.value * size < total.value) {
    page.value++
    load()
  }
}

onMounted(load)
</script>

<template>
  <h1>我的提交</h1>
  <p class="sub">共 {{ total }} 条</p>

  <p v-if="loading" class="meta">加载中…</p>
  <p v-if="error" class="error">{{ error }}</p>

  <table v-if="items.length > 0">
    <thead>
      <tr>
        <th>时间</th>
        <th>语言</th>
        <th>状态</th>
        <th>通过</th>
        <th>得分</th>
        <th></th>
      </tr>
    </thead>
    <tbody>
      <tr v-for="item in items" :key="item.id">
        <td>{{ formatTime(item.createdAt) }}</td>
        <td>{{ item.language }}</td>
        <td>
          <span :class="resultClass(item.result)">{{ item.result ? resultLabel(item.result) : submissionStatusLabel(item.status) }}</span>
        </td>
        <td>{{ item.passedCases }}/{{ item.totalCases }}</td>
        <td>{{ item.score }}</td>
        <td class="right"><button class="link" type="button" @click="open(item.id)">查看</button></td>
      </tr>
    </tbody>
  </table>
  <div v-else-if="!loading" class="empty">还没有提交记录。</div>

  <div class="pager">
    <button class="link" type="button" :disabled="page <= 1" @click="prev">上一页</button>
    <span class="meta">第 {{ page }} 页</span>
    <button class="link" type="button" :disabled="page * size >= total" @click="next">下一页</button>
  </div>

  <div v-if="detail" class="card">
    <h2>提交详情</h2>
    <p class="meta">
      {{ formatTime(detail.createdAt) }} · {{ detail.language }} · 通过 {{ detail.passedCases }}/{{ detail.totalCases }}
      · {{ detail.score }} 分
    </p>
    <pre v-if="detail.compileOutput" class="compile-error">{{ detail.compileOutput }}</pre>
    <div v-if="detail.firstFailedCase">
      <h3>首个未通过：第 {{ detail.firstFailedCase.seq }} 组</h3>
      <template v-if="!detail.firstFailedCase.hidden">
        <div class="kv"><b>输入</b><pre>{{ detail.firstFailedCase.input }}</pre></div>
        <div class="kv"><b>期望</b><pre>{{ detail.firstFailedCase.expected }}</pre></div>
        <div class="kv"><b>实际</b><pre>{{ detail.firstFailedCase.actual }}</pre></div>
      </template>
      <p v-else class="meta">隐藏用例，不显示内容。</p>
    </div>
    <h3>代码</h3>
    <pre>{{ detail.sourceCode }}</pre>
    <table v-if="detail.cases.length > 0" class="nested">
      <thead>
        <tr><th>用例</th><th>结果</th><th>耗时</th></tr>
      </thead>
      <tbody>
        <tr v-for="item in detail.cases" :key="item.seq">
          <td>第 {{ item.seq }} 组</td>
          <td>{{ caseStatusLabel(item.status) }}</td>
          <td>{{ item.timeMs }} ms</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
