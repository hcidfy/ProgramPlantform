<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as monaco from 'monaco-editor'
// monaco 0.5x 的 exports 映射是 ./<path> -> esm/vs/<path>.js，所以不能写 esm/vs/... 深层路径
import EditorWorker from 'monaco-editor/editor/editor.worker?worker'

// 只要 Java/Python 的编辑能力，不需要语言服务，所以只起基础 worker
self.MonacoEnvironment = { getWorker: () => new EditorWorker() } as never

const props = defineProps<{ modelValue: string; language: string }>()
const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>()

const host = ref<HTMLElement | null>(null)
let editor: monaco.editor.IStandaloneCodeEditor | null = null

onMounted(() => {
  if (!host.value) {
    return
  }
  editor = monaco.editor.create(host.value, {
    value: props.modelValue,
    language: props.language,
    theme: 'vs',
    automaticLayout: true,
    minimap: { enabled: false },
    fontSize: 13,
    tabSize: 4,
    scrollBeyondLastLine: false,
    renderWhitespace: 'selection'
  })
  editor.onDidChangeModelContent(() => emit('update:modelValue', editor?.getValue() ?? ''))
})

watch(() => props.language, (language) => {
  const model = editor?.getModel()
  if (model) {
    monaco.editor.setModelLanguage(model, language)
  }
})

watch(() => props.modelValue, (value) => {
  if (editor && editor.getValue() !== value) {
    editor.setValue(value)
  }
})

onBeforeUnmount(() => {
  editor?.dispose()
  editor = null
})
</script>

<template>
  <div ref="host" class="editor-host" />
</template>
