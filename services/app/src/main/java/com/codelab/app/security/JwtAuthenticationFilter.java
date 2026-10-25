package com.codelab.app.security;

import com.codelab.app.auth.AuthSessionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 解析 Authorization: Bearer &lt;access token&gt;。
 * <p>除了验签，还会确认会话未被撤销——这样"登出即失效"（FR-ACC-02）不必等 access token 自然过期。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER = "Bearer ";

    private final JwtService jwt;
    private final AuthSessionRepository sessions;

    public JwtAuthenticationFilter(JwtService jwt, AuthSessionRepository sessions) {
        this.jwt = jwt;
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            jwt.verify(header.substring(BEARER.length())).ifPresentOrElse(token -> {
                boolean active;
                try {
                    active = sessions.isActive(token.sessionId());
                } catch (RuntimeException ex) {
                    log.warn("会话状态查询失败，按未登录处理: {}", ex.getMessage());
                    active = false;
                }
                if (active) {
                    CurrentUser principal = new CurrentUser(token.userId(), token.role(), token.sessionId());
                    var authentication = new UsernamePasswordAuthenticationToken(principal, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + token.role().toUpperCase())));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }, () -> log.debug("access token 无效或过期"));
        }
        chain.doFilter(request, response);
    }
}
