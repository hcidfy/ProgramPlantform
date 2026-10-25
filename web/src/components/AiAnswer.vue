<script setup lang="ts">
import { computed } from 'vue'
import AiCodeCanvas from './AiCodeCanvas.vue'
import type { AiAnswer } from '../ai-types'

/**
 * AI 回答卡片（PRD §8.5 前端那一半：不可复制）。
 * 三层拦住复制：CSS user-select、事件拦截、关键代码用 Canvas 绘制。
 * 明示局限：截图/开发者工具拦不住，产品定位是"提高成本"。
 */
const props = defineProps<{ answer: AiAnswer }>()

const payload = computed(() => props.answer.payload as Record<string, unknown>)
const taskLabel = computed(() => {
  switch (props.answer.task) {
    case 'explain':
      return '答疑'
    case 'hint':
      return '思路提示'
    case 'analyze':
      return '正确结果分析'
    default:
      return 'AI'
  }
})

const location = computed(() => {
  const value = payload.value.location as { line?: number | null } | undefined
  return value?.line ?? null
})

const keySnippet = computed(() => String(payload.value.key_snippet ?? ''))
const outline = computed(() => String(payload.value.outline ?? ''))
const checklist = computed(() => {
  const value = payload.value.checklist
  return Array.isArray(value) ? value.map((item) => String(item)) : []
})
const firstFail = computed(() => payload.value.first_fail_case as
  | { seq?: number; expected?: string; actual?: string }
  | undefined)

function blockKeys(event: KeyboardEvent) {
  const key = event.key.toLowerCase()
  if ((event.ctrlKey || event.metaKey) && ['c', 'x', 'a', 's', 'p'].includes(key)) {
    event.preventDefault()
  }
}
</script>

<template>
  <div
    class="ai-answer"
    tabindex="0"
    @copy.prevent
    @cut.prevent
    @contextmenu.prevent
    @selectstart.prevent
    @keydown="blockKeys"
  >
    <div class="ai-head">
      <span class="badge">{{ taskLabel }}</span>
      <span v-if="answer.cached" class="pill">缓存</span>
      <span v-if="answer.degraded" class="pill warn">降级为纯文本</span>
      <span class="meta">
        tokens {{ answer.usage.promptTokens + answer.usage.completionTokens }} · 本题已用
        {{ answer.remaining.perProblemCalls }}/{{ answer.remaining.perProblemCallLimit }} 次
      </span>
    </div>

    <p v-if="answer.degraded" class="ai-text">{{ answer.degradedText }}</p>

    <template v-else-if="answer.task === 'explain'">
      <section class="ai-card">
        <h4>定位</h4>
        <p>{{ location ? `第 ${location} 行附近` : '无法从判题结果定位到具体行，先看下面原因' }}</p>
      </section>
      <section class="ai-card">
        <h4>原因</h4>
        <p class="ai-text">{{ payload.cause }}</p>
        <p class="ai-text meta">{{ payload.why }}</p>
      </section>
      <section class="ai-card">
        <h4>下一步</h4>
        <p class="ai-text">{{ payload.next_step }}</p>
      </section>
      <section v-if="keySnippet" class="ai-card">
        <h4>关键片段（不可复制，仅可看）</h4>
        <AiCodeCanvas :text="keySnippet" />
      </section>
    </template>

    <template v-else-if="answer.task === 'hint'">
      <section class="ai-card">
        <h4>L{{ payload.level }} 概念</h4>
        <p class="ai-text">{{ payload.concept }}</p>
      </section>
      <section class="ai-card">
        <h4>方向</h4>
        <p class="ai-text">{{ payload.direction }}</p>
      </section>
      <section class="ai-card">
        <h4>骨架（伪代码，不可复制）</h4>
        <AiCodeCanvas :text="outline" />
      </section>
    </template>

    <template v-else-if="answer.task === 'analyze'">
      <section class="ai-card">
        <h4>诊断</h4>
        <p class="ai-text">{{ payload.diagnosis }}</p>
      </section>
      <section v-if="firstFail" class="ai-card">
        <h4>首个失败用例</h4>
        <p class="meta">
          第 {{ firstFail.seq }} 组
          <span v-if="firstFail.expected"> · 期望 <code>{{ firstFail.expected }}</code></span>
          <span v-if="firstFail.actual"> · 实际 <code>{{ firstFail.actual }}</code></span>
        </p>
        <p class="ai-text">{{ payload.gap }}</p>
      </section>
      <section class="ai-card">
        <h4>怎么改</h4>
        <p class="ai-text">{{ payload.fix_direction }}</p>
        <ul>
          <li v-for="(item, index) in checklist" :key="index">{{ item }}</li>
        </ul>
      </section>
    </template>

    <p class="ai-disclaimer">
      AI 生成，可能有误；判题结果永远优先于 AI 的说法。截图与开发者工具无法阻止，这里只是提高抄袭成本。
    </p>
  </div>
</template>
