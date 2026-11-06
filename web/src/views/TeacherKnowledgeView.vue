<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ApiError, request } from '../api'
import { formatTime } from '../labels'
import type { KnowledgeEntry, QaRecord } from '../ai-types'
import type { TeachingClassView } from '../types'

const classes = ref<TeachingClassView[]>([])
const selectedCourseId = ref('')
const entries = ref<KnowledgeEntry[]>([])
const qaRows = ref<QaRecord[]>([])
const selectedTcId = ref('')
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const notice = ref('')

const form = ref({ title: '', contentMd: '', tags: '' })
const editingId = ref<string | null>(null)

const courses = computed(() => {
  const seen = new Map<string, string>()
  for (const item of classes.value) {
    if (!seen.has(item.courseId)) {
      seen.set(item.courseId, item.courseName)
    }
  }
  return Array.from(seen.entries()).map(([id, name]) => ({ id, name }))
})

async function load() {
  loading.value = true
  try {
    classes.value = await request<TeachingClassView[]>('/api/teacher/teaching-classes')
    if (courses.value.length > 0) {
      selectedCourseId.value = courses.value[0].id
      await loadEntries()
    }
    if (classes.value.length > 0) {
      selectedTcId.value = classes.value[0].id
      await loadQa()
    }
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function loadEntries() {
  if (!selectedCourseId.value) {
    return
  }
  try {
    entries.value = await request<KnowledgeEntry[]>(`/api/teacher/knowledge?courseId=${selectedCourseId.value}`)
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '加载知识条目失败'
  }
}

async function loadQa() {
  if (!selectedTcId.value) {
    return
  }
  try {
    const page = await request<{ items: QaRecord[] }>(`/api/teacher/knowledge-qa?teachingClassId=${selectedTcId.value}`)
    qaRows.value = page.items
  } catch {
    qaRows.value = []
  }
}

async function save() {
  if (!selectedCourseId.value) {
    return
  }
  if (!form.value.title.trim() || !form.value.contentMd.trim()) {
    error.value = '标题与内容都要填'
    return
  }
  busy.value = true
  error.value = ''
  notice.value = ''
  const tags = form.value.tags.split(/[,，\s]+/).filter(Boolean)
  try {
    if (editingId.value) {
      await request<void>(`/api/teacher/knowledge/${editingId.value}`, {
        method: 'PUT',
        body: JSON.stringify({ courseId: selectedCourseId.value, title: form.value.title, contentMd: form.value.contentMd, tags })
      })
      notice.value = '知识条目已更新'
    } else {
      await request<{ id: string }>('/api/teacher/knowledge', {
        method: 'POST',
        body: JSON.stringify({ courseId: selectedCourseId.value, title: form.value.title, contentMd: form.value.contentMd, tags })
      })
      notice.value = '已创建草稿，点「发布」后才进检索池'
    }
    resetForm()
    await loadEntries()
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '保存失败'
  } finally {
    busy.value = false
  }
}

function edit(entry: KnowledgeEntry) {
  editingId.value = entry.id
  form.value = { title: entry.title, contentMd: entry.contentMd, tags: (entry.tags || []).join(',') }
}

function resetForm() {
  editingId.value = null
  form.value = { title: '', contentMd: '', tags: '' }
}

async function publish(id: string) {
  error.value = ''
  try {
    await request<void>(`/api/teacher/knowledge/${id}/publish`, { method: 'POST' })
    notice.value = '已发布，学生现在能检索到这条'
    await loadEntries()
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '发布失败'
  }
}

async function remove(id: string) {
  error.value = ''
  try {
    await request<void>(`/api/teacher/knowledge/${id}`, { method: 'DELETE' })
    await loadEntries()
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '删除失败'
  }
}

watch(selectedCourseId, () => {
  resetForm()
  loadEntries()
})
watch(selectedTcId, loadQa)

onMounted(load)
</script>

<template>
  <h1>课程知识问答资料</h1>
  <p class="sub">教师维护的课程知识条目，是 AI 知识问答唯一的依据来源</p>

  <p v-if="loading" class="meta">加载中…</p>
  <p v-if="error" class="error">{{ error }}</p>
  <p v-if="notice" class="info">{{ notice }}</p>

  <div class="toolbar">
    <label class="inline">
      <span>课程</span>
      <select v-model="selectedCourseId">
        <option v-for="course in courses" :key="course.id" :value="course.id">{{ course.name }}</option>
      </select>
    </label>
    <span class="meta">共 {{ entries.length }} 条（只有已发布的会被检索）</span>
  </div>

  <table v-if="entries.length > 0">
    <thead>
      <tr><th>标题</th><th>标签</th><th>状态</th><th>更新时间</th><th></th></tr>
    </thead>
    <tbody>
      <tr v-for="entry in entries" :key="entry.id">
        <td>{{ entry.title }}</td>
        <td class="meta">{{ (entry.tags || []).join(' / ') || '-' }}</td>
        <td>
          <span class="pill" :class="entry.status === 'published' ? 'ok' : ''">
            {{ entry.status === 'published' ? '已发布' : '草稿' }}
          </span>
        </td>
        <td class="meta">{{ formatTime(entry.updatedAt) }}</td>
        <td class="right">
          <button class="link" type="button" @click="edit(entry)">编辑</button>
          <button v-if="entry.status === 'draft'" class="link" type="button" @click="publish(entry.id)">发布</button>
          <button v-if="entry.status === 'draft'" class="link" type="button" @click="remove(entry.id)">删除</button>
          <span v-if="entry.status !== 'draft'" class="meta">修改后仍保持发布状态，新问答会使用最新内容</span>
        </td>
      </tr>
    </tbody>
  </table>
  <div v-else class="empty">还没有知识条目。先把课上常见问题写成条目，AI 才有东西可引用。</div>

  <h2>{{ editingId ? '编辑条目' : '新建条目' }}</h2>
  <div class="card">
    <label><span>标题</span><input v-model="form.title" maxlength="200" /></label>
    <label><span>内容（Markdown）</span><textarea v-model="form.contentMd" rows="6" /></label>
    <label><span>标签（逗号分隔，用于检索匹配）</span><input v-model="form.tags" placeholder="equals, String, 比较" /></label>
    <div class="toolbar">
      <button class="primary small" type="button" :disabled="busy" @click="save">
        {{ editingId ? '保存修改' : '创建草稿' }}
      </button>
      <button v-if="editingId" class="link" type="button" @click="resetForm">取消编辑</button>
    </div>
  </div>

  <h2>本班问答记录</h2>
  <div class="toolbar">
    <label class="inline">
      <span>教学班</span>
      <select v-model="selectedTcId">
        <option v-for="item in classes" :key="item.id" :value="item.id">
          {{ item.className }} · {{ item.courseName }}
        </option>
      </select>
    </label>
    <span class="meta">用于发现共性疑问（不做社区）</span>
  </div>
  <table v-if="qaRows.length > 0">
    <thead>
      <tr><th>学生</th><th>问题</th><th>AI 回答摘要</th><th>时间</th></tr>
    </thead>
    <tbody>
      <tr v-for="row in qaRows" :key="row.conversationId">
        <td>{{ row.studentName }}<span class="meta"> · {{ row.studentEmail }}</span></td>
        <td>{{ row.question }}</td>
        <td class="meta">{{ (row.answer || '').slice(0, 80) }}</td>
        <td class="meta">{{ formatTime(row.createdAt) }}</td>
      </tr>
    </tbody>
  </table>
  <div v-else class="empty">本班还没有学生用过知识问答。</div>
</template>
