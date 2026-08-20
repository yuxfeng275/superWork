package com.bu.management.service;

import com.bu.management.entity.BuKeyMatter;
import com.bu.management.exception.ForbiddenOperationException;
import com.bu.management.mapper.BuKeyMatterMapper;
import com.bu.management.mapper.BuKeyMatterParticipantMapper;
import com.bu.management.vo.BuKeyMatterAccessView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BuKeyMatterAccessServiceTest {

    @Mock
    private SysRoleService sysRoleService;
    @Mock
    private BuKeyMatterParticipantMapper participantMapper;
    @Mock
    private BuKeyMatterMapper matterMapper;

    private BuKeyMatterAccessService service;

    @BeforeEach
    void setUp() {
        service = new BuKeyMatterAccessService(sysRoleService, participantMapper, matterMapper);
    }

    @Test
    void accessForOwnerCanReadAndFeedbackButCannotManageAll() {
        when(sysRoleService.getPermissionCodesByUserId(7L))
                .thenReturn(List.of("bu:key-matter:view", "bu:key-matter:feedback"));
        when(participantMapper.existsByUserId(7L)).thenReturn(true);
        when(matterMapper.existsByOwnerId(7L)).thenReturn(true);

        BuKeyMatterAccessView access = service.resolveAccess(7L, "shijiale");

        assertThat(access).isEqualTo(new BuKeyMatterAccessView(true, false, true));
    }

    @Test
    void accessForParticipantIsReadOnly() {
        when(sysRoleService.getPermissionCodesByUserId(16L))
                .thenReturn(List.of("bu:key-matter:view", "bu:key-matter:feedback"));
        when(participantMapper.existsByUserId(16L)).thenReturn(true);
        when(matterMapper.existsByOwnerId(16L)).thenReturn(false);

        assertThat(service.resolveAccess(16L, "participant"))
                .isEqualTo(new BuKeyMatterAccessView(true, false, false));
    }

    @Test
    void accessForAdminRequiresManagePermission() {
        when(sysRoleService.getPermissionCodesByUserId(1L))
                .thenReturn(List.of("bu:key-matter:manage"));

        assertThat(service.resolveAccess(1L, "admin"))
                .isEqualTo(new BuKeyMatterAccessView(true, true, true));
    }

    @Test
    void adminUsernameWithoutManagePermissionIsNotManager() {
        when(sysRoleService.getPermissionCodesByUserId(1L))
                .thenReturn(List.of("bu:key-matter:view"));

        assertThat(service.resolveAccess(1L, "admin"))
                .isEqualTo(new BuKeyMatterAccessView(false, false, false));
    }

    @Test
    void unrelatedUserCannotReadOrFeedback() {
        when(sysRoleService.getPermissionCodesByUserId(20L))
                .thenReturn(List.of("bu:key-matter:view", "bu:key-matter:feedback"));
        when(participantMapper.existsByUserId(20L)).thenReturn(false);
        when(matterMapper.existsByOwnerId(20L)).thenReturn(false);

        assertThat(service.resolveAccess(20L, "unrelated"))
                .isEqualTo(new BuKeyMatterAccessView(false, false, false));
        assertThatThrownBy(() -> service.requireReadAccess(20L, "unrelated"))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("无权访问大事儿");
    }

    @Test
    void requireReadAccessAllowsParticipant() {
        when(sysRoleService.getPermissionCodesByUserId(7L))
                .thenReturn(List.of("bu:key-matter:view", "bu:key-matter:feedback"));
        when(participantMapper.existsByUserId(7L)).thenReturn(true);

        service.requireReadAccess(7L, "shijiale");
    }

    @Test
    void requireManageAllRejectsNonAdmin() {
        when(sysRoleService.getPermissionCodesByUserId(7L))
                .thenReturn(List.of("bu:key-matter:view", "bu:key-matter:feedback"));
        when(participantMapper.existsByUserId(7L)).thenReturn(true);
        when(matterMapper.existsByOwnerId(7L)).thenReturn(true);

        assertThatThrownBy(() -> service.requireManageAll(7L, "shijiale"))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("仅管理员可管理大事儿");
    }

    @Test
    void requireManageAllAllowsAdminWithManagePermission() {
        when(sysRoleService.getPermissionCodesByUserId(1L))
                .thenReturn(List.of("bu:key-matter:manage"));

        service.requireManageAll(1L, "admin");
    }

    @Test
    void requireFeedbackAllowsCurrentOwnerWithFeedbackPermission() {
        when(sysRoleService.getPermissionCodesByUserId(7L))
                .thenReturn(List.of("bu:key-matter:view", "bu:key-matter:feedback"));

        service.requireFeedback(matter(7L), 7L, "shijiale");
    }

    @Test
    void requireFeedbackRejectsParticipantWithoutOwnership() {
        when(sysRoleService.getPermissionCodesByUserId(16L))
                .thenReturn(List.of("bu:key-matter:view", "bu:key-matter:feedback"));

        assertThatThrownBy(() -> service.requireFeedback(matter(7L), 16L, "participant"))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("仅事项负责人可反馈周进度");
    }

    @Test
    void requireFeedbackAllowsAdminWithManagePermission() {
        when(sysRoleService.getPermissionCodesByUserId(1L))
                .thenReturn(List.of("bu:key-matter:manage"));

        service.requireFeedback(matter(7L), 1L, "admin");
    }

    @Test
    void requireFeedbackRejectsOwnerWithoutFeedbackPermission() {
        when(sysRoleService.getPermissionCodesByUserId(7L))
                .thenReturn(List.of("bu:key-matter:view"));

        assertThatThrownBy(() -> service.requireFeedback(matter(7L), 7L, "shijiale"))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("仅事项负责人可反馈周进度");
    }

    private BuKeyMatter matter(Long ownerId) {
        BuKeyMatter matter = new BuKeyMatter();
        matter.setId(11L);
        matter.setOwnerId(ownerId);
        return matter;
    }
}
