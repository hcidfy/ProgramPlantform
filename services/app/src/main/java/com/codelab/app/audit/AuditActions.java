package com.codelab.app.audit;

/** 审计动作名集中登记，避免各控制器随手写字符串导致检索困难。 */
public final class AuditActions {

    private AuditActions() {
    }

    public static final String REGISTER = "auth.register";
    public static final String LOGIN_SUCCESS = "auth.login.success";
    public static final String LOGIN_FAILED = "auth.login.failed";
    public static final String LOGOUT = "auth.logout";

    public static final String ADMIN_CREATE_USER = "admin.user.create";
    public static final String ADMIN_IMPORT_USERS = "admin.user.import";
    public static final String ADMIN_USER_STATUS_UPDATE = "admin.user_status_update";
    public static final String ADMIN_CREATE_CLASS = "admin.school_class.create";
    public static final String ADMIN_DELETE_CLASS = "admin.school_class.delete";
    public static final String ADMIN_CREATE_COURSE = "admin.course.create";
    public static final String ADMIN_DELETE_COURSE = "admin.course.delete";
    public static final String ADMIN_CREATE_TEACHING_CLASS = "admin.teaching_class.create";
    public static final String ADMIN_DELETE_TEACHING_CLASS = "admin.teaching_class.delete";
    public static final String ADMIN_ENROLL = "admin.enrollment.upsert";
    public static final String ADMIN_UNENROLL = "admin.enrollment.delete";
    public static final String ADMIN_AI_QUOTA_UPDATE = "admin.ai_quota_update";

    public static final String PROBLEM_CREATE = "teacher.problem.create";
    public static final String PROBLEM_PUBLISH = "teacher.problem.publish";
    public static final String PROBLEM_NEW_VERSION = "teacher.problem.new_version";
    public static final String ASSIGNMENT_CREATE = "teacher.assignment.create";
    public static final String ASSIGNMENT_PUBLISH = "teacher.assignment.publish";
    public static final String STUDENT_SUBMIT = "student.submission.create";
    public static final String TEACHER_VIEW_CODE = "teacher.view_code";
    public static final String TEACHER_REJUDGE = "teacher.rejudge";

    public static final String AI_CALL = "ai.call";
    public static final String AI_QUOTA_EXCEEDED = "ai.quota.exceeded";
    public static final String KNOWLEDGE_CREATE = "teacher.knowledge.create";
    public static final String KNOWLEDGE_UPDATE = "teacher.knowledge.update";
    public static final String KNOWLEDGE_PUBLISH = "teacher.knowledge.publish";
    public static final String KNOWLEDGE_DELETE = "teacher.knowledge.delete";
    public static final String TEACHER_VIEW_QA = "teacher.view_qa";
}
