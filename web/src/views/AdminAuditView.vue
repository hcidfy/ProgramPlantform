<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ApiError, request } from '../api'
import { formatTime } from '../labels'
import type { Page } from '../types'

interface AuditRow {
  id: number
  actorId: string | null
  actorName: string | null
  actorEmail: string | null
  action: string
  resource: string | null
  detail: string | null
  ip: string | null
  createdAt: string
}

const rows = ref<AuditRow[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const queryName = ref('')
const queryAction = ref('')
const from = ref('')
const to = ref('')
const loading = ref(false)
const error = ref('')
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size)))

function toIso(value: string): string | null {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.valueOf()) ? null : date.toISOString()
}

async function search(nextPage = 1) {
  loading.value = true
  error.value = ''
  page.value = nextPage
  const params = new URLSearchParams({ page: String(page.value), size: String(size) })
  if (queryName.value.trim()) params.set('q', queryName.value.trim())
  if (queryAction.value.trim()) params.set('action', queryAction.value.trim())
  const fromIso = toIso(from.value)
  const toIsoValue = toIso(to.value)
  if (fromIso) params.set('from', fromIso)
  if (toIsoValue) params.set('to', toIsoValue)
  try {
    const result = await request<Page<AuditRow>>(`/api/admin/audit-logs?${params.toString()}`)
    rows.value = result.items
    total.value = result.total
    page.value = result.page
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '查询操作日志失败'
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

onMounted(() => search())
</script>

<template>
  <h1>操作日志</h1>
  <p class="sub">按操作者、动作和时间范围查询只追加审计记录。正文和完整代码不会在此展示。</p>

  <section class="card">
    <form class="form-grid" @submit.prevent="search(1)">
      <label><span>操作者姓名或邮箱</span><input v-model="queryName" placeholder="输入姓名或邮箱片段" /></label>
      <label><span>动作（可输入 teacher.view_code 等）</span><input v-model="queryAction" placeholder="teacher.view_code" /></label>
      <label><span>开始时间（含）</span><input v-model="from" type="datetime-local" /></label>
      <label><span>结束时间（不含）</span><input v-model="to" type="datetime-local" /></label>
      <button class="primary" type="submit" :disabled="loading">{{ loading ? '查询中…' : '查询' }}</button>
    </form>
    <p v-if="error" class="error">{{ error }}</p>
    <p class="meta">共 {{ total.toLocaleString('zh-CN') }} 条</p>
  </section>

  <section class="card">
    <table v-if="rows.length">
      <thead><tr><th>时间</th><th>操作者</th><th>动作</th><th>资源</th><th>IP</th><th>详情</th></tr></thead>
      <tbody>
        <tr v-for="row in rows" :key="row.id">
          <td>{{ formatTime(row.createdAt) }}</td>
          <td>{{ row.actorName || '匿名/已删除用户' }}<br /><span class="meta">{{ row.actorEmail || row.actorId || '—' }}</span></td>
          <td><code>{{ row.action }}</code></td>
          <td>{{ row.resource || '—' }}</td>
          <td>{{ row.ip || '—' }}</td>
          <td><details v-if="row.detail"><summary>查看</summary><pre>{{ row.detail }}</pre></details><span v-else>—</span></td>
        </tr>
      </tbody>
    </table>
    <p v-else-if="!loading" class="empty">没有符合条件的日志。</p>
    <div v-if="totalPages > 1" class="toolbar">
      <button class="link" type="button" :disabled="page <= 1 || loading" @click="search(page - 1)">上一页</button>
      <span class="meta">第 {{ page }} / {{ totalPages }} 页</span>
      <button class="link" type="button" :disabled="page >= totalPages || loading" @click="search(page + 1)">下一页</button>
    </div>
  </section>
</template>
