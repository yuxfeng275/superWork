package com.bu.management.capability;

/**
 * 能力类别：决定网关的执行路径。
 *
 * <ul>
 *   <li>{@link #TOOL} 助手可直接调用的工具（唯一会下发给模型的一类）</li>
 *   <li>{@link #JOB} 后台任务型能力，由调度器 / 队列触发，不进助手工具列表</li>
 *   <li>{@link #KNOWLEDGE} 知识检索型能力（向量 / 文档召回）</li>
 *   <li>{@link #REMOTE} 远端能力：本地只有壳，实际执行在 Python worker 侧</li>
 *   <li>{@link #CONNECTOR} 外部系统连接器（邮件、企微、云效等）</li>
 * </ul>
 *
 * @author BU Team
 * @since 2026-11-20
 */
public enum CapabilityKind {

    /** 助手工具：会出现在 describeForAgent() 的结果里。 */
    TOOL,

    /** 后台任务：慢、需要重试，走任务队列。 */
    JOB,

    /** 知识检索：问答前的召回环节。 */
    KNOWLEDGE,

    /** 远端能力：HTTP 转发到 Python worker。 */
    REMOTE,

    /** 外部连接器：包住三方系统调用。 */
    CONNECTOR
}
