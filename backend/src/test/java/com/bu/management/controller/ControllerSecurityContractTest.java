package com.bu.management.controller;

import com.bu.management.annotation.RequirePermission;
import com.bu.management.annotation.RequireUsername;
import com.bu.management.dto.RegisterRequest;
import com.bu.management.dto.UserRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ControllerSecurityContractTest {

    private static final List<Class<?>> PROTECTED_CONTROLLERS = List.of(
            AttachmentController.class,
            BuDecisionController.class,
            BuDashboardController.class,
            BuDirectionController.class,
            BuKeyMatterController.class,
            BusinessLineController.class,
            CustomerContactController.class,
            DesignWorkLogController.class,
            EmailController.class,
            IssueController.class,
            ProjectController.class,
            ProjectMemberController.class,
            RequirementConfirmationController.class,
            RequirementController.class,
            RequirementDeliveryController.class,
            RequirementDesignController.class,
            RequirementEvaluationController.class,
            RequirementStatusTransitionController.class,
            StatisticsController.class,
            SysMenuController.class,
            SystemConfigController.class,
            SysPermissionController.class,
            SysRoleController.class,
            TaskController.class,
            UserController.class,
            WorkLogController.class,
            WorkflowConfigController.class,
            YunxiaoIntegrationController.class
    );

    @Test
    void everyBusinessEndpointDeclaresPermissionProtection() {
        PROTECTED_CONTROLLERS.forEach(controller -> {
            RequirePermission classPermission = controller.getAnnotation(RequirePermission.class);
            Arrays.stream(controller.getDeclaredMethods())
                    .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                    .forEach(method -> assertThat(method.getAnnotation(RequirePermission.class) != null
                            || classPermission != null)
                            .as("%s#%s 缺少权限声明", controller.getSimpleName(), method.getName())
                            .isTrue());
        });
    }

    @Test
    void userManagementRequiresSystemUserPermission() throws Exception {
        Method create = UserController.class.getMethod("create", UserRequest.class);

        assertThat(create.getAnnotation(RequirePermission.class))
                .isNotNull()
                .extracting(RequirePermission::value)
                .isEqualTo(new String[]{"system:user:create"});
    }

    @Test
    void userDirectoryIsAvailableToOrganizationViewers() {
        RequirePermission permission = UserController.class.getAnnotation(RequirePermission.class);

        assertThat(permission.value())
                .containsExactlyInAnyOrder("system:user:list", "org:view");
    }

    @Test
    void registrationRequiresSystemUserCreatePermission() throws Exception {
        Method register = AuthController.class.getMethod("register", RegisterRequest.class);

        assertThat(register.getAnnotation(RequirePermission.class))
                .isNotNull()
                .extracting(RequirePermission::value)
                .isEqualTo(new String[]{"system:user:create"});
    }

    @Test
    void workflowManagementRequiresSystemWorkflowPermission() {
        RequirePermission permission = WorkflowConfigController.class.getAnnotation(RequirePermission.class);

        assertThat(permission)
                .isNotNull()
                .extracting(RequirePermission::value)
                .isEqualTo(new String[]{"system:workflow:list"});
    }

    @Test
    void keyMattersAreRestrictedToAdminAndYufeng() {
        RequireUsername usernames = BuKeyMatterController.class.getAnnotation(RequireUsername.class);

        assertThat(usernames)
                .isNotNull()
                .extracting(RequireUsername::value)
                .isEqualTo(new String[]{"admin", "yufeng"});
    }

    @Test
    void writeActionsReceiveAuthenticatedUserId() {
        assertUserIdRequestAttribute(findMethod(RequirementController.class, "create"));
        assertUserIdRequestAttribute(findMethod(RequirementEvaluationController.class, "submitEvaluation"));
        assertUserIdRequestAttribute(findMethod(BuDecisionController.class, "makeDecision"));
    }

    @Test
    void emailActionsReceiveAuthenticatedUserId() {
        assertUserIdRequestAttribute(findMethod(EmailController.class, "save"));
        assertUserIdRequestAttribute(findMethod(EmailController.class, "sync"));
        assertUserIdRequestAttribute(findMethod(EmailController.class, "message"));
        assertUserIdRequestAttribute(findMethod(EmailController.class, "regenerate"));
        assertUserIdRequestAttribute(findMethod(EmailController.class, "generateInterpretation"));
    }

    @Test
    void systemConfigWritesReceiveAuthenticatedUserId() {
        assertUserIdRequestAttribute(findMethod(SystemConfigController.class, "saveGroup"));
    }

    private Method findMethod(Class<?> controller, String methodName) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
    }

    private void assertUserIdRequestAttribute(Method method) {
        Parameter actorParameter = Arrays.stream(method.getParameters())
                .filter(parameter -> parameter.isAnnotationPresent(RequestAttribute.class))
                .findFirst()
                .orElseThrow(() -> new AssertionError(method.getName() + " 缺少 userId 请求属性"));

        RequestAttribute requestAttribute = actorParameter.getAnnotation(RequestAttribute.class);
        assertThat(requestAttribute.value()).isEqualTo("userId");
        assertThat(actorParameter.getType()).isEqualTo(Long.class);
    }
}
