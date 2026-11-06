<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ApiError, request } from '../api'
import type { Page, UserView } from '../types'

type Scope = 'global' | 'teaching_class' | 'student'

interface Quota {
  scope: Scope
  scopeId: string | null
  targetName: string
  dailyTokens: number
  perProblemCalls: number
}

interface QuotasResponse {
  configuredDefaults: Quota
  overrides: Quota[]
}

interface UsageTotals {
  calls: number
  promptTokens: number
  completionTokens: number
  totalTokens: number
}

interface UsageDashboard {
  today: UsageTotals
  month: UsageTotals
  byTaskToday: Array<{ task: string; calls: number; promptTokens: number; completionTokens: number }>
  byCourseMonth: Array<{ courseId: string; courseCode: string; courseName: string; calls: number; promptTokens: number; completionTokens: number }>
  topStudentsMonth: Array<{ userId: string; displayName: string; email: string; calls: number; promptTokens: number; completionTokens: number }>
  costEstimateAvailable: boolean
  estimatedCostCents: number | null
  inputPriceCentsPerMillion: number
  outputPriceCentsPerMillion: number
}

interface TeachingClass {
  id: string
  className: string
  courseName: string
  teacherName: string
  term: string
}

const loading = ref(true)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const quotas = ref<QuotasResponse | null>(null)
const usage = ref<UsageDashboard | null>(null)
const classes = ref<TeachingClass[]>([])
const students = ref<UserView[]>([])
const form = reactive({ scope: 'global' as Scope, scopeId: '', dailyTokens: 30000, perProblemCalls: 10 })

const scopedOverrides = computed(() => (quotas.value?.overrides ?? []).filter((item) => item.scope !== 'global'))
function targetName(item: Quota): string {
  return item.targetName || '未命名目标'
}
function taskName(task: string): string {
  return ({ explain: '解释错误', hint: '思路提示', analyze: '分析结果', chat: '课程问答' } as Record<string, string>)[task] ?? task
}
const targets = computed(() => form.scope === 'teaching_class'
  ? classes.value.map((item) => ({ id: item.id, name: `${item.className} · ${item.courseName} · ${item.term}` }))
  : form.scope === 'student'
    ? students.value.map((item) => ({ id: item.id, name: `${item.displayName} · ${item.email}` }))
    : [])

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [quotaData, usageData, classData, firstStudents] = await Promise.all([
      request<QuotasResponse>('/api/admin/ai/quotas'),
      request<UsageDashboard>('/api/admin/ai/usage'),
      request<TeachingClass[]>('/api/admin/teaching-classes'),
      request<Page<UserView>>('/api/admin/users?role=student&page=1&size=100')
    ])
    quotas.value = quotaData
    usage.value = usageData
    classes.value = classData
    students.value = firstStudents.items
    for (let page = 2; students.value.length < firstStudents.total; page += 1) {
      const next = await request<Page<UserView>>(`/api/admin/users?role=student&page=${page}&size=100`)
      if (!next.items.length) break
      students.value = [...students.value, ...next.items]
    }
    form.dailyTokens = quotaData.configuredDefaults.dailyTokens
    form.perProblemCalls = quotaData.configuredDefaults.perProblemCalls
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '加载配额和用量失败'
  } finally {
    loading.value = false
  }
}

function chooseOverride(item: Quota) {
  form.scope = item.scope
  form.scopeId = item.scopeId ?? ''
  form.dailyTokens = item.dailyTokens
  form.perProblemCalls = item.perProblemCalls
}

async function saveQuota() {
  if (saving.value) return
  if (form.scope !== 'global' && !form.scopeId) {
    error.value = '请选择要配置的教学班或学生。'
    return
  }
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    await request('/api/admin/ai/quotas', {
      method: 'PUT',
      body: JSON.stringify({
        scope: form.scope,
        scopeId: form.scope === 'global' ? null : form.scopeId,
        dailyTokens: form.dailyTokens,
        perProblemCalls: form.perProblemCalls
      })
    })
    notice.value = '配额已保存，新值对后续 AI 调用生效。'
    await load()
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '保存配额失败'
  } finally {
    saving.value = false
  }
}

function money(cents: number | null): string {
  return cents === null ? '未配置单价' : `¥${(cents / 100).toFixed(2)}`
}

function tokens(prompt: number, completion: number): string {
  return (prompt + completion).toLocaleString('zh-CN')
}

onMounted(load)
</script>

