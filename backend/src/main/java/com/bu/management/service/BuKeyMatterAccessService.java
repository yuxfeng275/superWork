package com.bu.management.service;

import com.bu.management.entity.BuKeyMatter;
import com.bu.management.entity.User;
import com.bu.management.exception.ForbiddenOperationException;
import com.bu.management.mapper.BuKeyMatterMapper;
import com.bu.management.mapper.BuKeyMatterParticipantMapper;
import com.bu.management.mapper.UserMapper;
import com.bu.management.vo.BuKeyMatterAccessView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 大事儿动态访问授权：结合 RBAC 权限码与当前参与人/负责人关系计算能力，
 * 并对读取、管理和周进度反馈执行领域校验。
 */
@Service
@RequiredArgsConstructor
public class BuKeyMatterAccessService {

    private static final String PERMISSION_VIEW = "bu:key-matter:view";
    private static final String PERMISSION_FEEDBACK = "bu:key-matter:feedback";
    private static final String PERMISSION_MANAGE = "bu:key-matter:manage";

    private final SysRoleService sysRoleService;
    private final BuKeyMatterParticipantMapper participantMapper;
    private final BuKeyMatterMapper matterMapper;
    private final UserMapper userMapper;

    public BuKeyMatterAccessView resolveAccess(Long userId, String username) {
        if (!isActiveUser(userId)) {
            return new BuKeyMatterAccessView(false, false, false, false);
        }
        List<String> permissionCodes = permissionCodes(userId);
        boolean canManageAll = isAdmin(username) && permissionCodes.contains(PERMISSION_MANAGE);
        boolean canCreateOwn = canManageAll
                || (permissionCodes.contains(PERMISSION_VIEW)
                    && permissionCodes.contains(PERMISSION_FEEDBACK));
        boolean canView = canManageAll || permissionCodes.contains(PERMISSION_VIEW);
        boolean isParticipant = userId != null && participantMapper.existsByUserId(userId);
        boolean canAccess = canManageAll || canCreateOwn || (canView && isParticipant);
        boolean canFeedbackOwn = canManageAll
                || (permissionCodes.contains(PERMISSION_FEEDBACK)
                    && userId != null
                    && matterMapper.existsByOwnerId(userId));
        return new BuKeyMatterAccessView(canAccess, canManageAll, canFeedbackOwn, canCreateOwn);
    }

    public void requireCreate(Long ownerId, Long userId, String username) {
        if (!isActiveUser(userId)) {
            throw new ForbiddenOperationException("仅可创建本人负责的大事儿");
        }
        List<String> permissionCodes = permissionCodes(userId);
        boolean canManageAll = isAdmin(username) && permissionCodes.contains(PERMISSION_MANAGE);
        boolean canCreateOwn = permissionCodes.contains(PERMISSION_VIEW)
                && permissionCodes.contains(PERMISSION_FEEDBACK);
        if (!canManageAll && (!canCreateOwn || !userId.equals(ownerId))) {
            throw new ForbiddenOperationException("仅可创建本人负责的大事儿");
        }
    }

    public void requireReadAccess(Long userId, String username) {
        if (!resolveAccess(userId, username).isCanAccess()) {
            throw new ForbiddenOperationException("无权访问大事儿");
        }
    }

    public void requireManageAll(Long userId, String username) {
        if (!resolveAccess(userId, username).isCanManageAll()) {
            throw new ForbiddenOperationException("仅管理员可管理大事儿");
        }
    }
    public void requireEdit(BuKeyMatter matter, Long userId, String username) {
        if (!isActiveUser(userId)) {
            throw new ForbiddenOperationException("仅可编辑本人负责的大事儿");
        }
        List<String> permissionCodes = permissionCodes(userId);
        boolean canManageAll = isAdmin(username) && permissionCodes.contains(PERMISSION_MANAGE);
        if (canManageAll) {
            return;
        }
        boolean canEditOwn = permissionCodes.contains(PERMISSION_VIEW)
                && permissionCodes.contains(PERMISSION_FEEDBACK)
                && matter != null
                && userId != null
                && userId.equals(matter.getOwnerId());
        if (!canEditOwn) {
            throw new ForbiddenOperationException("仅可编辑本人负责的大事儿");
        }
    }

    public void requireFeedback(BuKeyMatter matter, Long userId, String username) {
        if (!isActiveUser(userId)) {
            throw new ForbiddenOperationException("无权访问大事儿");
        }
        List<String> permissionCodes = permissionCodes(userId);
        boolean canManageAll = isAdmin(username) && permissionCodes.contains(PERMISSION_MANAGE);
        if (canManageAll) {
            return;
        }
        boolean canFeedback = permissionCodes.contains(PERMISSION_FEEDBACK)
                && matter != null
                && userId != null
                && userId.equals(matter.getOwnerId());
        if (!canFeedback) {
            throw new ForbiddenOperationException("仅事项负责人可反馈周进度");
        }
    }

    private boolean isAdmin(String username) {
        return "admin".equals(username) || "yufeng".equals(username);
    }

    private boolean isActiveUser(Long userId) {
        User user = userId == null ? null : userMapper.selectById(userId);
        return user != null && Integer.valueOf(1).equals(user.getStatus());
    }

    private List<String> permissionCodes(Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<String> codes = sysRoleService.getPermissionCodesByUserId(userId);
        return codes == null ? List.of() : codes;
    }
}
