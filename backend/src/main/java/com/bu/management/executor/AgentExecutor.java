package com.bu.management.executor;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * 执行器插槽：编排层只认这个接口，换了谁来干活都不改业务代码。
 *
 * <p>为什么要有这层：sidecar 擅长业务工具密集型任务，dsh 擅长长任务与文件/命令，
 * 两者能力不同但「一轮执行」的语义相同（给提示词 → 流式事件 → 终态）。
 * 如果没有这个边界，「换执行器」就会变成在 service 里写 if-else，最后谁也不敢动。
 *
 * @author BU Team
 * @since 2026-11-21
 */
public interface AgentExecutor {

    /** 插槽标识，与 {@code ai_run_node.executor_kind} 对应。 */
    String kind();

    Set<Capability> capabilities();

    /** 启动一轮，立刻返回句柄（不阻塞编排线程）。 */
    ExecHandle start(ExecRequest request, ExecSink sink);

    enum Capability {
        /** 能按会话号续跑 */
        RESUME,
        /** 能把提示词走 stdin（超长提示词） */
        STDIN,
        /** 能返回结构化结果 */
        STRUCTURED,
        /** 能取消 */
        CANCEL
    }

    /**
     * 一轮执行的输入。
     *
     * @param systemPrompt    由节点/SOP 决定，不在执行器里硬编码
     * @param resumeSessionId 非空则续跑（dsh: --session-id，sidecar: 会话回放）
     * @param command         cli 类执行器的命令模板，元素可用 {prompt} / {systemPrompt} / {sessionId}
     */
    record ExecRequest(String runId, String nodeId, int attempt, Long userId, Long projectId,
                       String systemPrompt, String prompt, String resumeSessionId,
                       java.util.List<String> command, Map<String, String> env) {
    }

    /** 执行句柄：可以等，也可以取消。 */
    interface ExecHandle {
        /** 执行器侧的会话号（用于下一轮续跑；不确定时返回 null）。 */
        String sessionId();

        /** 等待终态；超时返回 {@link ExecOutcome#UNKNOWN}（不是失败——可能已经写完）。 */
        ExecOutcome await(Duration timeout) throws InterruptedException;

        /** 取消；执行器不支持时只能等它自己超时。 */
        void cancel();
    }

    /** 执行器只能通过事件讲话，编排层据此落 invocation 与节点状态。 */
    interface ExecSink {
        void onSession(String executorSessionId);

        void onText(String delta);

        void onThinking(String delta);

        void onToolCall(String callId, String toolName, String argsJson);

        void onToolResult(String callId, String resultJson, boolean isError);

        void onFinish(ExecOutcome outcome, String summary);
    }

    enum ExecOutcome {
        /** 正常结束 */
        OK,
        /** 需要用户补信息（停下等人，不是失败） */
        NEEDS_INPUT,
        /** 明确失败 */
        FAILED,
        /** 被取消 */
        CANCELLED,
        /** 拿不到终态：可能已产生副作用，必须核对 */
        UNKNOWN
    }
}