<template>
  <h1>AI 配额与用量</h1>
  <p class="sub">今日用量是平台所有账号当天已完成 AI 调用的合计，按数据库 current_date 切日；本月从本月 1 日统计到今天。页面打开、登录和缓存命中不会扣 token。费用按服务器配置的输入/输出单价估算，不是供应商账单。本地 AI_PROVIDER=stub 时 token 是按字符数估出的开发用近似值，不代表真实模型 token 或费用。</p>
  <p v-if="loading" class="meta">加载中…</p>
  <p v-if="error" class="error">{{ error }}</p>
  <p v-if="notice" class="info">{{ notice }}</p>

  <template v-if="!loading && usage && quotas">
    <section class="card">
      <h2>今日用量</h2>
      <p>调用 {{ usage.today.calls.toLocaleString('zh-CN') }} 次 · {{ usage.today.totalTokens.toLocaleString('zh-CN') }} tokens</p>
      <p class="meta">输入 {{ usage.today.promptTokens.toLocaleString('zh-CN') }} · 输出 {{ usage.today.completionTokens.toLocaleString('zh-CN') }}。只统计已经由模型完成并记账的调用。</p>
    </section>
    <section class="card">
      <h2>本月用量与估算费用</h2>
      <p>调用 {{ usage.month.calls.toLocaleString('zh-CN') }} 次 · {{ usage.month.totalTokens.toLocaleString('zh-CN') }} tokens · {{ money(usage.estimatedCostCents) }}</p>
      <p class="meta">
        输入费率 {{ usage.inputPriceCentsPerMillion }} 分/百万 tokens；输出费率 {{ usage.outputPriceCentsPerMillion }} 分/百万 tokens。
        请在服务器 .env 按当前模型价格设置 APP_AI_INPUT_PRICE_CENTS_PER_MILLION 和 APP_AI_OUTPUT_PRICE_CENTS_PER_MILLION。
      </p>
    </section>

    <section class="card">
      <h2>配置配额</h2>
      <p class="meta">每日 token 上限按单个学生的输入 + 输出总量计算。学生专项覆盖优先于教学班覆盖，再其次是全局默认。单题调用上限只限制做题页的解释、思路和分析；课程问答只受每日 token 限额控制。达到上限后拒绝后续新调用；已开始的模型回答不会因日额度中途截断。</p>
      <p class="meta">30,000 是每日累计额度，不是单次回答长度；当前默认单次输出上限为 1,000 tokens，通常可支持多轮短问答。每轮还会计入提示词、代码/判题上下文和回答本身的 token，所以可用轮数会少于 30 次。</p>
      <label>
        <span>范围</span>
        <select v-model="form.scope" @change="form.scopeId = ''; form.dailyTokens = quotas.configuredDefaults.dailyTokens; form.perProblemCalls = quotas.configuredDefaults.perProblemCalls">
          <option value="global">全局默认</option>
          <option value="teaching_class">教学班覆盖</option>
          <option value="student">学生覆盖</option>
        </select>
      </label>
      <label v-if="form.scope !== 'global'">
        <span>目标</span>
        <select v-model="form.scopeId" required>
          <option value="" disabled>请选择</option>
          <option v-for="target in targets" :key="target.id" :value="target.id">{{ target.name }}</option>
        </select>
      </label>
      <div class="form-grid">
        <label><span>每日 token 上限</span><input v-model.number="form.dailyTokens" type="number" min="1" required /></label>
        <label><span>单题 AI 调用上限</span><input v-model.number="form.perProblemCalls" type="number" min="1" required /></label>
      </div>
      <button class="primary" type="button" :disabled="saving" @click="saveQuota">{{ saving ? '保存中…' : '保存配额' }}</button>

      <h3>已配置的专项配额</h3>
      <table v-if="scopedOverrides.length">
        <thead><tr><th>范围</th><th>对象</th><th>日 token</th><th>单题次数</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="item in scopedOverrides" :key="`${item.scope}-${item.scopeId}`">
            <td>{{ item.scope === 'student' ? '学生' : '教学班' }}</td>
            <td>{{ targetName(item) }}</td><td>{{ item.dailyTokens }}</td><td>{{ item.perProblemCalls }}</td>
            <td><button class="link" type="button" @click="chooseOverride(item)">编辑</button></td>
          </tr>
        </tbody>
      </table>
      <p v-else class="meta">暂无教学班或学生覆盖；未配置时使用全局默认。</p>
    </section>

    <section class="card">
      <h2>今日按任务聚合</h2>
      <p class="meta">将今日已计费的 AI 调用按功能分类；显示调用数和模型实际返回的输入、输出 tokens。</p>
      <table v-if="usage.byTaskToday.length"><thead><tr><th>任务</th><th>调用</th><th>输入 tokens</th><th>输出 tokens</th></tr></thead>
        <tbody><tr v-for="item in usage.byTaskToday" :key="item.task"><td>{{ taskName(item.task) }}</td><td>{{ item.calls }}</td><td>{{ item.promptTokens }}</td><td>{{ item.completionTokens }}</td></tr></tbody>
      </table>
      <p v-else class="meta">今日暂无调用。</p>
    </section>

    <section class="card">
      <h2>本月按课程聚合</h2>
      <p class="meta">把本月发生在课程上下文中的已计费调用，按课程汇总。未关联课程的调用不出现在此表。</p>
      <table v-if="usage.byCourseMonth.length"><thead><tr><th>课程</th><th>调用</th><th>Tokens</th></tr></thead>
        <tbody><tr v-for="item in usage.byCourseMonth" :key="item.courseId"><td>{{ item.courseCode }} · {{ item.courseName }}</td><td>{{ item.calls }}</td><td>{{ tokens(item.promptTokens, item.completionTokens) }}</td></tr></tbody>
      </table>
      <p v-else class="meta">本月暂无可归属课程的 AI 调用。</p>
    </section>

    <section class="card">
      <h2>本月学生用量 Top 20</h2>
      <table v-if="usage.topStudentsMonth.length"><thead><tr><th>学生</th><th>调用</th><th>Tokens</th></tr></thead>
        <tbody><tr v-for="item in usage.topStudentsMonth" :key="item.userId"><td>{{ item.displayName }} · {{ item.email }}</td><td>{{ item.calls }}</td><td>{{ tokens(item.promptTokens, item.completionTokens) }}</td></tr></tbody>
      </table>
      <p v-else class="meta">本月暂无用量。</p>
    </section>
  </template>
</template>
