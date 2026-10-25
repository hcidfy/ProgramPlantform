-- V1__init.sql —— M1 初始表结构：身份域（users / auth_sessions / audit_logs）+ 组织域（school_classes / courses / teaching_classes / enrollments）
-- 要求：可重复执行。应用启动时由迁移器按文件名升序执行，已执行版本跳过（见 PRD §13.6）。
-- 本文件自身也写成幂等：IF NOT EXISTS，直接 psql -f 反复跑也不会报错。

CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ========== 账号 ==========
CREATE TABLE IF NOT EXISTS users (
  id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email                 citext NOT NULL UNIQUE,
  display_name          varchar(64) NOT NULL,
  password_hash         text NOT NULL,                       -- Argon2id
  role                  varchar(16) NOT NULL DEFAULT 'student'
                        CHECK (role IN ('student','teacher','admin')),
  status                varchar(16) NOT NULL DEFAULT 'pending'
                        CHECK (status IN ('pending','active','suspended')),
  failed_login_attempts int NOT NULL DEFAULT 0,
  locked_until          timestamptz,
  last_login_at         timestamptz,
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS users_role_idx ON users (role);

-- ========== 会话（refresh token 只存摘要）==========
CREATE TABLE IF NOT EXISTS auth_sessions (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  refresh_hash text NOT NULL UNIQUE,
  device       text,
  created_at   timestamptz NOT NULL DEFAULT now(),
  expires_at   timestamptz NOT NULL,
  revoked_at   timestamptz
);

CREATE INDEX IF NOT EXISTS auth_sessions_user_idx ON auth_sessions (user_id);

-- ========== 组织：班级 / 课程 / 教学班 / 入班 ==========
CREATE TABLE IF NOT EXISTS school_classes (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name       varchar(64) NOT NULL UNIQUE,          -- 2028级应用数学2班
  grade_year int,
  major      varchar(64),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS courses (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code        varchar(32) NOT NULL UNIQUE,         -- JAVA101
  name        varchar(128) NOT NULL,               -- Java程序设计
  description text,
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS teaching_classes (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  class_id   uuid NOT NULL REFERENCES school_classes(id) ON DELETE RESTRICT,
  course_id  uuid NOT NULL REFERENCES courses(id) ON DELETE RESTRICT,
  teacher_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  term       varchar(16) NOT NULL,                 -- 2026A
  status     varchar(16) NOT NULL DEFAULT 'active'
             CHECK (status IN ('active','archived')),
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT teaching_classes_unique UNIQUE (class_id, course_id, term)
);

CREATE INDEX IF NOT EXISTS teaching_classes_teacher_idx ON teaching_classes (teacher_id);

CREATE TABLE IF NOT EXISTS enrollments (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  student_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  class_id   uuid NOT NULL REFERENCES school_classes(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT enrollments_student_unique UNIQUE (student_id)   -- MVP 单班
);

CREATE INDEX IF NOT EXISTS enrollments_class_idx ON enrollments (class_id);

-- ========== 审计（只追加）==========
CREATE TABLE IF NOT EXISTS audit_logs (
  id         bigserial PRIMARY KEY,
  actor_id   uuid REFERENCES users(id) ON DELETE SET NULL,
  action     varchar(64) NOT NULL,
  resource   varchar(128),
  detail     jsonb,
  ip         inet,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS audit_logs_created_idx ON audit_logs (created_at DESC);
CREATE INDEX IF NOT EXISTS audit_logs_actor_idx ON audit_logs (actor_id, created_at DESC);

-- ========== 迁移器自身表（应用启动时建；此处兜底）==========
CREATE TABLE IF NOT EXISTS schema_migrations (
  version    text PRIMARY KEY,
  applied_at timestamptz NOT NULL DEFAULT now()
);
