-- V4：M4 配额/用量看板与 AI 回答教师复核。
-- 幂等：应用启动迁移器可能在重试时再次执行。

-- 课程归属使管理端可对 AI 调用做课程聚合（S1 从题目回填，S2 写入 course_id）。
ALTER TABLE ai_calls
  ADD COLUMN IF NOT EXISTS course_id uuid REFERENCES courses(id) ON DELETE SET NULL;

UPDATE ai_calls c
   SET course_id = p.course_id
  FROM problems p
 WHERE c.problem_id = p.id AND c.course_id IS NULL;

CREATE INDEX IF NOT EXISTS ai_calls_course_created_idx ON ai_calls (course_id, created_at DESC);

-- NULL scope_id 表示全局配额；普通 UNIQUE 不限制多个 NULL，使用表达式索引固定唯一性。
CREATE UNIQUE INDEX IF NOT EXISTS ai_quotas_scope_key_idx
  ON ai_quotas (scope, coalesce(scope_id, '00000000-0000-0000-0000-000000000000'::uuid));

ALTER TABLE ai_feedback
  ADD COLUMN IF NOT EXISTS reason varchar(24),
  ADD COLUMN IF NOT EXISTS comment varchar(500),
  ADD COLUMN IF NOT EXISTS status varchar(16) NOT NULL DEFAULT 'not_required',
  ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

CREATE UNIQUE INDEX IF NOT EXISTS ai_feedback_message_user_idx
  ON ai_feedback (message_id, user_id) WHERE message_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ai_feedback_status_created_idx ON ai_feedback (status, created_at DESC);

CREATE TABLE IF NOT EXISTS ai_feedback_reviews (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  feedback_id  uuid NOT NULL REFERENCES ai_feedback(id) ON DELETE CASCADE,
  reviewer_id  uuid NOT NULL REFERENCES users(id),
  verdict      varchar(24) NOT NULL CHECK (verdict IN ('correct','partially_correct','incorrect','unverifiable')),
  explanation  varchar(1000) NOT NULL,
  correction   varchar(2000),
  created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ai_feedback_reviews_feedback_idx
  ON ai_feedback_reviews (feedback_id, created_at DESC);
