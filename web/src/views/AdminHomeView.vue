<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ApiError, request } from '../api'
import type { Page, TeachingClassView, UserView } from '../types'

interface SchoolClass {
  id: string
  name: string
  gradeYear: number | null
  major: string | null
  homeroomTeacherId: string | null
  homeroomTeacherName: string | null
}

interface Course {
  id: string
  code: string
  name: string
  description: string | null
}

interface ImportResult {
  created: number
  createdUsers: Array<{ line: number; email: string; role: string; initialPassword: string }>
  failed: Array<{ line: number; reason: string }>
}

const classes = ref<SchoolClass[]>([])
const selectedClassId = ref('')
const classStudents = ref<Array<{ id: string; email: string; displayName: string }>>([])
const existingStudents = ref<UserView[]>([])
const addStudentId = ref('')
const courses = ref<Course[]>([])
const teachers = ref<UserView[]>([])
const teachingClasses = ref<TeachingClassView[]>([])
const loading = ref(true)
const saving = ref('')
const error = ref('')
const notice = ref('')
const csv = ref('')
const importResult = ref<ImportResult | null>(null)

const classForm = reactive({ name: '', gradeYear: '' as string | number, major: '', homeroomTeacherId: '' })
const courseForm = reactive({ code: '', name: '', description: '' })
const teacherForm = reactive({ displayName: '', email: '', initialPassword: '' })
const teachingClassForm = reactive({ classId: '', courseId: '', teacherId: '', term: '' })

const canCreateTeachingClass = computed(
  () => classes.value.length > 0 && courses.value.length > 0 && teachers.value.some((teacher) => teacher.status === 'active')
)
const activeTeachers = computed(() => teachers.value.filter((teacher) => teacher.status === 'active'))
const route = useRoute()
const page = computed(() => route.path.split('/').pop() || 'classes')

async function loadStudents() {
  if (!selectedClassId.value) { classStudents.value = []; return }
  try { classStudents.value = await request<Array<{ id: string; email: string; displayName: string }>>(`/api/admin/enrollments?classId=${selectedClassId.value}`) }
  catch (err) { error.value = err instanceof ApiError ? err.message : '加载班级学生失败' }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [classRows, courseRows, classRowsTeaching, teacherPage] = await Promise.all([
      request<SchoolClass[]>('/api/admin/school-classes'),
      request<Course[]>('/api/admin/courses'),
      request<TeachingClassView[]>('/api/admin/teaching-classes'),
      request<Page<UserView>>('/api/admin/users?role=teacher&page=1&size=100')
    ])
    classes.value = classRows
    courses.value = courseRows
    teachingClasses.value = classRowsTeaching
    teachers.value = teacherPage.items
    const studentPage = await request<Page<UserView>>('/api/admin/users?role=student&page=1&size=100')
    existingStudents.value = studentPage.items
    for (let page = 2; existingStudents.value.length < studentPage.total; page += 1) {
      const next = await request<Page<UserView>>(`/api/admin/users?role=student&page=${page}&size=100`)
      if (!next.items.length) break
      existingStudents.value = [...existingStudents.value, ...next.items]
    }
    if (!selectedClassId.value && classRows.length) selectedClassId.value = classRows[0].id
    if (!teachingClassForm.classId && classRows.length) teachingClassForm.classId = classRows[0].id
    if (!teachingClassForm.courseId && courseRows.length) teachingClassForm.courseId = courseRows[0].id
    if (!teachingClassForm.teacherId && teacherPage.items.some((teacher) => teacher.status === 'active')) teachingClassForm.teacherId = teacherPage.items.find((teacher) => teacher.status === 'active')!.id
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '加载管理数据失败，请刷新重试'
  } finally {
    loading.value = false
    if (selectedClassId.value) await loadStudents()
  }
}

function beginSave(key: string): boolean {
  if (saving.value) return false
  saving.value = key
  error.value = ''
  notice.value = ''
  return true
}

