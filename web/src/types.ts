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
