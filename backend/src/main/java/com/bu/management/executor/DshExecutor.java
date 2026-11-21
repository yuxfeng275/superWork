package com.bu.management.executor;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * dsh 执行器：长任务、文件与命令类节点。
 *
 * <p>用 dsh 自带的 headless + JSON 模式，会话号由 {@code --session-id} 带上，
 * 因此下一轮能续跑同一个会话（{@link Capability#RESUME}）。
 *
 * <p>为什么是 CLI 而不是 ACP：ACP 的 stdio JSON-RPC 通道留给<b>触发器</b>（agent-autoloop 的
 * {@code use:"acp"}）用，编排内核只需要「给提示词、收事件、能续跑」这三件事，
 * CLI 已经稳定提供；在这里再写一套 JSON-RPC 客户端只会多一处需要跟着 dsh 版本走的代码。
 * 若将来需要在同一次会话里做多轮工具交互，再按 ACP 替换本类即可——上层不受影响。
 *
 * @author BU Team
 * @since 2026-11-21
 */
@Component
public class DshExecutor extends CommandExecutor {

    /** dsh 的默认命令行：项目里不配 command 时用它。 */
    private static final List<String> DEFAULT_COMMAND = List.of(
            "dsh", "--profile", "headless", "--json",
            "--session-id", "{sessionId}",
            "{prompt}");

    @Override
    public String kind() {
        return "dsh";
    }

    @Override
    protected List<String> defaultCommand() {
        return DEFAULT_COMMAND;
    }
}
