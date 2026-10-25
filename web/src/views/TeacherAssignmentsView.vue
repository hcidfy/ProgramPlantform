<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ApiError, request } from '../api'
import { assignmentStatusLabel, formatTime } from '../labels'
import type { Page, ProblemAdmin, TeacherAssignmentView, TeachingClassView } from '../types'

const classes = ref<TeachingClassView[]>([])
const selectedTcId = ref('')
const assignments = ref<TeacherAssignmentView[]>([])
const publishable = ref<ProblemAdmin[]>([])
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const notice = ref('')

const form = ref({
  title: '',
  description: '',
  deadline: '',
  picks: [] as Array<{ problemId: string; score: number }>
})

const selectedClass = computed(() => classes.value.find((c) => c.id === selectedTcId.value))

async function loadClasses() {
  try {
    classes.value = await request<TeachingClassView[]>('/api/teacher/teaching-classes')
    if (classes.value.length > 0) {
      selectedTcId.value = classes.value[0].id
    }
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '加载教学班失败'
  } finally {
    loading.value = false
  }
}

async function loadAssignments() {
  if (!selectedTcId.value) {
    return
  }
  try {
    assignments.value = await request<TeacherAssignmentView[]>(
      `/api/teacher/assignments?teachingClassId=${selectedTcId.value}`
    )
    const courseId = selectedClass.value?.courseId
    if (courseId) {
      const data = await request<Page<ProblemAdmin>>(
        `/api/teacher/problems?courseId=${courseId}&status=published&size=100`
      )
      publishable.value = data.items
    }
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '加载作业失败'
  }
}

function togglePick(problemId: string) {
  const index = form.value.picks.findIndex((p) => p.problemId === problemId)
  if (index >= 0) {
    form.value.picks.splice(index, 1)
  } else {
    form.value.picks.push({ problemId, score: 10 })
  }
}

function picked(problemId: string): boolean {
  return form.value.picks.some((p) => p.problemId === problemId)
}

function scoreOf(problemId: string): number {
  return form.value.picks.find((p) => p.problemId === problemId)?.score ?? 0
}

function setScore(problemId: string, raw: string): void {
  const target = form.value.picks.find((p) => p.problemId === problemId)
  if (target) {
    target.score = Number(raw) || 0
  }
}

async function create() {
  error.value = ''
  notice.value = ''
  if (!selectedTcId.value) {
    error.value = '请先选择教学班'
    return
  }
  if (!form.value.title.trim()) {
    error.value = '作业标题不能为空'
    return
  }
  if (form.value.picks.length === 0) {
    error.value = '至少选 1 道题'
    return
  }
  busy.value = true
  try {
    await request<{ id: string }>('/api/teacher/assignments', {
      method: 'POST',
      body: JSON.stringify({
        teachingClassId: selectedTcId.value,
        title: form.value.title,
        description: form.value.description,
        deadline: form.value.deadline ? new Date(form.value.deadline).toISOString() : null,
        problems: form.value.picks
      })
    })
    notice.value = '作业已创建（草稿），记得点「发布」推送给学生'
    form.value = { title: '', description: '', deadline: '', picks: [] }
    await loadAssignments()
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '创建作业失败'
  } finally {
    busy.value = false
  }
}

async function publish(id: string) {
  error.value = ''
  try {
    await request<void>(`/api/teacher/assignments/${id}/publish`, { method: 'POST' })
    notice.value = '已发布，学生现在能看到这份作业了'
    await loadAssignments()
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '发布失败'
  }
}

watch(selectedTcId, loadAssignments)
onMounted(loadClasses)
</script>

<template>
  <h1>作业</h1>
  <p class="sub">作业挂在教学班下，发布即推送给该班全部学生</p>

  <p v-if="loading" class="meta">加载中…</p>
  <p v-if="error" class="error">{{ error }}</p>
  <p v-if="notice" class="info">{{ notice }}</p>

  <div class="toolbar">
    <label class="inline">
      <span>教学班</span>
      <select v-model="selectedTcId">
        <option v-for="item in classes" :key="item.id" :value="item.id">
          {{ item.className }} · {{ item.courseName }}
        </option>
      </select>
    </label>
  </div>

  <table v-if="assignments.length > 0">
    <thead>
      <tr>
        <th>作业</th>
        <th>状态</th>
        <th>截止</th>
        <th>应交</th>
        <th>已交</th>
        <th>通过</th>
        <th>通过率</th>
        <th></th>
      </tr>
    </thead>
    <tbody>
      <tr v-for="view in assignments" :key="view.assignment.id">
        <td>{{ view.assignment.title }}</td>
        <td>
          <span class="pill" :class="view.assignment.status === 'published' ? 'ok' : ''">
            {{ assignmentStatusLabel(view.assignment.status) }}
          </span>
        </td>
        <td class="meta">{{ formatTime(view.assignment.deadline) }}</td>
        <td>{{ view.progress.expectedCount }}</td>
        <td>{{ view.progress.submittedCount }}</td>
        <td>{{ view.progress.passedCount }}</td>
        <td>{{ view.progress.passRate }}%</td>
        <td class="right">
          <router-link :to="`/teacher/assignments/${view.assignment.id}`">详情</router-link>
          <button
            v-if="view.assignment.status === 'draft'"
            class="link"
            type="button"
            @click="publish(view.assignment.id)"
          >发布</button>
        </td>
      </tr>
    </tbody>
  </table>
  <div v-else-if="!loading" class="empty">该教学班还没有作业。</div>

  <h2>新建作业</h2>
  <div class="card">
    <label><span>标题</span><input v-model="form.title" maxlength="200" /></label>
    <label><span>说明</span><textarea v-model="form.description" rows="3" /></label>
    <label><span>截止时间</span><input v-model="form.deadline" type="datetime-local" /></label>

    <h3>选题（只列本课程已发布的题）</h3>
    <p v-if="publishable.length === 0" class="meta">该课程还没有已发布的题目，先去题库发布。</p>
    <table v-else class="nested">
      <thead>
        <tr><th>选择</th><th>题目</th><th>语言</th><th>难度</th><th>分值</th></tr>
      </thead>
      <tbody>
        <tr v-for="item in publishable" :key="item.id">
          <td><input type="checkbox" :checked="picked(item.id)" @change="togglePick(item.id)" /></td>
          <td>{{ item.title }}</td>
          <td>{{ item.language }}</td>
          <td>{{ item.difficulty }}</td>
          <td>
            <input
              :value="scoreOf(item.id)"
              type="number"
              class="narrow"
              :disabled="!picked(item.id)"
              @input="setScore(item.id, ($event.target as HTMLInputElement).value)"
            />
          </td>
        </tr>
      </tbody>
    </table>

    <button class="primary" type="button" :disabled="busy" @click="create">创建作业草稿</button>
  </div>
</template>
