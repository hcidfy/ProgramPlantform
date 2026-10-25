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
    public static final String ADMIN_CREATE_CLASS = "admin.school_class.create";
    public static final String ADMIN_DELETE_CLASS = "admin.school_class.delete";
    public static final String ADMIN_CREATE_COURSE = "admin.course.create";
    public static final String ADMIN_DELETE_COURSE = "admin.course.delete";
    public static final String ADMIN_CREATE_TEACHING_CLASS = "admin.teaching_class.create";
    public static final String ADMIN_DELETE_TEACHING_CLASS = "admin.teaching_class.delete";
    public static final String ADMIN_ENROLL = "admin.enrollment.upsert";
    public static final String ADMIN_UNENROLL = "admin.enrollment.delete";
}
