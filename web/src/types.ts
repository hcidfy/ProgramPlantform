export type Role = 'student' | 'teacher' | 'admin'

export interface UserView {
  id: string
  email: string
  displayName: string
  role: Role
  status: string
}

export interface TeachingClassView {
  id: string
  classId: string
  className: string
  gradeYear: number | null
  courseId: string
  courseCode: string
  courseName: string
  courseDescription: string | null
  teacherId: string
  teacherName: string
  term: string
  status: string
  studentCount: number
}

export interface StudentRow {
  id: string
  email: string
  displayName: string
}

export interface StudentCourses {
  needClass: boolean
  items: TeachingClassView[]
}

export interface TeacherClassDetail {
  teachingClass: TeachingClassView
  students: StudentRow[]
}

export interface ApiErrorBody {
  code: string
  message: string
}


// ===== M2：题库 / 作业 / 提交 / 判题 =====

export interface Page<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface TestCase {
  id?: string
  problemId?: string
  seq: number
  input: string
  expected: string
  hidden: boolean
  score: number
}

export interface ProblemAdmin {
  id: string
  courseId: string
  title: string
  statementMd: string
  language: 'java' | 'python3'
  difficulty: 'easy' | 'medium' | 'hard'
  timeLimitMs: number
  memLimitKb: number
  initialCode: string | null
  status: 'draft' | 'published' | 'archived'
  version: number
  previousVersionId: string | null
  publishedAt: string | null
  createdAt: string
}

export interface ProblemDetail {
  problem: ProblemAdmin
  testCases: TestCase[]
}

export interface ProblemInAssignment {
  problemId: string
  seq: number
  score: number
  title: string
  language: string
  difficulty: string
  timeLimitMs: number
  memLimitKb: number
  status: string
}

export interface Progress {
  expectedCount: number
  submittedCount: number
  passedCount: number
  passRate: number
  passedSubmissionRate: number
}

export interface Assignment {
  id: string
  teachingClassId: string
  title: string
  description: string | null
  deadline: string | null
  status: 'draft' | 'published' | 'closed'
  createdAt: string
}

export interface TeacherAssignmentView {
  assignment: Assignment
  problems: ProblemInAssignment[]
  progress: Progress
}

export interface StudentProblemStatusView {
  problemId: string
  status: 'not_started' | 'submitted' | 'passed'
}

export interface StudentAssignmentView {
  assignment: Assignment
  problems: ProblemInAssignment[]
  statuses: StudentProblemStatusView[]
  expired: boolean
}

export interface StudentProblemView {
  id: string
  courseId: string
  title: string
  statementMd: string
  language: 'java' | 'python3'
  difficulty: string
  timeLimitMs: number
  memLimitKb: number
  initialCode: string | null
}

export interface CaseView {
  seq: number
  status: 'ac' | 'wa' | 'tle' | 're'
  timeMs: number
  input: string | null
  expected: string | null
  actual: string | null
  hidden: boolean
}

export interface SubmissionView {
  id: string
  problemId: string
  assignmentId: string | null
  language: string
  status: 'queued' | 'judging' | 'done' | 'judge_error'
  result: 'accepted' | 'wrong_answer' | 'compile_error' | 'runtime_error' | 'timeout' | null
  passedCases: number
  totalCases: number
  score: number
  compileOutput: string | null
  sourceCode: string
  queuePosition: number
  createdAt: string
  finishedAt: string | null
  cases: CaseView[]
  firstFailedCase: CaseView | null
}

export interface SubmissionSummary {
  id: string
  problemId: string
  assignmentId: string | null
  language: string
  status: string
  result: string | null
  passedCases: number
  totalCases: number
  score: number
  createdAt: string
  finishedAt: string | null
}

export interface TeacherSubmission {
  id: string
  userId: string
  studentName: string
  studentEmail: string
  problemId: string
  problemTitle: string
  language: string
  status: string
  result: string | null
  passedCases: number
  totalCases: number
  score: number
  sourceCode: string
  createdAt: string
}
