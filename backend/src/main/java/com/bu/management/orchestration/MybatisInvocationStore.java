package com.bu.management.orchestration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bu.management.entity.AiInvocation;
import com.bu.management.mapper.AiInvocationMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link InvocationStore} 的 MyBatis 实现：条件更新用状态做 CAS，不依赖行锁。
 *
 * @author BU Team
 * @since 2026-11-21
 */
@Component
@RequiredArgsConstructor
public class MybatisInvocationStore implements InvocationStore {

    private final AiInvocationMapper mapper;

    @Override
    public AiInvocation findByActionKey(Long runId, String logicalActionKey) {
        return mapper.selectOne(Wrappers.<AiInvocation>lambdaQuery()
                .eq(AiInvocation::getRunId, runId)
                .eq(AiInvocation::getLogicalActionKey, logicalActionKey)
                .last("limit 1"));
    }

    @Override
    public AiInvocation findById(Long id) {
        return mapper.selectById(id);
    }

    @Override
    public void insert(AiInvocation row) {
        mapper.insert(row);
    }

    @Override
    public int updateIfStatusIn(Long id, Set<String> expected, AiInvocation patch) {
        if (expected == null || expected.isEmpty()) {
            return 0;
        }
        patch.setId(id);
        return mapper.update(patch, Wrappers.<AiInvocation>lambdaUpdate()
                .eq(AiInvocation::getId, id)
                .in(AiInvocation::getStatus, expected));
    }

    @Override
    public List<AiInvocation> listStartedBefore(LocalDateTime threshold) {
        return mapper.selectList(Wrappers.<AiInvocation>lambdaQuery()
                .eq(AiInvocation::getStatus, InvocationStatus.STARTED.name())
                .lt(AiInvocation::getStartedAt, threshold));
    }
}
