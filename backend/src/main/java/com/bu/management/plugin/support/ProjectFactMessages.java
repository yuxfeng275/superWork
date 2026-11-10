package com.bu.management.plugin.support;

/**
 * 项目准入被拒的统一文案：与 AiAgentToolService#NO_FACT_READ / #NO_FACT_WRITE 逐字一致。
 *
 * <p>这两句是模型唯一的「为什么读不到」依据，措辞不能两边漂。
 *
 * @author BU Team
 * @since 2026-11-20
 */
public final class ProjectFactMessages {

    /** 读事实被拒：让模型看到明确拒绝，而不是一个无解释的空列表。 */
    public static final String NO_FACT_READ = "无权访问该项目的事实（需项目成员/项目负责人或管理角色）";

    /** 写工具（制品 / 带 projectId 的晨报）被拒。 */
    public static final String NO_FACT_WRITE = "无权向该项目写入（需项目成员/负责人或管理角色）";

    private ProjectFactMessages() {
    }
}
