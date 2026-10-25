package com.codelab.app.security;

import com.codelab.app.common.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static CurrentUser require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentUser cu) {
            return cu;
        }
        throw ApiException.unauthorized("请先登录");
    }

    public static CurrentUser requireRole(String role) {
        CurrentUser cu = require();
        if (!role.equals(cu.role())) {
            throw ApiException.forbidden("该操作需要 " + role + " 角色");
        }
        return cu;
    }
}
