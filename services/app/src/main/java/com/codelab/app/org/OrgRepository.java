package com.codelab.app.org;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 组织域（班级 / 课程 / 教学班 / 入班）SQL。 */
@Repository
public class OrgRepository {

    public record SchoolClassRow(UUID id, String name, Integer gradeYear, String major) {
    }

    public record CourseRow(UUID id, String code, String name, String description) {
    }

    public record TeachingClassRow(UUID id, UUID classId, String className, Integer gradeYear,
            UUID courseId, String courseCode, String courseName, String courseDescription,
            UUID teacherId, String teacherName, String term, String status, long studentCount) {
    }

    public record StudentRow(UUID id, String email, String displayName) {
    }

    private static final String TEACHING_CLASS_SELECT = """
            SELECT tc.id, tc.class_id, sc.name AS class_name, sc.grade_year,
                   tc.course_id, c.code AS course_code, c.name AS course_name, c.description AS course_description,
                   tc.teacher_id, u.display_name AS teacher_name, tc.term, tc.status,
                   (SELECT count(*) FROM enrollments e WHERE e.class_id = tc.class_id) AS student_count
              FROM teaching_classes tc
              JOIN school_classes sc ON sc.id = tc.class_id
              JOIN courses c ON c.id = tc.course_id
              JOIN users u ON u.id = tc.teacher_id
            """;

    private static final RowMapper<TeachingClassRow> TC_MAPPER = (rs, i) -> new TeachingClassRow(
            rs.getObject("id", UUID.class),
            rs.getObject("class_id", UUID.class),
            rs.getString("class_name"),
            rs.getObject("grade_year", Integer.class),
            rs.getObject("course_id", UUID.class),
            rs.getString("course_code"),
            rs.getString("course_name"),
            rs.getString("course_description"),
            rs.getObject("teacher_id", UUID.class),
            rs.getString("teacher_name"),
            rs.getString("term"),
            rs.getString("status"),
            rs.getLong("student_count"));

    private final JdbcClient jdbc;

    public OrgRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 学校班级 ----------------

