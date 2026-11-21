package com.bu.management.executor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 命令行执行器：把「一个命令 + 提示词」当成一轮 Agent 执行。
 *
 * <p>它存在的两个真实用途：
 * <ul>
 *   <li>接 dsh：{@code dsh --profile headless --json --session-id <id> <prompt>}，
 *       有会话号就能续跑（{@link Capability#RESUME}）；</li>
 *   <li>接脚本类节点：{@code tools/m2/nightly.sh {prompt}}，此时只输出文本、不承诺结构化。</li>
 * </ul>
 *
 * <p>刻意不做的事：不解析各家的 JSON 协议。标准输出按行抛给 sink，
 * 需要结构化时由具体通道自行加适配，避免这里长成一个协议动物园。
 *
 * @author BU Team
 * @since 2026-11-21
 */
@Slf4j
@Component
public class CommandExecutor implements AgentExecutor {

    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "cmd-executor");
        t.setDaemon(true);
        return t;
    });

    /** 单轮上限：超时一律 UNKNOWN（可能已经写了一半），不当作失败重试。 */
    @Value("${ai.executor.cli.timeout-seconds:900}")
    private long timeoutSeconds;

    @Override
    public String kind() {
        return "cli";
    }

    @Override
    public java.util.Set<Capability> capabilities() {
        return java.util.Set.of(Capability.RESUME);
    }

    @Override
    public ExecHandle start(ExecRequest request, ExecSink sink) {
        List<String> argv = buildArgv(request);
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("cli 执行器缺少 command 模板");
        }
        AtomicReference<Process> processRef = new AtomicReference<>();
        AtomicReference<ExecOutcome> finished = new AtomicReference<>(null);

        CompletableFuture<ExecOutcome> future = CompletableFuture.supplyAsync(() -> {
            try {
                ProcessBuilder builder = new ProcessBuilder(argv);
                if (request.env() != null) {
                    builder.environment().putAll(request.env());
                }
                Process process = builder.start();
                processRef.set(process);
                // 标准输出按行喂给 sink；错误流单独读，避免管道写满把子进程卡死
                Thread errReader = new Thread(() -> drain(process.getErrorStream(), true));
                errReader.setDaemon(true);
                errReader.start();
                try (BufferedReader out = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = out.readLine()) != null) {
                        if (line.startsWith("SESSION:")) {
                            sink.onSession(line.substring("SESSION:".length()).trim());
                        } else {
                            sink.onText(line + "\n");
                        }
                    }
                }
                boolean done = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!done) {
                    process.destroyForcibly();
                    finished.set(ExecOutcome.UNKNOWN);
                } else {
                    finished.set(process.exitValue() == 0 ? ExecOutcome.OK : ExecOutcome.FAILED);
                }
                sink.onFinish(finished.get(), "exit=" + (done ? process.exitValue() : "timeout"));
                return finished.get();
            } catch (Exception e) {
                log.warn("cli 执行失败：{}", e.getMessage());
                finished.set(ExecOutcome.FAILED);
                sink.onFinish(ExecOutcome.FAILED, e.getMessage());
                return ExecOutcome.FAILED;
            }
        }, pool);

        return new ExecHandle() {
            @Override
            public String sessionId() {
                return request.resumeSessionId();
            }

            @Override
            public ExecOutcome await(Duration timeout) throws InterruptedException {
                try {
                    return future.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    return ExecOutcome.UNKNOWN;
                } catch (java.util.concurrent.ExecutionException e) {
                    return ExecOutcome.FAILED;
                }
            }

            @Override
            public void cancel() {
                Process process = processRef.get();
                if (process != null) {
                    process.destroyForcibly();
                }
            }
        };
    }

    /** 替换 {prompt} / {systemPrompt} / {sessionId}；会话号为空时整段参数省略。 */
    List<String> buildArgv(ExecRequest request) {
        List<String> out = new ArrayList<>();
        List<String> template = request.command() == null ? defaultCommand() : request.command();
        if (template == null) {
            return out;
        }
        for (String raw : template) {
            if (raw == null) {
                continue;
            }
            if (raw.contains("{sessionId}") && !hasText(request.resumeSessionId())) {
                // 会话号为空时，连它前面的参数名一起省掉（否则会留下一个没有值的 --session-id）
                if (!out.isEmpty() && out.get(out.size() - 1).startsWith("-")) {
                    out.remove(out.size() - 1);
                }
                continue;
            }
            out.add(raw
                    .replace("{prompt}", request.prompt() == null ? "" : request.prompt())
                    .replace("{systemPrompt}", request.systemPrompt() == null ? "" : request.systemPrompt())
                    .replace("{sessionId}", request.resumeSessionId() == null ? "" : request.resumeSessionId()));
        }
        return out;
    }

    /** 子类可给一套默认命令模板（例如 dsh 的 headless 调用）。 */
    protected List<String> defaultCommand() {
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void drain(java.io.InputStream in, boolean stderr) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (stderr) {
                    log.debug("[cli stderr] {}", line);
                }
            }
        } catch (Exception ignored) {
            // 子进程被强杀时读流会抛错，这里吞掉即可
        }
    }
}
