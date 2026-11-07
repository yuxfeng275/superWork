package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.bu.management.constant.PositionRoles;
import com.bu.management.entity.Project;
import com.bu.management.entity.ProjectFact;
import com.bu.management.entity.SysRole;
import com.bu.management.mapper.ProjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 投递准入：项目负责人 / 项目成员 / 管理角色可读写，普通成员之外的角色一律 403；
 * PROJECT 永远可见、OWNER 仅本人、PRIVATE_COMPANY 仅管理者。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectFactAccessGuardTest {

    private static final Long PROJECT_ID = 11L;
    private static final Long MANAGER_ID = 3L;
    private static final Long MEMBER_ID = 5L;
    private static final Long STRANGER_ID = 7L;
    private static final Long DIRECTOR_ID = 9L;

    @Mock private ProjectMapper projectMapper;
    @Mock private DataPermissionService dataPermissionService;
    @Mock private SysRoleService sysRoleService;

    private ProjectFactAccessGuard guard;

    @BeforeEach
    void setUp() {
        guard = new ProjectFactAccessGuard(projectMapper, dataPermissionService, sysRoleService);

        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setManagerId(MANAGER_ID);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);
        when(projectMapper.selectById(99L)).thenReturn(null);

        // 只有 MEMBER_ID 在 project_member 里
        when(dataPermissionService.isProjectMember(anyLong(), any())).thenAnswer(inv ->
                MEMBER_ID.equals(inv.<Long>getArgument(0))
                        && PROJECT_ID.equals(inv.<Long>getArgument(1)));

        SysRole directorRole = new SysRole();
        directorRole.setId(1L);
        directorRole.setCode(PositionRoles.DIRECTOR);
        SysRole engineerRole = new SysRole();
        engineerRole.setId(2L);
        engineerRole.setCode(PositionRoles.FULL_STACK_ENGINEER);
        when(sysRoleService.getRolesByUserId(any())).thenAnswer(inv -> {
            Long userId = inv.getArgument(0);
            if (DIRECTOR_ID.equals(userId)) {
                return List.of(directorRole);
            }
            if (STRANGER_ID.equals(userId) || MEMBER_ID.equals(userId)) {
                return List.of(engineerRole);
            }
            return List.of();
        });
        when(sysRoleService.getPermissionCodesByUserId(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("项目负责人可读可写")
    void projectManagerCanReadAndWrite() {
        assertThat(guard.canRead(MANAGER_ID, PROJECT_ID)).isTrue();
        assertThat(guard.canWrite(MANAGER_ID, PROJECT_ID)).isTrue();
    }

    @Test
    @DisplayName("项目成员可读可写")
    void projectMemberCanReadAndWrite() {
        assertThat(guard.canRead(MEMBER_ID, PROJECT_ID)).isTrue();
        assertThat(guard.canWrite(MEMBER_ID, PROJECT_ID)).isTrue();
    }

    @Test
    @DisplayName("管理角色（总监）可读可写任意项目")
    void adminRoleCanReadAndWrite() {
        assertThat(guard.canRead(DIRECTOR_ID, PROJECT_ID)).isTrue();
        assertThat(guard.canWrite(DIRECTOR_ID, PROJECT_ID)).isTrue();
        assertThat(guard.isProjectManager(DIRECTOR_ID, PROJECT_ID)).isTrue();
    }

    @Test
    @DisplayName("拥有 project:manage 权限也可读写")
    void projectManagePermissionGrantsAccess() {
        when(sysRoleService.getPermissionCodesByUserId(STRANGER_ID))
                .thenReturn(List.of("project:manage"));

        assertThat(guard.canRead(STRANGER_ID, PROJECT_ID)).isTrue();
        assertThat(guard.canWrite(STRANGER_ID, PROJECT_ID)).isTrue();
    }

    @Test
    @DisplayName("既非成员又非管理角色：不可读也不可写")
    void strangerCanNeitherReadNorWrite() {
        assertThat(guard.canRead(STRANGER_ID, PROJECT_ID)).isFalse();
        assertThat(guard.canWrite(STRANGER_ID, PROJECT_ID)).isFalse();
        assertThat(guard.isProjectManager(STRANGER_ID, PROJECT_ID)).isFalse();
    }

    @Test
    @DisplayName("项目不存在 / 参数缺失：一律 false")
    void missingProjectDenied() {
        assertThat(guard.canRead(MANAGER_ID, 99L)).isFalse();
        assertThat(guard.canWrite(MANAGER_ID, 99L)).isFalse();
        assertThat(guard.canRead(null, PROJECT_ID)).isFalse();
        assertThat(guard.canWrite(MANAGER_ID, null)).isFalse();
    }

    @Test
    @DisplayName("可见性过滤：PROJECT 全可见、OWNER 仅本人、PRIVATE_COMPANY 仅管理者")
    void visibilityFiltering() {
        ProjectFact projectFact = fact(1L, ProjectFactService.VISIBILITY_PROJECT, STRANGER_ID);
        ProjectFact ownFact = fact(2L, ProjectFactService.VISIBILITY_OWNER, MEMBER_ID);
        ProjectFact companyFact = fact(3L, ProjectFactService.VISIBILITY_COMPANY, MEMBER_ID);
        List<ProjectFact> all = List.of(projectFact, ownFact, companyFact);

        // 普通成员：看得见项目口径与自己的 OWNER，看不到 PRIVATE_COMPANY
        assertThat(guard.visibleFacts(MEMBER_ID, PROJECT_ID, all))
                .extracting(ProjectFact::getId).containsExactly(1L, 2L);
        // 项目负责人：OWNER 那条是别人投的，看不到；PRIVATE_COMPANY 看得到
        assertThat(guard.visibleFacts(MANAGER_ID, PROJECT_ID, all))
                .extracting(ProjectFact::getId).containsExactly(1L, 3L);
        // 局外人：只看得见 PROJECT 口径
        assertThat(guard.visibleFacts(STRANGER_ID, PROJECT_ID, all))
                .extracting(ProjectFact::getId).containsExactly(1L);
    }

    private ProjectFact fact(Long id, String visibility, Long actorId) {
        ProjectFact fact = new ProjectFact();
        fact.setId(id);
        fact.setProjectId(PROJECT_ID);
        fact.setVisibility(visibility);
        fact.setActorId(actorId);
        return fact;
    }
}