    public List<SchoolClassRow> listClasses() {
        return jdbc.sql("SELECT id, name, grade_year, major FROM school_classes ORDER BY name")
                .query((rs, i) -> new SchoolClassRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getObject("grade_year", Integer.class), rs.getString("major")))
                .list();
    }

    public Optional<SchoolClassRow> findClassById(UUID id) {
        return jdbc.sql("SELECT id, name, grade_year, major FROM school_classes WHERE id = :id")
                .param("id", id)
                .query((rs, i) -> new SchoolClassRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getObject("grade_year", Integer.class), rs.getString("major")))
                .optional();
    }

    public Optional<SchoolClassRow> findClassByName(String name) {
        return jdbc.sql("SELECT id, name, grade_year, major FROM school_classes WHERE name = :name")
                .param("name", name)
                .query((rs, i) -> new SchoolClassRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getObject("grade_year", Integer.class), rs.getString("major")))
                .optional();
    }

    public UUID insertClass(String name, Integer gradeYear, String major) {
        return jdbc.sql("""
                        INSERT INTO school_classes (name, grade_year, major)
                        VALUES (:name, :gradeYear, :major) RETURNING id
                        """)
                .param("name", name)
                .param("gradeYear", gradeYear)
                .param("major", major)
                .query(UUID.class)
                .single();
    }

    // ---------------- 课程 ----------------

    public List<CourseRow> listCourses() {
        return jdbc.sql("SELECT id, code, name, description FROM courses ORDER BY code")
                .query((rs, i) -> new CourseRow(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getString("description")))
                .list();
    }

    public Optional<CourseRow> findCourseById(UUID id) {
        return jdbc.sql("SELECT id, code, name, description FROM courses WHERE id = :id")
                .param("id", id)
                .query((rs, i) -> new CourseRow(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getString("description")))
                .optional();
    }

    public Optional<CourseRow> findCourseByCode(String code) {
        return jdbc.sql("SELECT id, code, name, description FROM courses WHERE code = :code")
                .param("code", code)
                .query((rs, i) -> new CourseRow(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getString("description")))
                .optional();
    }

    public UUID insertCourse(String code, String name, String description) {
        return jdbc.sql("""
                        INSERT INTO courses (code, name, description)
                        VALUES (:code, :name, :description) RETURNING id
                        """)
                .param("code", code)
                .param("name", name)
                .param("description", description)
                .query(UUID.class)
                .single();
    }

    // ---------------- 教学班 ----------------

    public UUID insertTeachingClass(UUID classId, UUID courseId, UUID teacherId, String term) {
        return jdbc.sql("""
                        INSERT INTO teaching_classes (class_id, course_id, teacher_id, term)
                        VALUES (:classId, :courseId, :teacherId, :term) RETURNING id
                        """)
                .param("classId", classId)
                .param("courseId", courseId)
                .param("teacherId", teacherId)
                .param("term", term)
                .query(UUID.class)
                .single();
    }

    public boolean teachingClassExists(UUID classId, UUID courseId, String term) {
        return jdbc.sql("""
                        SELECT 1 FROM teaching_classes
                         WHERE class_id = :classId AND course_id = :courseId AND term = :term
                        """)
                .param("classId", classId)
                .param("courseId", courseId)
                .param("term", term)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    public List<TeachingClassRow> listAllTeachingClasses() {
        return jdbc.sql(TEACHING_CLASS_SELECT + " ORDER BY sc.name, c.name").query(TC_MAPPER).list();
    }

    public List<TeachingClassRow> listTeachingClassesByTeacher(UUID teacherId) {
        return jdbc.sql(TEACHING_CLASS_SELECT + " WHERE tc.teacher_id = :teacherId ORDER BY sc.name, c.name")
                .param("teacherId", teacherId)
                .query(TC_MAPPER)
                .list();
    }

    public List<TeachingClassRow> listTeachingClassesForStudent(UUID studentId) {
        return jdbc.sql(TEACHING_CLASS_SELECT + """
                 WHERE tc.class_id IN (SELECT e.class_id FROM enrollments e WHERE e.student_id = :studentId)
                 ORDER BY c.name
                """)
                .param("studentId", studentId)
                .query(TC_MAPPER)
                .list();
    }

    public Optional<TeachingClassRow> findTeachingClassById(UUID id) {
        return jdbc.sql(TEACHING_CLASS_SELECT + " WHERE tc.id = :id")
                .param("id", id)
                .query(TC_MAPPER)
                .optional();
    }

    public Optional<TeachingClassRow> findTeachingClassForTeacher(UUID id, UUID teacherId) {
        return jdbc.sql(TEACHING_CLASS_SELECT + " WHERE tc.id = :id AND tc.teacher_id = :teacherId")
                .param("id", id)
                .param("teacherId", teacherId)
                .query(TC_MAPPER)
                .optional();
    }

    public Optional<TeachingClassRow> findTeachingClassForStudent(UUID id, UUID studentId) {
        return jdbc.sql(TEACHING_CLASS_SELECT + """
                 WHERE tc.id = :id
                   AND tc.class_id IN (SELECT e.class_id FROM enrollments e WHERE e.student_id = :studentId)
                """)
                .param("id", id)
                .param("studentId", studentId)
                .query(TC_MAPPER)
                .optional();
    }

    // ---------------- 入班 ----------------

    public void upsertEnrollment(UUID studentId, UUID classId) {
        jdbc.sql("""
                        INSERT INTO enrollments (student_id, class_id)
                        VALUES (:studentId, :classId)
                        ON CONFLICT (student_id) DO UPDATE SET class_id = EXCLUDED.class_id
                        """)
                .param("studentId", studentId)
                .param("classId", classId)
                .update();
    }

    public Optional<UUID> findClassIdByStudent(UUID studentId) {
        return jdbc.sql("SELECT class_id FROM enrollments WHERE student_id = :studentId")
                .param("studentId", studentId)
                .query(UUID.class)
                .optional();
    }

    public List<StudentRow> listStudentsInClass(UUID classId) {
        return jdbc.sql("""
                        SELECT u.id, u.email, u.display_name
                          FROM enrollments e
                          JOIN users u ON u.id = e.student_id
                         WHERE e.class_id = :classId
                         ORDER BY u.display_name
                        """)
                .param("classId", classId)
                .query((rs, i) -> new StudentRow(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("display_name")))
                .list();
    }

    // ---------------- 教师/课程范围（M2 题库权限用） ----------------

    public List<UUID> listCourseIdsByTeacher(UUID teacherId) {
        return jdbc.sql("SELECT DISTINCT course_id FROM teaching_classes WHERE teacher_id = :teacherId")
                .param("teacherId", teacherId)
                .query(UUID.class)
                .list();
    }

    public java.util.List<UUID> listTeachingClassIdsByTeacher(UUID teacherId) {
        return jdbc.sql("SELECT id FROM teaching_classes WHERE teacher_id = :teacherId ORDER BY created_at")
                .param("teacherId", teacherId)
                .query(UUID.class)
                .list();
    }

    public boolean teacherTeachesCourse(UUID teacherId, UUID courseId) {
        return jdbc.sql("SELECT 1 FROM teaching_classes WHERE teacher_id = :teacherId AND course_id = :courseId")
                .param("teacherId", teacherId)
                .param("courseId", courseId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /** 学生是否在其行政班的某门课程教学班里（S2 问答的权限边界）。 */
    public boolean studentHasCourse(UUID studentId, UUID courseId) {
        return jdbc.sql("""
                        SELECT 1
                          FROM teaching_classes tc
                          JOIN enrollments e ON e.class_id = tc.class_id
                         WHERE e.student_id = :studentId AND tc.course_id = :courseId
                         LIMIT 1
                        """)
                .param("studentId", studentId)
                .param("courseId", courseId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /** 学生所在行政班 × 题目所属课程 → 教学班（配额按教学班覆盖时用）。 */
    public Optional<UUID> findTeachingClassIdByStudentAndCourse(UUID studentId, UUID courseId) {
        return jdbc.sql("""
                        SELECT tc.id
                          FROM teaching_classes tc
                          JOIN enrollments e ON e.class_id = tc.class_id
                         WHERE e.student_id = :studentId AND tc.course_id = :courseId
                         LIMIT 1
                        """)
                .param("studentId", studentId)
                .param("courseId", courseId)
                .query(UUID.class)
                .optional();
    }

    public Optional<UUID> findCourseIdByTeachingClass(UUID teachingClassId) {
        return jdbc.sql("SELECT course_id FROM teaching_classes WHERE id = :id")
                .param("id", teachingClassId)
                .query(UUID.class)
                .optional();
    }

    public boolean teacherOwnsTeachingClass(UUID teacherId, UUID teachingClassId) {
        return jdbc.sql("SELECT 1 FROM teaching_classes WHERE id = :tcId AND teacher_id = :teacherId")
                .param("tcId", teachingClassId)
                .param("teacherId", teacherId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /** 学生是否属于该教学班对应的行政班。 */
    public boolean studentInTeachingClass(UUID studentId, UUID teachingClassId) {
        return jdbc.sql("""
                        SELECT 1
                          FROM teaching_classes tc
                          JOIN enrollments e ON e.class_id = tc.class_id
                         WHERE tc.id = :tcId AND e.student_id = :studentId
                        """)
                .param("tcId", teachingClassId)
                .param("studentId", studentId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    // ---------------- 删除 / 退班 ----------------

    public long countTeachingClassesByClass(UUID classId) {
        return jdbc.sql("SELECT count(*) FROM teaching_classes WHERE class_id = :classId")
                .param("classId", classId)
                .query(Long.class)
                .single();
    }

    public long countEnrollmentsByClass(UUID classId) {
        return jdbc.sql("SELECT count(*) FROM enrollments WHERE class_id = :classId")
                .param("classId", classId)
                .query(Long.class)
                .single();
    }

    public long countTeachingClassesByCourse(UUID courseId) {
        return jdbc.sql("SELECT count(*) FROM teaching_classes WHERE course_id = :courseId")
                .param("courseId", courseId)
                .query(Long.class)
                .single();
    }

    /** 返回受影响行数（0 = 已不存在）。 */
    public int deleteClass(UUID id) {
        return jdbc.sql("DELETE FROM school_classes WHERE id = :id").param("id", id).update();
    }

    public int deleteCourse(UUID id) {
        return jdbc.sql("DELETE FROM courses WHERE id = :id").param("id", id).update();
    }

    public int deleteTeachingClass(UUID id) {
        return jdbc.sql("DELETE FROM teaching_classes WHERE id = :id").param("id", id).update();
    }

    /** 退班（一个学生只有一个班，按学生删）。 */
    public int deleteEnrollmentByStudent(UUID studentId) {
        return jdbc.sql("DELETE FROM enrollments WHERE student_id = :studentId")
                .param("studentId", studentId)
                .update();
    }
}
