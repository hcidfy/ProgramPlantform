<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'

/**
 * 用 Canvas 画代码片段（PRD §8.5 渲染层）：
 * DOM 里没有可复制的文本节点，Ctrl+C / 右键都拿不到内容。
 */
const props = defineProps<{ text: string }>()
const canvas = ref<HTMLCanvasElement | null>(null)

const FONT = '13px ui-monospace, SFMono-Regular, Consolas, monospace'
const LINE_HEIGHT = 22
const PAD = 12

function draw() {
  const element = canvas.value
  if (!element) {
    return
  }
  const context = element.getContext('2d')
  if (!context) {
    return
  }
  const lines = (props.text || '').split('\n')
  const dpr = window.devicePixelRatio || 1

  context.font = FONT
  const width = Math.max(...lines.map((line) => context.measureText(line).width), 120) + PAD * 2
  const height = lines.length * LINE_HEIGHT + PAD * 2

  element.width = Math.ceil(width * dpr)
  element.height = Math.ceil(height * dpr)
  element.style.width = `${width}px`
  element.style.height = `${height}px`

  context.scale(dpr, dpr)
  context.fillStyle = '#f8fafc'
  context.fillRect(0, 0, width, height)
  context.strokeStyle = '#e3e8ef'
  context.strokeRect(0.5, 0.5, width - 1, height - 1)
  context.fillStyle = '#1f2937'
  context.font = FONT
  context.textBaseline = 'middle'
  lines.forEach((line, index) => {
    context.fillText(line, PAD, PAD + LINE_HEIGHT * index + LINE_HEIGHT / 2)
  })
}

onMounted(draw)
watch(() => props.text, draw)
</script>

<template>
  <div class="ai-canvas">
    <canvas ref="canvas" />
  </div>
</template>
