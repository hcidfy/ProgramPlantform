package com.codelab.app.org;

import com.codelab.app.audit.AuditActions;
import com.codelab.app.audit.AuditService;
import com.codelab.app.auth.UserRepository;
import com.codelab.app.common.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 组织模型业务规则（PRD §2 / §13.3 / §13.4）：
 * 教师只能碰自己任课的教学班，学生只能看本班教学班；越权一律 404，不泄露存在性。
 */
@Service
public class OrgService {

    public record TeacherClassDetail(OrgRepository.TeachingClassRow teachingClass,
            List<OrgRepository.StudentRow> students) {
    }

    public record StudentCourses(boolean needClass, List<OrgRepository.TeachingClassRow> items) {
    }

    private final OrgRepository org;
    private final UserRepository users;
    private final AuditService audit;

    public OrgService(OrgRepository org, UserRepository users, AuditService audit) {
        this.org = org;
        this.users = users;
        this.audit = audit;
    }

    // ---------------- 管理端 ----------------

    public UUID createClass(UUID actorId, String name, Integer gradeYear, String major, UUID homeroomTeacherId) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw ApiException.validation("班级名称不能为空");
        }
        if (org.findClassByName(trimmed).isPresent()) {
            throw ApiException.conflict("CLASS_EXISTS", "班级已存在：" + trimmed);
        }
        UserRepository.UserRow teacher = users.findById(homeroomTeacherId)
                .orElseThrow(() -> ApiException.validation("班主任账号不存在"));
        if (!"teacher".equals(teacher.role()) || !teacher.isActive()) {
            throw ApiException.validation("班主任必须是启用状态的教师账号");
        }
        UUID id = org.insertClass(trimmed, gradeYear, major == null ? null : major.trim(), homeroomTeacherId);
        audit.record(actorId, AuditActions.ADMIN_CREATE_CLASS, "school_class:" + id,
                Map.of("name", trimmed, "gradeYear", gradeYear == null ? "" : gradeYear, "homeroomTeacherId", homeroomTeacherId));
        return id;
    }

    public UUID createCourse(UUID actorId, String code, String name, String description) {
        String c = code == null ? "" : code.trim().toUpperCase();
        if (c.isEmpty() || name == null || name.isBlank()) {
            throw ApiException.validation("课程编码与名称不能为空");
        }
        if (org.findCourseByCode(c).isPresent()) {
            throw ApiException.conflict("COURSE_EXISTS", "课程编码已存在：" + c);
        }
        UUID id = org.insertCourse(c, name.trim(), description == null ? null : description.trim());
        audit.record(actorId, AuditActions.ADMIN_CREATE_COURSE, "course:" + id, Map.of("code", c, "name", name.trim()));
        return id;
    }

    public UUID createTeachingClass(UUID actorId, UUID classId, UUID courseId, UUID teacherId, String term) {
        if (classId == null || courseId == null || teacherId == null || term == null || term.isBlank()) {
            throw ApiException.validation("班级、课程、任课教师、学期均为必填");
        }
        org.findClassById(classId).orElseThrow(() -> ApiException.validation("班级不存在"));
        org.findCourseById(courseId).orElseThrow(() -> ApiException.validation("课程不存在"));
        UserRepository.UserRow teacher = users.findById(teacherId)
                .orElseThrow(() -> ApiException.validation("教师账号不存在"));
        if (!"teacher".equals(teacher.role()) || !teacher.isActive()) {
            throw ApiException.validation("任课教师必须是启用状态的教师账号：" + teacher.email());
        }
        if (org.teachingClassExists(classId, courseId, term.trim())) {
            throw ApiException.conflict("TEACHING_CLASS_EXISTS", "同一班级同一课程同一学期已存在教学班");
        }
        UUID id = org.insertTeachingClass(classId, courseId, teacherId, term.trim());
        audit.record(actorId, AuditActions.ADMIN_CREATE_TEACHING_CLASS, "teaching_class:" + id,
                Map.of("classId", classId, "courseId", courseId, "teacherId", teacherId, "term", term.trim()));
        return id;
    }

    public void enroll(UUID actorId, UUID studentId, UUID classId) {
        if (studentId == null || classId == null) {
            throw ApiException.validation("学生与班级均为必填");
        }
        UserRepository.UserRow student = users.findById(studentId)
                .orElseThrow(() -> ApiException.validation("学生账号不存在"));
        if (!"student".equals(student.role())) {
            throw ApiException.validation("该账号不是学生：" + student.email());
        }
        org.findClassById(classId).orElseThrow(() -> ApiException.validation("班级不存在"));
        org.upsertEnrollment(studentId, classId);
        audit.record(actorId, AuditActions.ADMIN_ENROLL, "enrollment:" + studentId,
                Map.of("studentId", studentId, "classId", classId));
    }

    public List<OrgRepository.TeachingClassRow> allTeachingClasses() {
        return org.listAllTeachingClasses();
    }

    public List<OrgRepository.SchoolClassRow> allClasses() {
        return org.listClasses();
    }

    public List<OrgRepository.CourseRow> allCourses() {
        return org.listCourses();
    }

    public List<OrgRepository.StudentRow> studentsInClass(UUID classId) {
        return org.listStudentsInClass(classId);
    }

    /** 删班级：下面还有教学班或学生就不许删，避免留下孤儿数据。 */
    public void deleteClass(UUID actorId, UUID classId) {
        org.findClassById(classId).orElseThrow(() -> ApiException.notFound("班级不存在"));
        long teachingClasses = org.countTeachingClassesByClass(classId);
        long students = org.countEnrollmentsByClass(classId);
        if (teachingClasses > 0 || students > 0) {
            throw ApiException.conflict("CLASS_IN_USE",
                    "该班级还有 " + teachingClasses + " 个教学班、" + students + " 名学生，先解除绑定再删");
        }
        org.deleteClass(classId);
        audit.record(actorId, AuditActions.ADMIN_DELETE_CLASS, "school_class:" + classId, Map.of("classId", classId));
    }

    public void deleteCourse(UUID actorId, UUID courseId) {
        org.findCourseById(courseId).orElseThrow(() -> ApiException.notFound("课程不存在"));
        long teachingClasses = org.countTeachingClassesByCourse(courseId);
        long history = org.countCourseHistory(courseId);
        if (teachingClasses > 0 || history > 0) {
            throw ApiException.conflict("COURSE_IN_USE", "该课程仍有 " + teachingClasses + " 个教学班或 " + history + " 条题目/知识/问答历史，不能删除");
        }
        org.deleteCourse(courseId);
        audit.record(actorId, AuditActions.ADMIN_DELETE_COURSE, "course:" + courseId, Map.of("courseId", courseId));
    }

    public void deleteTeachingClass(UUID actorId, UUID teachingClassId) {
        org.findTeachingClassById(teachingClassId).orElseThrow(() -> ApiException.notFound("教学班不存在"));
        long assignments = org.countAssignmentsByTeachingClass(teachingClassId);
        if (assignments > 0) {
            throw ApiException.conflict("TEACHING_CLASS_IN_USE", "该教学班已有 " + assignments + " 份作业，不能删除；请保留历史教学数据");
        }
        org.deleteTeachingClass(teachingClassId);
        audit.record(actorId, AuditActions.ADMIN_DELETE_TEACHING_CLASS, "teaching_class:" + teachingClassId,
                Map.of("teachingClassId", teachingClassId));
    }

    /** 退班：学生回到"无班级"状态（再登录会看到 needClass 提示）。 */
    public void unenroll(UUID actorId, UUID studentId) {
        if (org.deleteEnrollmentByStudent(studentId) == 0) {
            throw ApiException.notFound("该学生当前没有班级");
        }
        audit.record(actorId, AuditActions.ADMIN_UNENROLL, "enrollment:" + studentId, Map.of("studentId", studentId));
    }

    // ---------------- 教师端 ----------------

    public List<OrgRepository.TeachingClassRow> teacherClasses(UUID teacherId) {
        return org.listTeachingClassesByTeacher(teacherId);
    }

    public TeacherClassDetail teacherClassDetail(UUID teacherId, UUID teachingClassId) {
        OrgRepository.TeachingClassRow tc = org.findTeachingClassForTeacher(teachingClassId, teacherId)
                .orElseThrow(() -> ApiException.notFound("教学班不存在"));
        return new TeacherClassDetail(tc, org.listStudentsInClass(tc.classId()));
    }

    // ---------------- 学生端 ----------------

    public StudentCourses studentCourses(UUID studentId) {
        boolean needClass = org.findClassIdByStudent(studentId).isEmpty();
        return new StudentCourses(needClass, org.listTeachingClassesForStudent(studentId));
    }

    public OrgRepository.TeachingClassRow studentCourse(UUID studentId, UUID teachingClassId) {
        return org.findTeachingClassForStudent(teachingClassId, studentId)
                .orElseThrow(() -> ApiException.notFound("课程不存在"));
    }
}
