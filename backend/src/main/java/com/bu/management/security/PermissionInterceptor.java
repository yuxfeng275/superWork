package com.bu.management.security;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.annotation.RequireUsername;
import com.bu.management.service.SysRoleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class PermissionInterceptor implements HandlerInterceptor {

    private final SysRoleService sysRoleService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        HandlerMethod handlerMethod = (HandlerMethod) handler;
        RequirePermission annotation = handlerMethod.getMethod().getAnnotation(RequirePermission.class);
        RequireUsername usernameAnnotation = handlerMethod.getMethod().getAnnotation(RequireUsername.class);

        if (annotation == null) {
            annotation = handlerMethod.getBeanType().getAnnotation(RequirePermission.class);
        }
        if (usernameAnnotation == null) {
            usernameAnnotation = handlerMethod.getBeanType().getAnnotation(RequireUsername.class);
        }

        if (annotation == null && usernameAnnotation == null) {
            return true;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "登录已失效，请重新登录");
            return false;
        }

        if (usernameAnnotation != null) {
            String username = (String) request.getAttribute("username");
            if (username == null) {
                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "登录已失效，请重新登录");
                return false;
            }
            if (!Set.of(usernameAnnotation.value()).contains(username)) {
                writeError(response, HttpServletResponse.SC_FORBIDDEN, "当前账号无权执行该操作");
                return false;
            }
        }

        if (annotation == null) {
            return true;
        }

        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "登录已失效，请重新登录");
            return false;
        }

        List<String> userPermissions = sysRoleService.getPermissionCodesByUserId(userId);

        for (String required : annotation.value()) {
            if (userPermissions.contains(required)) {
                return true;
            }
        }

        // 以前这里只 setStatus(403)，响应体为空，前端只能显示「请求失败（403）」。
        writeError(response, HttpServletResponse.SC_FORBIDDEN,
                "当前账号无权执行该操作（需要 " + String.join(" 或 ", annotation.value()) + " 权限）");
        return false;
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // 不直接用 Result：Result 带 LocalDateTime，若注入的 ObjectMapper 未注册 JSR310 会序列化失败。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status);
        body.put("message", message);
        body.put("data", null);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
