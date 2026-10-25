package com.codelab.app.security;

import java.util.UUID;

/** 登录主体的最小信息量：身份 + 角色 + 会话（登出即失效靠 sid 校验）。 */
public record CurrentUser(UUID id, String role, UUID sessionId) {

    public boolean isStudent() {
        return "student".equals(role);
    }

    public boolean isTeacher() {
        return "teacher".equals(role);
    }

    public boolean isAdmin() {
        return "admin".equals(role);
    }
}
