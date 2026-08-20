package com.bu.management.controller;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bu.management.annotation.RequirePermission;
import com.bu.management.annotation.RequireUsername;
import com.bu.management.dto.BuKeyMatterRequest;
import com.bu.management.dto.RegisterRequest;
import com.bu.management.dto.UserRequest;
import com.bu.management.entity.BuKeyMatterParticipant;
import com.bu.management.exception.ForbiddenOperationException;
import com.bu.management.exception.GlobalExceptionHandler;
import com.bu.management.mapper.BuKeyMatterParticipantMapper;
import com.bu.management.vo.BuKeyMatterAccessView;
import com.bu.management.vo.BuKeyMatterParticipantView;
import com.bu.management.vo.BuKeyMatterView;
import com.bu.management.vo.Result;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
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
        assertUserIdRequestAttribute(findMethod(EmailController.class, "startGrouping"));
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

    @Test
    void participantRelationEntityMapsToTableWithAutoId() throws Exception {
        TableName tableName = BuKeyMatterParticipant.class.getAnnotation(TableName.class);
        assertThat(tableName).isNotNull();
        assertThat(tableName.value()).isEqualTo("bu_key_matter_participant");

        Field idField = BuKeyMatterParticipant.class.getDeclaredField("id");
        TableId tableId = idField.getAnnotation(TableId.class);
        assertThat(tableId).isNotNull();
        assertThat(tableId.type()).isEqualTo(IdType.AUTO);
    }

    @Test
    void participantMapperExtendsBaseMapper() {
        assertThat(BaseMapper.class.isAssignableFrom(BuKeyMatterParticipantMapper.class)).isTrue();
    }

    @Test
    void participantViewExposesIdentityFields() throws Exception {
        assertThat(BuKeyMatterParticipantView.class.getDeclaredField("userId").getType())
                .isEqualTo(Long.class);
        assertThat(BuKeyMatterParticipantView.class.getDeclaredField("username").getType())
                .isEqualTo(String.class);
        assertThat(BuKeyMatterParticipantView.class.getDeclaredField("realName").getType())
                .isEqualTo(String.class);
    }

    @Test
    void accessViewUsesPrimitiveSafeBooleans() throws Exception {
        assertThat(BuKeyMatterAccessView.class.getDeclaredField("canAccess").getType())
                .isEqualTo(boolean.class);
        assertThat(BuKeyMatterAccessView.class.getDeclaredField("canManageAll").getType())
                .isEqualTo(boolean.class);
        assertThat(BuKeyMatterAccessView.class.getDeclaredField("canFeedbackOwn").getType())
                .isEqualTo(boolean.class);

        assertThat(BuKeyMatterAccessView.class.getConstructor()).isNotNull();
        assertThat(BuKeyMatterAccessView.class.getConstructor(boolean.class, boolean.class, boolean.class))
                .isNotNull();
    }

    @Test
    void forbiddenOperationExceptionExtendsRuntimeExceptionWithMessage() throws Exception {
        assertThat(RuntimeException.class.isAssignableFrom(ForbiddenOperationException.class)).isTrue();

        ForbiddenOperationException exception = ForbiddenOperationException.class
                .getConstructor(String.class)
                .newInstance("无权访问大事儿");
        assertThat(exception.getMessage()).isEqualTo("无权访问大事儿");
    }

    @Test
    void keyMatterRequestCarriesParticipantIds() throws Exception {
        assertThat(BuKeyMatterRequest.class.getDeclaredField("participantIds").getType())
                .isEqualTo(List.class);
    }

    @Test
    void keyMatterViewInitializesParticipantsNonNull() {
        assertThat(new BuKeyMatterView().getParticipants()).isNotNull();
    }

    @Test
    void forbiddenOperationExceptionMapsToHttp403() {
        Method handler = Arrays.stream(GlobalExceptionHandler.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                .filter(method -> Arrays.stream(method.getAnnotation(ExceptionHandler.class).value())
                        .anyMatch(type -> type.equals(ForbiddenOperationException.class)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少 ForbiddenOperationException 处理器"));

        ResponseStatus status = handler.getAnnotation(ResponseStatus.class);
        assertThat(status).isNotNull();
        assertThat(status.value()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(handler.getReturnType()).isEqualTo(Result.class);
    }

    @Test
    void v33MigrationCreatesParticipantRelationAndAccessPermissions() {
        String sql = readClasspathResource("db/migration/V33__add_key_matter_participants_and_access.sql");

        assertThat(sql)
                .contains(
                        "CREATE TABLE bu_key_matter_participant",
                        "UNIQUE KEY uk_key_matter_participant (key_matter_id, user_id)",
                        "INDEX idx_key_matter_participant_user (user_id)",
                        "FOREIGN KEY (key_matter_id) REFERENCES bu_key_matter(id) ON DELETE CASCADE",
                        "FOREIGN KEY (user_id) REFERENCES user(id)",
                        "utf8mb4_unicode_ci",
                        "INSERT IGNORE INTO bu_key_matter_participant (key_matter_id, user_id)",
                        "'bu:key-matter:view'",
                        "'bu:key-matter:feedback'",
                        "WHERE role.status = 1");
    }

    private String readClasspathResource(String path) {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(inputStream).as("classpath resource missing: %s", path).isNotNull();
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
