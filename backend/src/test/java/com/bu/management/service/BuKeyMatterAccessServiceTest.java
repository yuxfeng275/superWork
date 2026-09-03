package com.bu.management.service;

import com.bu.management.entity.BuKeyMatter;
import com.bu.management.entity.User;
import com.bu.management.exception.ForbiddenOperationException;
import com.bu.management.mapper.BuKeyMatterMapper;
import com.bu.management.mapper.BuKeyMatterParticipantMapper;
import com.bu.management.mapper.UserMapper;
import com.bu.management.vo.BuKeyMatterAccessView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BuKeyMatterAccessServiceTest {

    @Mock
    private SysRoleService sysRoleService;
    @Mock
    private BuKeyMatterParticipantMapper participantMapper;
    @Mock
    private BuKeyMatterMapper matterMapper;
    @Mock
    private UserMapper userMapper;

    private BuKeyMatterAccessService service;

    @BeforeEach
    void setUp() {
        lenient().when(userMapper.selectById(anyLong()))
                .thenAnswer(invocation -> user(invocation.getArgument(0), 1));
        service = new BuKeyMatterAccessService(sysRoleService, participantMapper, matterMapper, userMapper);
    }

    @Test
    void accessForOwnerCanReadAndFeedbackButCannotManageAll() {
        when(sysRoleService.getPermissionCodesByUserId(7L))
                .thenReturn(List.of("bu:key-matter:view", "bu:key-matter:feedback"));
        when(participantMapper.existsByUserId(7L)).thenReturn(true);
        when(matterMapper.existsByOwnerId(7L)).thenReturn(true);

        BuKeyMatterAccessView access = service.resolveAccess(7L, "shijiale");

        assertThat(access).isEqualTo(new BuKeyMatterAccessView(true, false, true, true));
    }

    @Test
    void accessRequiresViewPermissionEvenForAnOwnerWithFeedbackPermission() {
        when(sysRoleService.getPermissionCodesByUserId(7L))
                .thenReturn(List.of("bu:key-matter:feedback"));
        when(participantMapper.existsByUserId(7L)).thenReturn(true);
        when(matterMapper.existsByOwnerId(7L)).thenReturn(true);

        assertThat(service.resolveAccess(7L, "shijiale"))
                .isEqualTo(new BuKeyMatterAccessView(false, false, true, false));
    }

    @Test
    void accessForParticipantIsReadOnly() {
        when(sysRoleService.getPermissionCodesByUserId(16L))
                .thenReturn(List.of("bu:key-matter:view", "bu:key-matter:feedback"));
        when(participantMapper.existsByUserId(16L)).thenReturn(true);
        when(matterMapper.existsByOwnerId(16L)).thenReturn(false);

        assertThat(service.resolveAccess(16L, "participant"))
                .isEqualTo(new BuKeyMatterAccessView(true, false, false, true));
    }

    @Test
    void resolveAccessLoadsPermissionCodesOnce() {
        when(sysRoleService.getPermissionCodesByUserId(7L))
                .thenReturn(List.of("bu:key-matter:view", "bu:key-matter:feedback"));
        when(participantMapper.existsByUserId(7L)).thenReturn(true);
        when(matterMapper.existsByOwnerId(7L)).thenReturn(true);

        service.resolveAccess(7L, "shijiale");

        verify(userMapper, times(1)).selectById(7L);
        verify(sysRoleService, times(1)).getPermissionCodesByUserId(7L);
    }

    @Test
    void disabledParticipantCannotRetainReadAccessFromOldJwt() {
        when(userMapper.selectById(16L)).thenReturn(user(16L, 0));
        lenient().when(sysRoleService.getPermissionCodesByUserId(16L))
                .thenReturn(List.of("bu:key-matter:view"));
        lenient().when(participantMapper.existsByUserId(16L)).thenReturn(true);

        assertThat(service.resolveAccess(16L, "participant"))
                .isEqualTo(new BuKeyMatterAccessView(false, false, false, false));
        assertThatThrownBy(() -> service.requireReadAccess(16L, "participant"))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("无权访问大事儿");
        verify(userMapper, times(2)).selectById(16L);
        verifyNoInteractions(sysRoleService, participantMapper, matterMapper);
    }

    @Test
    void disabledOwnerCannotRetainFeedbackAccessFromOldJwt() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, 0));
        lenient().when(sysRoleService.getPermissionCodesByUserId(7L))
                .thenReturn(List.of("bu:key-matter:view", "bu:key-matter:feedback"));
        lenient().when(participantMapper.existsByUserId(7L)).thenReturn(true);
        lenient().when(matterMapper.existsByOwnerId(7L)).thenReturn(true);

        assertThat(service.resolveAccess(7L, "shijiale"))
                .isEqualTo(new BuKeyMatterAccessView(false, false, false, false));
        assertThatThrownBy(() -> service.requireFeedback(matter(7L), 7L, "shijiale"))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("无权访问大事儿");
        verify(userMapper, times(2)).selectById(7L);
        verifyNoInteractions(sysRoleService, participantMapper, matterMapper);
    }

    @Test
    void disabledAdminCannotRetainManageAccessFromOldJwt() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, 0));
        lenient().when(sysRoleService.getPermissionCodesByUserId(1L))
                .thenReturn(List.of("bu:key-matter:manage"));

        assertThat(service.resolveAccess(1L, "admin"))
                .isEqualTo(new BuKeyMatterAccessView(false, false, false, false));
        assertThatThrownBy(() -> service.requireManageAll(1L, "admin"))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("仅管理员可管理大事儿");
        verify(userMapper, times(2)).selectById(1L);
        verifyNoInteractions(sysRoleService, participantMapper, matterMapper);
    }

    @Test
    void accessForAdminRequiresManagePermission() {
        when(sysRoleService.getPermissionCodesByUserId(1L))
                .thenReturn(List.of("bu:key-matter:manage"));

        assertThat(service.resolveAccess(1L, "admin"))
                .isEqualTo(new BuKeyMatterAccessView(true, true, true, true));
    }

    @Test
    void adminUsernameWithoutManagePermissionIsNotManager() {
        when(sysRoleService.getPermissionCodesByUserId(1L))
                .thenReturn(List.of("bu:key-matter:view"));

        assertThat(service.resolveAccess(1L, "admin"))
                .isEqualTo(new BuKeyMatterAccessView(false, false, false, false));
    }

    @Test
    void nonAdminUsernameWithManagePermissionCannotBypassUsernameBoundary() {
        when(sysRoleService.getPermissionCodesByUserId(21L))
                .thenReturn(List.of("bu:key-matter:view", "bu:key-matter:manage"));
        when(participantMapper.existsByUserId(21L)).thenReturn(true);

        assertThat(service.resolveAccess(21L, "manager"))
                .isEqualTo(new BuKeyMatterAccessView(true, false, false, false));
        assertThatThrownBy(() -> service.requireManageAll(21L, "manager"))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("仅管理员可管理大事儿");
    }

    @Test
    void firstTimeViewFeedbackUserCanReadAndCreateButCannotFeedbackYet() {
        when(sysRoleService.getPermissionCodesByUserId(20L))
                .thenReturn(List.of("bu:key-matter:view", "bu:key-matter:feedback"));
        when(participantMapper.existsByUserId(20L)).thenReturn(false);
        when(matterMapper.existsByOwnerId(20L)).thenReturn(false);

        assertThat(service.resolveAccess(20L, "unrelated"))
                .isEqualTo(new BuKeyMatterAccessView(true, false, false, true));
        service.requireReadAccess(20L, "unrelated");
        assertThatThrownBy(() -> service.requireFeedback(matter(7L), 20L, "unrelated"))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("仅事项负责人可反馈周进度");
    }

    @Test
    void partialPermissionsCannotCreateOrEnterWithoutParticipantRelation() {
        when(sysRoleService.getPermissionCodesByUserId(30L)).thenReturn(List.of("bu:key-matter:view"));
        when(participantMapper.existsByUserId(30L)).thenReturn(false);
        assertThat(service.resolveAccess(30L, "view-only"))
                .isEqualTo(new BuKeyMatterAccessView(false, false, false, false));

        when(sysRoleService.getPermissionCodesByUserId(31L)).thenReturn(List.of("bu:key-matter:feedback"));
        when(participantMapper.existsByUserId(31L)).thenReturn(false);
        assertThat(service.resolveAccess(31L, "feedback-only"))
                .isEqualTo(new BuKeyMatterAccessView(false, false, false, false));
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
    void firstTimeUserWithViewAndFeedbackCanAccessAndCreateOwn() {
        when(sysRoleService.getPermissionCodesByUserId(30L))
                .thenReturn(List.of("bu:key-matter:view", "bu:key-matter:feedback"));
        when(participantMapper.existsByUserId(30L)).thenReturn(false);

        BuKeyMatterAccessView access = service.resolveAccess(30L, "creator");

        assertThat(access).isEqualTo(new BuKeyMatterAccessView(true, false, false, true));
        service.requireCreate(30L, 30L, "creator");
    }

    @Test
    void ownCreationRejectsMismatchedOwnerBeforeMatterWrite() {
        when(sysRoleService.getPermissionCodesByUserId(30L))
                .thenReturn(List.of("bu:key-matter:view", "bu:key-matter:feedback"));

        assertThatThrownBy(() -> service.requireCreate(31L, 30L, "creator"))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("仅可创建本人负责的大事儿");
    }

    @Test
    void ownCreationRequiresBothViewAndFeedbackPermissions() {
        when(sysRoleService.getPermissionCodesByUserId(30L))
                .thenReturn(List.of("bu:key-matter:view"));

        assertThatThrownBy(() -> service.requireCreate(30L, 30L, "creator"))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("仅可创建本人负责的大事儿");
    }

    @Test
    void adminWithManageCanCreateAnyOwner() {
        when(sysRoleService.getPermissionCodesByUserId(1L))
                .thenReturn(List.of("bu:key-matter:manage"));

        service.requireCreate(31L, 1L, "admin");
    }

    @Test
    void disabledUserCannotCreateOwnMatter() {
        when(userMapper.selectById(30L)).thenReturn(user(30L, 0));

        assertThatThrownBy(() -> service.requireCreate(30L, 30L, "creator"))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("仅可创建本人负责的大事儿");
        verifyNoInteractions(sysRoleService);
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

    private User user(Long id, int status) {
        User user = new User();
        user.setId(id);
        user.setStatus(status);
        return user;
    }
}
