package com.bu.management.executor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * cli 执行器的两个关键行为：会话号能续跑、没有会话号时那段参数要省略（而不是传空串）。
 */
class CommandExecutorTest {

    private final CommandExecutor executor = new CommandExecutor();

    @Test
    @DisplayName("续跑：{sessionId} 被替换成节点记录的会话号")
    void resumeUsesSessionPlaceholder() {
        List<String> argv = executor.buildArgv(new AgentExecutor.ExecRequest(
                "r", "n", 1, 7L, 1L, "sys", "do it", "sess-42",
                List.of("dsh", "--profile", "headless", "--session-id", "{sessionId}", "{prompt}"),
                Map.of()));

        assertThat(argv).containsExactly("dsh", "--profile", "headless",
                "--session-id", "sess-42", "do it");
    }

    @Test
    @DisplayName("首次执行：没有会话号，--session-id 整段省略")
    void firstRunOmitsSessionFlag() {
        List<String> argv = executor.buildArgv(new AgentExecutor.ExecRequest(
                "r", "n", 0, 7L, 1L, "sys", "do it", null,
                List.of("dsh", "--session-id", "{sessionId}", "{prompt}"),
                Map.of()));

        assertThat(argv).containsExactly("dsh", "do it");
    }

    @Test
    @DisplayName("真的跑一个命令：标准输出按行转成文本事件，退出码 0 即 OK")
    void runsCommandAndReportsOutcome() throws Exception {
        ReflectionTestUtils.setField(executor, "timeoutSeconds", 10L);
        Recorder sink = new Recorder();

        AgentExecutor.ExecHandle handle = executor.start(new AgentExecutor.ExecRequest(
                "r", "n", 0, 7L, 1L, "sys", "ignored", null,
                List.of("/bin/sh", "-c", "echo 第一行; echo SESSION:s-9; echo 第二行"),
                Map.of()), sink);

        assertThat(handle.await(Duration.ofSeconds(10))).isEqualTo(AgentExecutor.ExecOutcome.OK);
        assertThat(sink.texts).anySatisfy(t -> assertThat(t).contains("第一行"));
        assertThat(sink.sessionId).isEqualTo("s-9");
        assertThat(sink.finished).isEqualTo(AgentExecutor.ExecOutcome.OK);
    }

    @Test
    @DisplayName("非 0 退出码：判失败，不当成未知")
    void nonZeroExitIsFailure() throws Exception {
        ReflectionTestUtils.setField(executor, "timeoutSeconds", 10L);
        Recorder sink = new Recorder();

        AgentExecutor.ExecHandle handle = executor.start(new AgentExecutor.ExecRequest(
                "r", "n", 0, 7L, 1L, "sys", "x", null,
                List.of("/bin/sh", "-c", "exit 3"), Map.of()), sink);

        assertThat(handle.await(Duration.ofSeconds(10))).isEqualTo(AgentExecutor.ExecOutcome.FAILED);
    }


    @Test
    @DisplayName("dsh 执行器：不配命令时用 headless 默认模板，并带上会话号")
    void dshHasDefaultCommand() {
        DshExecutor dsh = new DshExecutor();
        assertThat(dsh.kind()).isEqualTo("dsh");

        List<String> argv = dsh.buildArgv(new AgentExecutor.ExecRequest(
                "r", "n", 1, 7L, 1L, null, "扫一遍", "sess-7", null, Map.of()));

        assertThat(argv).containsExactly("dsh", "--profile", "headless", "--json",
                "--session-id", "sess-7", "扫一遍");
    }

    private static final class Recorder implements AgentExecutor.ExecSink {
        final List<String> texts = new java.util.ArrayList<>();
        String sessionId;
        AgentExecutor.ExecOutcome finished;

        @Override
        public void onSession(String executorSessionId) {
            this.sessionId = executorSessionId;
        }

        @Override
        public void onText(String delta) {
            texts.add(delta);
        }

        @Override
        public void onThinking(String delta) {
        }

        @Override
        public void onToolCall(String callId, String toolName, String argsJson) {
        }

        @Override
        public void onToolResult(String callId, String resultJson, boolean isError) {
        }

        @Override
        public void onFinish(AgentExecutor.ExecOutcome outcome, String summary) {
            this.finished = outcome;
        }
    }
}
