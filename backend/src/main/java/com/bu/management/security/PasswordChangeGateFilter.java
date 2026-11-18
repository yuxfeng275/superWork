package com.bu.management.security;

import com.bu.management.entity.User;
import com.bu.management.mapper.UserMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 强制改密门禁：管理员重置密码后，用户只能访问认证/健康接口，业务接口一律 403。
 *
 * <p>前端登录后已经会跳改密页；本过滤器是服务端兜底，避免绕过页面直接调业务 API。
 * 只拦已认证的 {@code /api/**} 请求；认证接口、健康检查、微信回调放行。
 *
 * <p>DB 读失败按放行处理（fail-open）：不能让一次数据库抖动把全体用户锁死；
 * 这种情况下前端仍会引导改密，风险由前端 + 重置流程兜底。
 *
 * @author BU Team
 * @since 2026-11-18
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PasswordChangeGateFilter extends OncePerRequestFilter {

    private static final String BODY =
            "{\"code\":403,\"message\":\"请先修改密码（PASSWORD_CHANGE_REQUIRED）\",\"data\":null}";

    private final UserMapper userMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Object userId = request.getAttribute("userId");
        String uri = request.getRequestURI();
        if (!(userId instanceof Long id) || !uri.startsWith("/api/") || allowed(uri)) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            User user = userMapper.selectById(id);
            if (user != null && user.getPasswordChangeRequired() != null
                    && user.getPasswordChangeRequired() == 1) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write(BODY);
                return;
            }
        } catch (Exception e) {
            // fail-open：DB 抖动不能把全体用户锁死；改密引导仍在前端
            log.warn("强制改密门禁查询失败，按放行处理 userId={}: {}", id, e.getMessage());
        }
        filterChain.doFilter(request, response);
    }

    /** 登录/改密/菜单与健康、企微回调放行；注册等其它 auth 接口不放行。 */
    private static boolean allowed(String uri) {
        return uri.equals("/api/auth/login")
                || uri.equals("/api/auth/change-password")
                || uri.equals("/api/auth/my-menus")
                || uri.equals("/api/auth/my-menu-tree")
                || uri.startsWith("/api/ai-agent/health")
                || uri.startsWith("/api/wecom/callback")
                || uri.startsWith("/api/knowledge/health");
    }
}
