package com.bu.management.service;

import com.bu.management.entity.BuKeyMatter;
import com.bu.management.exception.ForbiddenOperationException;
import com.bu.management.mapper.BuKeyMatterMapper;
import com.bu.management.mapper.BuKeyMatterParticipantMapper;
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

    public BuKeyMatterAccessView resolveAccess(Long userId, String username) {
        boolean canManageAll = isAdmin(username) && hasPermission(userId, PERMISSION_MANAGE);
        boolean canView = canManageAll || hasPermission(userId, PERMISSION_VIEW);
        boolean isParticipant = userId != null && participantMapper.existsByUserId(userId);
        boolean canAccess = canManageAll || (canView && isParticipant);
        boolean canFeedbackOwn = canManageAll
                || (hasPermission(userId, PERMISSION_FEEDBACK)
                    && userId != null
                    && matterMapper.existsByOwnerId(userId));
        return new BuKeyMatterAccessView(canAccess, canManageAll, canFeedbackOwn);
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

    public void requireFeedback(BuKeyMatter matter, Long userId, String username) {
        boolean canManageAll = isAdmin(username) && hasPermission(userId, PERMISSION_MANAGE);
        if (canManageAll) {
            return;
        }
        boolean canFeedback = hasPermission(userId, PERMISSION_FEEDBACK)
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

    private boolean hasPermission(Long userId, String code) {
        if (userId == null) {
            return false;
        }
        List<String> codes = sysRoleService.getPermissionCodesByUserId(userId);
        return codes != null && codes.contains(code);
    }
}
