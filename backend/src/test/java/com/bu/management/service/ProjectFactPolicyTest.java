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
 * 事实准入 / 可见性的单一真源：网页列表与 AI 工具都调这一份。
 *
 * <p>口径依据 docs/fact-intake.md: PROJECT 项目成员可见；PRIVATE_COMPANY 只有本公司的人
 * （canWrite 那一档）；OWNER 仅投递人本人。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectFactPolicyTest {

    private static final Long PROJECT_ID = 11L;
    private static final Long MANAGER_ID = 3L;
    private static final Long MEMBER_ID = 5L;
    private static final Long STRANGER_ID = 7L;
    private static final Long DIRECTOR_ID = 9L;

    @Mock private ProjectMapper projectMapper;
    @Mock private DataPermissionService dataPermissionService;
    @Mock private SysRoleService sysRoleService;

    private ProjectFactPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new ProjectFactPolicy(
                new ProjectFactAccessGuard(projectMapper, dataPermissionService, sysRoleService));

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
    @DisplayName("读准入：非成员读不到也不抛异常，直接空列表")
    void nonMemberGetsEmpty() {
        assertThat(policy.canRead(STRANGER_ID, PROJECT_ID)).isFalse();
        assertThat(policy.visibleOrEmpty(STRANGER_ID, PROJECT_ID, allFacts())).isEmpty();
    }

    @Test
    @DisplayName("读准入：项目成员 / 项目负责人 / 管理角色都通过")
    void memberManagerAdminPass() {
        assertThat(policy.canRead(MEMBER_ID, PROJECT_ID)).isTrue();
        assertThat(policy.canRead(MANAGER_ID, PROJECT_ID)).isTrue();
        assertThat(policy.canRead(DIRECTOR_ID, PROJECT_ID)).isTrue();
        assertThat(policy.canWrite(MEMBER_ID, PROJECT_ID)).isTrue();
    }

    @Test
    @DisplayName("PRIVATE_COMPANY：canWrite 一档可见（成员 / 负责人 / 管理角色），外人不可见")
    void privateCompanyVisibility() {
        assertThat(policy.canSeePrivateCompany(MEMBER_ID, PROJECT_ID)).isTrue();
        assertThat(policy.canSeePrivateCompany(MANAGER_ID, PROJECT_ID)).isTrue();
        assertThat(policy.canSeePrivateCompany(DIRECTOR_ID, PROJECT_ID)).isTrue();
        assertThat(policy.canSeePrivateCompany(STRANGER_ID, PROJECT_ID)).isFalse();
        // 项目不存在：一律不可见
        assertThat(policy.canSeePrivateCompany(MANAGER_ID, 99L)).isFalse();
    }

    @Test
    @DisplayName("成员：看得到 PROJECT + PRIVATE_COMPANY + 自己的 OWNER，看不到别人的 OWNER")
    void memberSeesProjectCompanyAndOwnOwnerFacts() {
        assertThat(policy.visible(MEMBER_ID, PROJECT_ID, allFacts()))
                .extracting(ProjectFact::getId).containsExactly(1L, 2L, 3L);
        assertThat(policy.visibleOrEmpty(MEMBER_ID, PROJECT_ID, allFacts()))
                .extracting(ProjectFact::getId).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("项目负责人：看得到 PROJECT + PRIVATE_COMPANY，别人的 OWNER 仍不可见")
    void managerSeesEverythingButOthersOwnerFacts() {
        assertThat(policy.visible(MANAGER_ID, PROJECT_ID, allFacts()))
                .extracting(ProjectFact::getId).containsExactly(1L, 3L);
    }

    @Test
    @DisplayName("外人：即便直接调 visible 也只剩 PROJECT 口径，OWNER 别人的/本人都不漏给他")
    void strangerSeesOnlyProjectFacts() {
        assertThat(policy.visible(STRANGER_ID, PROJECT_ID, allFacts()))
                .extracting(ProjectFact::getId).containsExactly(1L);
    }

    @Test
    @DisplayName("空/null 列表不炸也不多打库")
    void emptyInput() {
        assertThat(policy.visible(MEMBER_ID, PROJECT_ID, List.of())).isEmpty();
        assertThat(policy.visible(MEMBER_ID, PROJECT_ID, null)).isEmpty();
        assertThat(policy.visibleOrEmpty(STRANGER_ID, PROJECT_ID, null)).isEmpty();
    }

    /** 1 = PROJECT（外人投的）、2 = OWNER（成员投的）、3 = PRIVATE_COMPANY（成员投的）。 */
    private List<ProjectFact> allFacts() {
        return List.of(
                fact(1L, ProjectFactService.VISIBILITY_PROJECT, STRANGER_ID),
                fact(2L, ProjectFactService.VISIBILITY_OWNER, MEMBER_ID),
                fact(3L, ProjectFactService.VISIBILITY_COMPANY, MEMBER_ID));
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
