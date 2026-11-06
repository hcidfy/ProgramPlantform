-- 行政班维护：记录班主任账号；既有班级暂时允许为空，管理员可后续补选。
ALTER TABLE school_classes
  ADD COLUMN IF NOT EXISTS homeroom_teacher_id uuid REFERENCES users(id) ON DELETE SET NULL;