async function createClass() {
  if (!beginSave('class')) return
  try {
    const created = await request<{ id: string }>('/api/admin/school-classes', {
      method: 'POST',
      body: JSON.stringify({
        name: classForm.name.trim(),
        gradeYear: classForm.gradeYear ? Number(classForm.gradeYear) : null,
        major: classForm.major.trim() || null,
        homeroomTeacherId: classForm.homeroomTeacherId
      })
    })
    selectedClassId.value = created.id
    classForm.name = ''
    classForm.major = ''
    notice.value = '行政班已创建。'
    await load()
    await loadStudents()
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '创建行政班失败'
  } finally {
    saving.value = ''
  }
}

async function createCourse() {
  if (!beginSave('course')) return
  try {
    await request('/api/admin/courses', {
      method: 'POST',
      body: JSON.stringify({
        code: courseForm.code.trim(),
        name: courseForm.name.trim(),
        description: courseForm.description.trim() || null
      })
    })
    courseForm.code = ''
    courseForm.name = ''
    courseForm.description = ''
    notice.value = '课程已创建。'
    await load()
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '创建课程失败'
  } finally {
    saving.value = ''
  }
}

async function createTeacher() {
  if (!beginSave('teacher')) return
  try {
    await request('/api/admin/users', {
      method: 'POST',
      body: JSON.stringify({
        displayName: teacherForm.displayName.trim(),
        email: teacherForm.email.trim(),
        role: 'teacher',
        initialPassword: teacherForm.initialPassword
      })
    })
    teacherForm.displayName = ''
    teacherForm.email = ''
    teacherForm.initialPassword = ''
    notice.value = '教师账号已创建。请通过安全渠道将初始密码告知教师。'
    await load()
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '创建教师账号失败'
  } finally {
    saving.value = ''
  }
}

async function createTeachingClass() {
  if (!beginSave('teachingClass')) return
  try {
    await request('/api/admin/teaching-classes', {
      method: 'POST',
      body: JSON.stringify({
        classId: teachingClassForm.classId,
        courseId: teachingClassForm.courseId,
        teacherId: teachingClassForm.teacherId,
        term: teachingClassForm.term.trim()
      })
    })
    notice.value = '教学班已创建。'
    teachingClassForm.term = ''
    await load()
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '创建教学班失败'
  } finally {
    saving.value = ''
  }
}

async function readCsvFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > 2 * 1024 * 1024) {
    error.value = 'CSV 文件不能超过 2 MiB。'
    return
  }
  csv.value = await file.text()
  importResult.value = null
  error.value = ''
}

async function importStudents() {
  if (!beginSave('import')) return
  try {
    importResult.value = await request<ImportResult>('/api/admin/users/import', {
      method: 'POST',
      headers: { 'Content-Type': 'text/csv' },
      body: csvForSelectedClass()
    })
    csv.value = ''
    notice.value = `已创建 ${importResult.value.created} 个账号；请安全保存下方初始密码。`
    await load()
  } catch (err) {
    error.value = err instanceof ApiError ? err.message : '导入学生失败'
  } finally {
    saving.value = ''
  }
}

function csvForSelectedClass(): string {
  const selected = classes.value.find((item) => item.id === selectedClassId.value)
  if (!selected) return csv.value
  return csv.value.split(/\r?\n/).map((line, index) => {
    if (!line.trim() || (index === 0 && line.toLowerCase().startsWith('email'))) return line
    const cols = line.split(',')
    if ((cols[2] || 'student').trim().toLowerCase() === 'student' && !cols[3]?.trim()) {
      while (cols.length < 3) cols.push('')
      cols[3] = selected.name
    }
    return cols.join(',')
  }).join('\n')
}

