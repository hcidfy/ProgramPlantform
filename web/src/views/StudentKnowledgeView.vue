<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ApiError, request } from '../api'
import { formatTime } from '../labels'
import type { ChatAnswer, ChatConversation, ChatMessage } from '../ai-types'
import type { TeachingClassView } from '../types'

const classes = ref<TeachingClassView[]>([])
const selectedCourseId = ref('')
const question = ref('')
const answer = ref<ChatAnswer | null>(null)
const conversations = ref<ChatConversation[]>([])
const messages = ref<ChatMessage[]>([])
const activeConversation = ref<string | null>(null)
const busy = ref(false)
const error = ref('')
const notice = ref('')

const courses = computed(() => {
  const seen = new Map<string, string>()
  for (const item of classes.value) {
    if (!seen.has(item.courseId)) {
      seen.set(item.courseId, item.courseName)
    }
  }
  return Array.from(seen.entries()).map(([id, name]) => ({ id, name }))
})

async function loadClasses() {
  try {
    classes.value = await request<TeachingClassView[]>('/api/student/courses').then((data) => data.items)
    if (courses.value.length > 0) {
      selectedCourseId.value = courses.value[0].id
      await loadConversations()
    }
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '加载课程失败'
  }
}

async function loadConversations() {
  if (!selectedCourseId.value) {
    return
  }
  try {
    conversations.value = await request<ChatConversation[]>(
      `/api/student/courses/${selectedCourseId.value}/ai/conversations`
    )
  } catch {
    conversations.value = []
  }
}

async function ask() {
  if (!question.value.trim() || !selectedCourseId.value) {
    return
  }
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const result = await request<ChatAnswer>(`/api/student/courses/${selectedCourseId.value}/ai/ask`, {
      method: 'POST',
      body: JSON.stringify({ question: question.value.trim(), conversationId: activeConversation.value })
    })
    answer.value = result
    activeConversation.value = result.conversationId
    question.value = ''
    await loadConversations()
    await loadMessages(result.conversationId)
    if (result.usage) {
      notice.value = result.noEntries
        ? '课程资料未命中，本次没有调用模型，也没有消耗 token。'
        : `本次消耗 ${result.usage.promptTokens + result.usage.completionTokens} tokens`
    }
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '提问失败'
  } finally {
    busy.value = false
  }
}

async function loadMessages(conversationId: string) {
  try {
    messages.value = await request<ChatMessage[]>(`/api/student/ai/conversations/${conversationId}`)
    activeConversation.value = conversationId
    answer.value = null
  } catch {
    messages.value = []
  }
}

function newConversation() {
  activeConversation.value = null
  messages.value = []
  answer.value = null
}

function switchCourse() {
  newConversation()
  loadConversations()
}

onMounted(loadClasses)
</script>

<template>
  <h1>课程知识问答</h1>
  <p class="sub">基于老师维护的课程知识条目回答；没有覆盖时会明确说明</p>

  <div class="toolbar">
    <label class="inline">
      <span>课程</span>
      <select v-model="selectedCourseId" @change="switchCourse">
        <option v-for="course in courses" :key="course.id" :value="course.id">{{ course.name }}</option>
      </select>
    </label>
    <button class="link" type="button" @click="newConversation">+ 新会话</button>
    <span class="meta">历史会话 {{ conversations.length }} 个</span>
  </div>

  <p v-if="error" class="error">{{ error }}</p>
  <p v-if="notice" class="info">{{ notice }}</p>

  <div class="chat-layout">
    <aside class="chat-side">
      <div
        v-for="item in conversations"
        :key="item.id"
        class="chat-item"
        :class="{ active: item.id === activeConversation }"
        @click="loadMessages(item.id)"
      >
        <div class="meta">{{ formatTime(item.createdAt) }}</div>
        <div class="small">{{ item.id.slice(0, 8) }}…</div>
      </div>
      <p v-if="conversations.length === 0" class="meta">还没有会话</p>
    </aside>

    <section class="chat-main">
      <div v-if="messages.length > 0" class="chat-history">
        <div v-for="item in messages" :key="item.id" class="chat-bubble" :class="item.role">
          <div class="meta">{{ item.role === 'user' ? '我' : 'AI 助教' }} · {{ formatTime(item.createdAt) }}</div>
          <p>{{ item.text }}</p>
        </div>
      </div>

      <div v-if="answer" class="card">
        <h3>
          AI 回答
          <span v-if="answer.uncertain" class="pill warn">课程资料未覆盖</span>
        </h3>
        <div class="ai-answer" @copy.prevent @contextmenu.prevent @selectstart.prevent>
          <section v-for="(segment, index) in answer.segments" :key="index" class="ai-card">
            <p class="ai-text">{{ segment }}</p>
          </section>
        </div>
        <div v-if="answer.citations.length > 0">
          <h4>引用的知识条目</h4>
          <details v-for="cite in answer.citations" :key="cite.entryId" class="cite">
            <summary>{{ cite.title }}</summary>
            <pre class="cite-body">{{ cite.contentMd }}</pre>
          </details>
        </div>
      </div>

      <form class="ask-box" @submit.prevent="ask">
        <textarea v-model="question" rows="3" maxlength="500" placeholder="向 AI 提问，例如：Java 里 == 和 equals 有什么区别？" />
        <button class="primary small" type="submit" :disabled="busy">
          {{ busy ? '思考中…' : '提问' }}
        </button>
      </form>
    </section>
  </div>
</template>
