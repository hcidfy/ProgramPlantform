/** 判题/作业相关的中文文案集中放这里，避免各页面各写一套。 */
export function resultLabel(result?: string | null): string {
  switch (result) {
    case 'accepted':
      return '通过'
    case 'wrong_answer':
      return '答案错误'
    case 'compile_error':
      return '编译错误'
    case 'runtime_error':
      return '运行错误'
    case 'timeout':
      return '超时'
    default:
      return result ?? '-'
  }
}

export function submissionStatusLabel(status?: string | null): string {
  switch (status) {
    case 'queued':
      return '排队中'
    case 'judging':
      return '判题中'
    case 'done':
      return '已完成'
    case 'judge_error':
      return '判题异常'
    default:
      return status ?? '-'
  }
}

export function caseStatusLabel(status?: string | null): string {
  switch (status) {
    case 'ac':
      return '通过'
    case 'wa':
      return '输出不符'
    case 'tle':
      return '超时'
    case 're':
      return '运行错误'
    default:
      return status ?? '-'
  }
}

export function assignmentStatusLabel(status?: string | null): string {
  switch (status) {
    case 'draft':
      return '草稿'
    case 'published':
      return '已发布'
    case 'closed':
      return '已关闭'
    default:
      return status ?? '-'
  }
}

export function problemStatusText(status?: string | null): string {
  switch (status) {
    case 'passed':
      return '已通过'
    case 'submitted':
      return '已提交未通过'
    case 'not_started':
      return '未开始'
    case 'published':
      return '已发布'
    case 'draft':
      return '草稿'
    default:
      return status ?? '-'
  }
}

export function resultClass(result?: string | null): string {
  return result === 'accepted' ? 'pill ok' : result ? 'pill bad' : 'pill'
}

export function formatTime(value?: string | null): string {
  if (!value) {
    return '-'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
