package com.bu.management.orchestration;

import com.bu.management.entity.AiInvocation;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 调用记录的持久化端口。
 *
 * <p>抽这层不是为了将来换库，而是为了让「崩溃点」可测：三个杀进程窗口
 * （begin 前 / begin 后无副作用 / 副作用后无结果）必须在测试里被真的注入一次，
 * 而不是靠读代码相信它安全。真实实现见 {@link MybatisInvocationStore}。
 *
 * @author BU Team
 * @since 2026-11-21
 */
public interface InvocationStore {

    AiInvocation findByActionKey(Long runId, String logicalActionKey);

    AiInvocation findById(Long id);

    /** 插入；命中唯一键时抛 {@link org.springframework.dao.DuplicateKeyException}。 */
    void insert(AiInvocation row);

    /**
     * 条件更新：仅当当前状态属于 {@code expected} 时才写入 patch。
     *
     * @return 影响行数；0 表示状态已被别人改过或代次不符
     */
    int updateIfStatusIn(Long id, Set<String> expected, AiInvocation patch);

    /** 扫描「开始了但早于阈值仍无结果」的记录。 */
    List<AiInvocation> listStartedBefore(LocalDateTime threshold);
}