async function removeStudent(studentId: string) {
  if (!confirm('将该学生移出行政班？其历史提交和成绩会保留。')) return
  try { await request(`/api/admin/enrollments/${studentId}`, { method: 'DELETE' }); await loadStudents() }
  catch (err) { error.value = err instanceof ApiError ? err.message : '移出班级失败' }
}

async function addExistingStudent() {
  if (!selectedClassId.value || !addStudentId.value) return
  try {
    await request('/api/admin/enrollments', { method: 'POST', body: JSON.stringify({ studentId: addStudentId.value, classId: selectedClassId.value }) })
    addStudentId.value = ''
    notice.value = '学生已加入班级；该学生原有班级关系（如有）已转移。'
    await loadStudents()
  } catch (err) { error.value = err instanceof ApiError ? err.message : '添加学生失败' }
}

async function deleteEntity(path: string, label: string) {
  if (!confirm(`确认删除${label}？有关联数据时系统会拒绝删除。`)) return
  try {
    await request(path, { method: 'DELETE' })
    if (label === '班级') { selectedClassId.value = ''; classStudents.value = [] }
    notice.value = `${label}已删除`
    await load()
  }
  catch (err) { error.value = err instanceof ApiError ? err.message : `删除${label}失败` }
}

async function toggleTeacher(user: UserView) {
  const status = user.status === 'active' ? 'suspended' : 'active'
  try {
    await request(`/api/admin/users/${user.id}/status`, { method: 'PUT', body: JSON.stringify({ status }) })
    notice.value = status === 'suspended' ? '教师账号已停用，历史教学数据保留。' : '教师账号已启用。'
    await load()
  } catch (err) { error.value = err instanceof ApiError ? err.message : '修改教师状态失败' }
}

onMounted(load)
onMounted(loadStudents)
</script>

