/** M3：AI 相关类型（与 PRD §15.3 / §15.4 的返回结构对齐） */

export interface AiUsage {
  promptTokens: number
  completionTokens: number
}

export interface AiRemaining {
  perProblemCalls: number
  perProblemCallLimit: number
  dailyTokens: number
  dailyTokenLimit: number
}

export interface AiStatus {
  hasSubmission: boolean
  passed: boolean
  canUseAi: boolean
  reason: string | null
  hintLevelsAvailable: number
  hintLevelsUnlocked: number
  perProblemCalls: number
  perProblemCallLimit: number
  dailyTokens: number
  dailyTokenLimit: number
}

export interface AiAnswer {
  task: 'explain' | 'hint' | 'analyze' | 'chat'
  promptVersion: number
  payload: Record<string, unknown>
  cached: boolean
  usage: AiUsage
  remaining: AiRemaining
  degraded: boolean
  degradedText: string | null
}

export interface KnowledgeEntry {
  id: string
  courseId: string
  title: string
  contentMd: string
  tags: string[]
  status: 'draft' | 'published' | 'archived'
  publishedAt: string | null
  updatedAt: string
  score: number | null
}

export interface ChatCitation {
  entryId: string
  title: string
  contentMd: string
}

export interface ChatAnswer {
  conversationId: string
  segments: string[]
  citations: ChatCitation[]
  uncertain: boolean
  usage: AiUsage
  degraded: boolean
  degradedText: string | null
  noEntries: boolean
}

export interface ChatConversation {
  id: string
  userId: string
  kind: string
  courseId: string | null
  problemId: string | null
  submissionId: string | null
  level: number | null
  createdAt: string
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  text: string
  tokens: number | null
  createdAt: string
}

export interface QaRecord {
  conversationId: string
  studentId: string
  studentName: string
  studentEmail: string
  question: string | null
  answer: string | null
  createdAt: string
}
