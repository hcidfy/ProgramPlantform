-- V2__m2_problem_assignment_judge.sql —— M2：题库 / 用例 / 作业 / 提交 / 判题结果 / 判题队列 / 文件
-- 幂等：可重复执行。字段与口径见 PRD §14。

-- ========== 题库 ==========
CREATE TABLE IF NOT EXISTS problems (
  id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  course_id           uuid NOT NULL REFERENCES courses(id) ON DELETE RESTRICT,
  title               varchar(200) NOT NULL,
  statement_md        text NOT NULL,
  language            varchar(16) NOT NULL DEFAULT 'java'
                      CHECK (language IN ('java','python3')),
  difficulty          varchar(16) NOT NULL DEFAULT 'medium'
                      CHECK (difficulty IN ('easy','medium','hard')),
  time_limit_ms       int NOT NULL DEFAULT 2000
                      CHECK (time_limit_ms BETWEEN 100 AND 10000),
  mem_limit_kb        int NOT NULL DEFAULT 262144
                      CHECK (mem_limit_kb BETWEEN 16384 AND 1048576),
  initial_code        text,
  status              varchar(16) NOT NULL DEFAULT 'draft'
                      CHECK (status IN ('draft','published','archived')),
  version             int NOT NULL DEFAULT 1,
  previous_version_id uuid REFERENCES problems(id) ON DELETE SET NULL,
  created_by          uuid NOT NULL REFERENCES users(id),
  published_at        timestamptz,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS problems_course_status_idx ON problems (course_id, status);
CREATE INDEX IF NOT EXISTS problems_language_idx ON problems (language);

-- ========== 用例 ==========
CREATE TABLE IF NOT EXISTS test_cases (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  problem_id uuid NOT NULL REFERENCES problems(id) ON DELETE CASCADE,
  seq        int NOT NULL,
  input      text NOT NULL DEFAULT '',
  expected   text NOT NULL,
  is_hidden  boolean NOT NULL DEFAULT false,
  score      int NOT NULL DEFAULT 0,
  CONSTRAINT test_cases_problem_seq_unique UNIQUE (problem_id, seq)
);

-- ========== 作业 ==========
CREATE TABLE IF NOT EXISTS assignments (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  teaching_class_id uuid NOT NULL REFERENCES teaching_classes(id) ON DELETE CASCADE,
  title             varchar(200) NOT NULL,
  description       text,
  deadline          timestamptz,
  status            varchar(16) NOT NULL DEFAULT 'draft'
                    CHECK (status IN ('draft','published','closed')),
  created_by        uuid NOT NULL REFERENCES users(id),
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS assignments_tc_idx ON assignments (teaching_class_id, deadline);

CREATE TABLE IF NOT EXISTS assignment_problems (
  assignment_id uuid NOT NULL REFERENCES assignments(id) ON DELETE CASCADE,
  problem_id    uuid NOT NULL REFERENCES problems(id) ON DELETE RESTRICT,
  seq           int NOT NULL DEFAULT 1,
  score         int NOT NULL DEFAULT 0,
  PRIMARY KEY (assignment_id, problem_id)
);

-- ========== 提交 ==========
CREATE TABLE IF NOT EXISTS submissions (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  problem_id      uuid NOT NULL REFERENCES problems(id) ON DELETE CASCADE,
  assignment_id   uuid REFERENCES assignments(id) ON DELETE SET NULL,
  language        varchar(16) NOT NULL,
  source_code     text NOT NULL,
  status          varchar(16) NOT NULL DEFAULT 'queued'
                  CHECK (status IN ('queued','judging','done','judge_error')),
  result          varchar(24)
                  CHECK (result IS NULL OR result IN
                         ('accepted','wrong_answer','compile_error','runtime_error','timeout')),
  passed_cases    int NOT NULL DEFAULT 0,
  total_cases     int NOT NULL DEFAULT 0,
  score           int NOT NULL DEFAULT 0,
  compile_output  text,
  idempotency_key varchar(64) UNIQUE,
  rejudge_of      uuid REFERENCES submissions(id) ON DELETE SET NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  finished_at     timestamptz
);

CREATE INDEX IF NOT EXISTS submissions_user_problem_idx ON submissions (user_id, problem_id, created_at DESC);
CREATE INDEX IF NOT EXISTS submissions_assignment_idx ON submissions (assignment_id, user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS submissions_problem_idx ON submissions (problem_id, created_at DESC);

-- ========== 判题逐用例结果 ==========
CREATE TABLE IF NOT EXISTS judge_case_results (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  submission_id    uuid NOT NULL REFERENCES submissions(id) ON DELETE CASCADE,
  case_seq         int NOT NULL,
  status           varchar(16) NOT NULL,           -- ac / wa / tle / re
  time_ms          int NOT NULL DEFAULT 0,
  exit_code        int,
  input_excerpt    text,
  expected_excerpt text,
  actual_excerpt   text,
  is_hidden        boolean NOT NULL DEFAULT false,
  CONSTRAINT judge_case_results_unique UNIQUE (submission_id, case_seq)
);

-- ========== 判题队列（D1：PG 队列，替代 judge.task 主题）==========
CREATE TABLE IF NOT EXISTS judge_queue (
  id            bigserial PRIMARY KEY,
  submission_id uuid NOT NULL UNIQUE REFERENCES submissions(id) ON DELETE CASCADE,
  status        varchar(16) NOT NULL DEFAULT 'queued'
                CHECK (status IN ('queued','running','done','failed')),
  attempts      int NOT NULL DEFAULT 0,
  last_error    text,
  enqueued_at   timestamptz NOT NULL DEFAULT now(),
  started_at    timestamptz,
  finished_at   timestamptz
);

CREATE INDEX IF NOT EXISTS judge_queue_status_idx ON judge_queue (status, enqueued_at);

-- ========== 文件（题目图片/附件）==========
CREATE TABLE IF NOT EXISTS files (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  problem_id    uuid REFERENCES problems(id) ON DELETE CASCADE,
  purpose       varchar(24) NOT NULL DEFAULT 'problem_image',
  object_key    text NOT NULL,
  original_name varchar(200),
  mime          varchar(64) NOT NULL,
  size_bytes    int NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS files_problem_idx ON files (problem_id);
CREATE INDEX IF NOT EXISTS files_owner_idx ON files (owner_id);