<template>
  <h1>组织管理 · {{ page === 'classes' ? '班级' : page === 'teachers' ? '教师账号' : page === 'courses' ? '课程' : '教学班' }}</h1>
  <p class="sub">各项组织数据分页面维护。删除有历史关联的数据会被拒绝；教师账号采用停用方式保留历史记录。</p>
  <nav class="role-nav"><router-link to="/admin/classes">班级</router-link><router-link to="/admin/teachers">教师账号</router-link><router-link to="/admin/courses">课程</router-link><router-link to="/admin/teaching-classes">教学班</router-link></nav>

  <p v-if="loading" class="meta">加载管理数据中…</p>
  <p v-if="error" class="error">{{ error }}</p>
  <p v-if="notice" class="info">{{ notice }}</p>

  <template v-if="!loading">
    <section v-if="page === 'classes'" class="card">
      <h2>创建行政班</h2>
      <form @submit.prevent="createClass">
        <label><span>班级名称</span><input v-model="classForm.name" required maxlength="64" placeholder="例如：2028级应用数学2班" /></label>
        <label><span>年级年份</span><input v-model.number="classForm.gradeYear" type="number" min="2000" max="2200" /></label>
        <label><span>专业（可选）</span><input v-model="classForm.major" maxlength="64" /></label>
        <label><span>班主任（从教师账号选择）</span><select v-model="classForm.homeroomTeacherId" required><option value="" disabled>请选择教师</option><option v-for="teacher in activeTeachers" :key="teacher.id" :value="teacher.id">{{ teacher.displayName }} · {{ teacher.email }}</option></select></label>
        <button class="primary" type="submit" :disabled="!!saving">{{ saving === 'class' ? '创建中…' : '创建行政班' }}</button>
      </form>
      <p class="meta">现有 {{ classes.length }} 个班级</p>
      <table><thead><tr><th>行政班</th><th>班主任</th><th>年级</th><th>专业</th><th>操作</th></tr></thead><tbody><tr v-for="item in classes" :key="item.id"><td>{{ item.name }}</td><td>{{ item.homeroomTeacherName || '—' }}</td><td>{{ item.gradeYear || '—' }}</td><td>{{ item.major || '—' }}</td><td><button class="link" type="button" @click="deleteEntity(`/api/admin/school-classes/${item.id}`, '班级')">删除</button></td></tr></tbody></table>
      <h3>班级名单维护</h3>
      <label><span>选择行政班</span><select v-model="selectedClassId" @change="loadStudents"><option value="" disabled>请选择班级</option><option v-for="item in classes" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
      <form class="toolbar" @submit.prevent="addExistingStudent"><label class="inline"><span>添加已有学生</span><select v-model="addStudentId" required><option value="" disabled>请选择学生</option><option v-for="student in existingStudents" :key="student.id" :value="student.id">{{ student.displayName }} · {{ student.email }}</option></select></label><button class="primary small" type="submit">添加到班级</button></form>
      <table v-if="classStudents.length"><thead><tr><th>学生</th><th>邮箱</th><th>操作</th></tr></thead><tbody><tr v-for="student in classStudents" :key="student.id"><td>{{ student.displayName }}</td><td>{{ student.email }}</td><td><button class="link" type="button" @click="removeStudent(student.id)">移出班级</button></td></tr></tbody></table>
      <p v-else class="meta">该班暂无学生，或尚未选择班级。</p>
      <p class="meta">导入 CSV 时，学生行的 class_name 留空会自动加入当前选中的班级；初始密码只在导入结果显示。</p>
    </section>

    <section v-if="page === 'courses'" class="card">
      <h2>创建课程</h2>
      <form @submit.prevent="createCourse">
        <label><span>课程编码</span><input v-model="courseForm.code" required maxlength="32" placeholder="例如：JAVA101" /></label>
        <label><span>课程名称</span><input v-model="courseForm.name" required maxlength="128" placeholder="例如：Java程序设计" /></label>
        <label><span>课程说明（可选）</span><textarea v-model="courseForm.description" rows="2" /></label>
        <button class="primary" type="submit" :disabled="!!saving">{{ saving === 'course' ? '创建中…' : '创建课程' }}</button>
      </form>
      <p class="meta">现有 {{ courses.length }} 门课程</p>
      <table><thead><tr><th>编码</th><th>课程</th><th>说明</th><th>操作</th></tr></thead><tbody><tr v-for="item in courses" :key="item.id"><td>{{ item.code }}</td><td>{{ item.name }}</td><td>{{ item.description || '—' }}</td><td><button class="link" type="button" @click="deleteEntity(`/api/admin/courses/${item.id}`, '课程')">删除</button></td></tr></tbody></table>
    </section>

    <section v-if="page === 'teachers'" class="card">
      <h2>创建教师账号</h2>
      <form @submit.prevent="createTeacher">
        <label><span>教师姓名</span><input v-model="teacherForm.displayName" required maxlength="64" /></label>
        <label><span>登录邮箱</span><input v-model="teacherForm.email" type="email" required maxlength="190" /></label>
        <label><span>初始密码（8–64 位，须含字母和数字）</span><input v-model="teacherForm.initialPassword" type="password" required minlength="8" maxlength="64" pattern="(?=.*[A-Za-z])(?=.*[0-9]).{8,64}" autocomplete="new-password" /></label>
        <button class="primary" type="submit" :disabled="!!saving">{{ saving === 'teacher' ? '创建中…' : '创建教师' }}</button>
      </form>
      <p class="meta">现有 {{ teachers.length }} 位教师</p>
      <table><thead><tr><th>教师</th><th>邮箱</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="teacher in teachers" :key="teacher.id"><td>{{ teacher.displayName }}</td><td>{{ teacher.email }}</td><td>{{ teacher.status === 'active' ? '启用' : teacher.status }}</td><td><button class="link" type="button" @click="toggleTeacher(teacher)">{{ teacher.status === 'active' ? '停用' : '启用' }}</button></td></tr></tbody></table>
    </section>

    <section v-if="page === 'teaching-classes'" class="card">
      <h2>创建教学班</h2>
      <form @submit.prevent="createTeachingClass">
        <label><span>行政班</span><select v-model="teachingClassForm.classId" required><option v-for="item in classes" :key="item.id" :value="item.id">{{ item.name }}</option></select></label>
        <label><span>课程</span><select v-model="teachingClassForm.courseId" required><option v-for="item in courses" :key="item.id" :value="item.id">{{ item.code }} · {{ item.name }}</option></select></label>
        <label><span>任课教师</span><select v-model="teachingClassForm.teacherId" required><option v-for="item in activeTeachers" :key="item.id" :value="item.id">{{ item.displayName }} · {{ item.email }}</option></select></label>
        <label><span>学期</span><input v-model="teachingClassForm.term" required maxlength="16" placeholder="例如：2026A" /></label>
        <button class="primary" type="submit" :disabled="!!saving || !canCreateTeachingClass">{{ saving === 'teachingClass' ? '创建中…' : '创建教学班' }}</button>
        <p v-if="!canCreateTeachingClass" class="meta">请先创建至少一个行政班、课程和教师账号。</p>
      </form>
    </section>

    <section v-if="page === 'classes'" class="card">
      <h2>导入学生名单（CSV）</h2>
      <p class="meta">列顺序：email,display_name,role,class_name,password。role 可省略（默认 student），password 可省略（系统生成初始密码）；班级名称须与上方行政班名称完全一致。未填写 class_name 的学生会自动加入当前选中的班级。</p>
      <label><span>选择 CSV 文件（最大 2 MiB）</span><input type="file" accept=".csv,text/csv" @change="readCsvFile" /></label>
      <label><span>或粘贴 CSV 内容</span><textarea v-model="csv" rows="7" placeholder="email,display_name,role,class_name,password&#10;student@example.edu,李同学,student,2028级应用数学2班," /></label>
      <button class="primary" type="button" :disabled="!!saving || !csv.trim()" @click="importStudents">{{ saving === 'import' ? '导入中…' : '导入学生' }}</button>

      <div v-if="importResult" class="import-results">
        <h3>导入结果：成功 {{ importResult.created }}，失败 {{ importResult.failed.length }}</h3>
        <p class="warn">初始密码只在此处展示。请安全保存并发放，离开页面后无法再次查询。</p>
        <table v-if="importResult.createdUsers.length">
          <thead><tr><th>行号</th><th>邮箱</th><th>角色</th><th>初始密码</th></tr></thead>
          <tbody><tr v-for="item in importResult.createdUsers" :key="`${item.line}-${item.email}`"><td>{{ item.line }}</td><td>{{ item.email }}</td><td>{{ item.role }}</td><td><code>{{ item.initialPassword }}</code></td></tr></tbody>
        </table>
        <table v-if="importResult.failed.length">
          <thead><tr><th>行号</th><th>失败原因</th></tr></thead>
          <tbody><tr v-for="item in importResult.failed" :key="`${item.line}-${item.reason}`"><td>{{ item.line }}</td><td>{{ item.reason }}</td></tr></tbody>
        </table>
      </div>
    </section>

    <section v-if="page === 'teaching-classes'" class="card">
      <h2>现有教学班</h2>
      <table v-if="teachingClasses.length">
        <thead><tr><th>班级</th><th>课程</th><th>教师</th><th>学期</th><th>学生数</th><th>操作</th></tr></thead>
        <tbody><tr v-for="item in teachingClasses" :key="item.id"><td>{{ item.className }}</td><td>{{ item.courseName }}</td><td>{{ item.teacherName }}</td><td>{{ item.term }}</td><td>{{ item.studentCount }}</td><td><button class="link" type="button" @click="deleteEntity(`/api/admin/teaching-classes/${item.id}`, '教学班')">删除</button></td></tr></tbody>
      </table>
      <p v-else class="empty">还没有教学班。</p>
    </section>
  </template>
</template>
