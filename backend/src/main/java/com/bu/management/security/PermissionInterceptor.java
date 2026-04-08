package com.bu.management.security;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.service.SysRoleService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

@Component
@RequiredArgsConstructor
public class PermissionInterceptor implements HandlerInterceptor {

    private final SysRoleService sysRoleService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        HandlerMethod handlerMethod = (HandlerMethod) handler;
        RequirePermission annotation = handlerMethod.getMethod().getAnnotation(RequirePermission.class);

        if (annotation == null) {
            annotation = handlerMethod.getBeanType().getAnnotation(RequirePermission.class);
        }

        if (annotation == null) {
            return true;
        }

        String[] requiredPermissions = annotation.value();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        List<String> userPermissions = sysRoleService.getPermissionCodesByUserId(userId);

        for (String required : requiredPermissions) {
            if (userPermissions.contains(required)) {
                return true;
            }
        }

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        return false;
    }
}
