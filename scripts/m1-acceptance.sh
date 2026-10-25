#!/usr/bin/env bash
# M1 验收自验脚本（curl + python3）：把"当面演示的 6 条"跑一遍。
# 用法：./scripts/m1-acceptance.sh [http://127.0.0.1:8080]
# 前提：数据库干净（首次部署后立刻跑），否则会因"班级已存在"等 409 提前失败。
set -uo pipefail

BASE="${1:-http://127.0.0.1:8080}"
TMP="$(mktemp -d)"
BODY="$TMP/body.json"
trap 'rm -rf "$TMP"' EXIT

command -v curl >/dev/null 2>&1 || { echo "需要 curl"; exit 2; }
command -v python3 >/dev/null 2>&1 || { echo "需要 python3（用来解析 JSON）"; exit 2; }

PASS=0
FAIL=0
ok()    { PASS=$((PASS + 1)); printf '  [PASS] %s\n' "$1"; }
bad()   { FAIL=$((FAIL + 1)); printf '  [FAIL] %s\n' "$1"; }
head3() { printf '\n=== %s ===\n' "$1"; }

# 发请求，全局 CODE 保存状态码，响应体落 $BODY
req() {
  local method="$1" path="$2" token="${3:-}" data="${4:-}" ctype="${5:-application/json}"
  local args=(-sS -o "$BODY" -w '%{http_code}' -X "$method" "$BASE$path")
  if [ -n "$token" ]; then args+=(-H "Authorization: Bearer $token"); fi
  if [ -n "$data" ]; then args+=(-H "Content-Type: $ctype" --data-binary "$data"); fi
  CODE="$(curl "${args[@]}")"
}

# 取 JSON 字段：jget items.0.courseName
jget() {
  python3 - "$BODY" "$1" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding='utf-8'))
cur = data
for part in sys.argv[2].split('.'):
    if part == '':
        continue
    cur = cur[int(part)] if part.isdigit() else cur[part]
print('' if cur is None else cur)
PY
}

check() { # 名称 实际 期望
  if [ "$2" = "$3" ]; then ok "$1 = $2"; else bad "$1：期望 [$3]，实际 [$2]"; fi
}

ADMIN_EMAIL="${M1_ADMIN_EMAIL:-admin@example.edu}"
ADMIN_PASSWORD="${M1_ADMIN_PASSWORD:-Admin@12345}"
CLASS_NAME="2028级应用数学2班"

head3 "0. 服务健康"
req GET /healthz
check "healthz" "$CODE" "200"

head3 "1. 管理员登录"
req POST /api/auth/login "" "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}"
check "管理员登录" "$CODE" "200"
ADMIN_TOKEN="$(jget accessToken)"

head3 "2. 建 班级 × 课程 × 教师 = 教学班"
req POST /api/admin/school-classes "$ADMIN_TOKEN" "{\"name\":\"$CLASS_NAME\",\"gradeYear\":2028,\"major\":\"应用数学\"}"
check "建班级" "$CODE" "201"
CLASS_ID="$(jget id)"

req POST /api/admin/courses "$ADMIN_TOKEN" '{"code":"JAVA101","name":"Java程序设计","description":"Java 语言与程序设计基础"}'
check "建课程" "$CODE" "201"
COURSE_ID="$(jget id)"

req POST /api/admin/users "$ADMIN_TOKEN" '{"email":"zhang@example.edu","displayName":"张老师","role":"teacher","initialPassword":"Teacher@2026"}'
check "建教师账号" "$CODE" "201"
TEACHER_ID="$(jget id)"

req POST /api/admin/teaching-classes "$ADMIN_TOKEN" \
  "{\"classId\":\"$CLASS_ID\",\"courseId\":\"$COURSE_ID\",\"teacherId\":\"$TEACHER_ID\",\"term\":\"2026A\"}"
check "建教学班" "$CODE" "201"
TC_ID="$(jget id)"

head3 "3. 导入 5 个学生入班"
CSV="email,display_name,role,class_name
liyi@example.edu,李一,student,$CLASS_NAME
lier@example.edu,李二,student,$CLASS_NAME
lisan@example.edu,李三,student,$CLASS_NAME
lisi@example.edu,李四,student,$CLASS_NAME
liwu@example.edu,李五,student,$CLASS_NAME"
req POST /api/admin/users/import "$ADMIN_TOKEN" "$CSV" "text/csv"
check "导入返回" "$CODE" "200"
check "导入成功人数" "$(jget created)" "5"
STU_EMAIL="$(jget createdUsers.0.email)"
STU_PASSWORD="$(jget createdUsers.0.initialPassword)"

head3 "4. 学生登录 → 我的课程"
req POST /api/auth/login "" "{\"email\":\"$STU_EMAIL\",\"password\":\"$STU_PASSWORD\"}"
check "学生登录" "$CODE" "200"
STU_TOKEN="$(jget accessToken)"

req GET /api/student/courses "$STU_TOKEN"
check "我的课程" "$CODE" "200"
check "课程数量" "$(jget items | wc -l)" "1"
check "课程名" "$(jget items.0.courseName)" "Java程序设计"
check "教师名" "$(jget items.0.teacherName)" "张老师"

head3 "5. 教师登录 → 只看到自己的教学班"
req POST /api/auth/login "" '{"email":"zhang@example.edu","password":"Teacher@2026"}'
check "教师登录" "$CODE" "200"
TEA_TOKEN="$(jget accessToken)"
req GET /api/teacher/teaching-classes "$TEA_TOKEN"
check "教学班" "$CODE" "200"
check "学生数" "$(jget 0.studentCount)" "5"

head3 "6. 越权：学生换 id 试探别的教学班 / 学生调管理接口"
req POST /api/admin/school-classes "$ADMIN_TOKEN" '{"name":"2027级计算机1班","gradeYear":2027,"major":"计算机"}'
OTHER_CLASS="$(jget id)"
req POST /api/admin/courses "$ADMIN_TOKEN" '{"code":"PY101","name":"Python程序设计","description":"Python 基础"}'
OTHER_COURSE="$(jget id)"
req POST /api/admin/users "$ADMIN_TOKEN" '{"email":"wang@example.edu","displayName":"王老师","role":"teacher","initialPassword":"Teacher@2026"}'
OTHER_TEACHER="$(jget id)"
req POST /api/admin/teaching-classes "$ADMIN_TOKEN" \
  "{\"classId\":\"$OTHER_CLASS\",\"courseId\":\"$OTHER_COURSE\",\"teacherId\":\"$OTHER_TEACHER\",\"term\":\"2026A\"}"
OTHER_TC="$(jget id)"

req GET "/api/student/courses/$OTHER_TC" "$STU_TOKEN"
check "学生访问别班教学班 → 404" "$CODE" "404"
req GET /api/admin/users "$STU_TOKEN"
check "学生调管理接口 → 403" "$CODE" "403"
req GET /api/student/courses ""
check "未登录 → 401" "$CODE" "401"

printf '\n=========== 汇总：PASS %d / FAIL %d ===========\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
