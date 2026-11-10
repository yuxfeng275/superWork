package com.bu.management;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * AI 平台地基的守护测试（不依赖 Spring 上下文：直接扫源码与配置，跑得飞快，适合放在每次提交之前）。
 *
 * <p>守三件事：
 * <ol>
 *   <li>G1 调度线程池必须显式配置且大于 1（Spring 默认单线程，19 个定时任务会互相阻塞）；</li>
 *   <li>G6 {@code @Transactional} 方法体内不得直接调 LLM（异步/长事务会抢在提交前查不到行，我们踩过）；</li>
 *   <li>能力 id 全局唯一（重复 id 会让网关路由错乱）。</li>
 * </ol>
 */
class AiPlatformGuardTest {

    /** 模块根目录：surefire 的工作目录就是 backend。 */
    private static final Path ROOT = Path.of("src/main");

    @Test
    void G1_调度线程池必须大于1() throws IOException {
        String yml = Files.readString(ROOT.resolve("resources/application.yml"));
        // 不引 YAML 解析：直接找 scheduling 段后的 size（本项目 application.yml 里只有这一处 task.scheduling）
        int idx = yml.indexOf("scheduling:");
        assertThat(idx).as("application.yml 必须显式配置 spring.task.scheduling").isGreaterThan(-1);
        Matcher m = Pattern.compile("size:\\s*(\\d+)").matcher(yml.substring(idx));
        assertThat(m.find()).as("scheduling 段必须配置 pool.size").isTrue();
        assertThat(Integer.parseInt(m.group(1)))
                .as("调度线程池必须 >1，否则 19 个定时任务互相阻塞")
                .isGreaterThan(1);
    }

    @Test
    void G6_事务方法内不得直接调LLM() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT.resolve("java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(file);
                Matcher m = Pattern.compile("@Transactional[^\\n]*\\n\\s*(?:[a-zA-Z@][^;{]*)\\{").matcher(src);
                while (m.find()) {
                    int bodyStart = m.end();
                    int bodyEnd = matchBrace(src, bodyStart - 1);
                    if (bodyEnd < 0) {
                        continue;
                    }
                    String body = src.substring(bodyStart, bodyEnd);
                    if (body.contains("digestLlmClient.") || body.contains("aiCallRecorder.call(")) {
                        violations.add(file.getFileName() + " 的 @Transactional 方法体里直接调了 LLM");
                    }
                }
            }
        }
        assertThat(violations).as("LLM 调用必须放到事务外（AiTaskService 异步执行）").isEmpty();
    }

    @Test
    void 能力id必须全局唯一() throws IOException {
        Set<String> ids = new HashSet<>();
        List<String> duplicated = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT.resolve("java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(file);
                if (!src.contains("@Capability(")) {
                    continue;
                }
                Matcher m = Pattern.compile("@Capability\\([^)]*?id\\s*=\\s*\"([^\"]+)\"", Pattern.DOTALL).matcher(src);
                while (m.find()) {
                    if (!ids.add(m.group(1))) {
                        duplicated.add(m.group(1));
                    }
                }
            }
        }
        assertThat(duplicated).as("能力 id 重复会让网关路由错乱").isEmpty();
        assertThat(ids).as("至少要有 search_knowledge 这个知识库能力").contains("search_knowledge");
    }

    /** 从 open 位置的 '{' 开始找配对的 '}'；找不到返回 -1。 */
    private static int matchBrace(String src, int open) {
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '\'') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
