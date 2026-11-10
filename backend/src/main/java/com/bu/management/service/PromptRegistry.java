package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.bu.management.entity.AiPrompt;
import com.bu.management.mapper.AiPromptMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Prompt 注册表：业务代码按 code 取提示词，DB 里没配或读失败时退回代码里的兜底文案。
 *
 * <p>设计取舍：</p>
 * <ul>
 *   <li>取「enabled=1 的最大 version」，所以发一段新提示词只要 saveVersion，旧版本自动让位；</li>
 *   <li>进程内缓存（{@code ConcurrentHashMap}），默认 60 秒（{@code ai.platform.prompt-cache-seconds}），
 *       避免每次 LLM 调用都打一次 DB；不做分布式缓存，多实例各自缓存最多 60 秒不一致，可接受；</li>
 *   <li>写操作（saveVersion/enableVersion）主动清掉本进程缓存，别让运营改完还得等一分钟。</li>
 * </ul>
 */
@Slf4j
@Service
public class PromptRegistry {

    /** 缓存项：正文 + 过期时间（毫秒时间戳）。 */
    private record CacheEntry(String content, long expireAtMillis) {}

    private final AiPromptMapper promptMapper;

    /** 缓存有效期（秒）；配置缺失或非正数时按 60 处理。 */
    private final long cacheSeconds;

    /** code -> 缓存项；只缓存「DB 里确实有内容」的结果，兜底文案不进缓存。 */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public PromptRegistry(AiPromptMapper promptMapper,
                          @Value("${ai.platform.prompt-cache-seconds:60}") long cacheSeconds) {
        this.promptMapper = promptMapper;
        this.cacheSeconds = cacheSeconds > 0 ? cacheSeconds : 60;
    }

    /**
     * 取提示词正文：DB 命中返回最新启用版本，未命中/异常/内容为空都返回 fallbackContent。
     *
     * @param code            提示词编码
     * @param fallbackContent 代码里的兜底文案（保证 LLM 永远有 prompt 可用）
     */
    public String get(String code, String fallbackContent) {
        if (!StringUtils.hasText(code)) {
            return fallbackContent;
        }
        CacheEntry entry = cache.get(code);
        if (entry != null && entry.expireAtMillis() > System.currentTimeMillis()) {
            return entry.content();
        }
        try {
            AiPrompt prompt = latestEnabled(code);
            if (prompt == null || !StringUtils.hasText(prompt.getContent())) {
                // 未配置不缓存：运营刚存完新版本应该立刻生效
                return fallbackContent;
            }
            cache.put(code, new CacheEntry(prompt.getContent(),
                    System.currentTimeMillis() + cacheSeconds * 1000L));
            return prompt.getContent();
        } catch (Exception e) {
            // 读库失败不能把主流程拖死：退回兜底文案，只记日志
            log.warn("读取 Prompt 失败 code={}，使用兜底文案: {}", code, e.getMessage());
            return fallbackContent;
        }
    }

    /** 存一个新版本：version = 当前最大版本 + 1，enabled=1；同时清本进程缓存。 */
    public void saveVersion(String code, String content, Long operatorUserId) {
        if (!StringUtils.hasText(code) || !StringUtils.hasText(content)) {
            throw new IllegalArgumentException("Prompt code 与 content 不能为空");
        }
        int nextVersion = maxVersion(code) + 1;
        AiPrompt row = new AiPrompt();
        row.setCode(code);
        row.setVersion(nextVersion);
        row.setContent(content);
        row.setEnabled(1);
        row.setCreatedBy(operatorUserId);
        row.setCreatedAt(LocalDateTime.now());
        promptMapper.insert(row);
        cache.remove(code);
        log.info("新增 Prompt 版本 code={} version={} operator={}", code, nextVersion, operatorUserId);
    }

    /** 启用指定版本：同 code 其它版本置 0，目标版本置 1（保证「最大启用版本」唯一）。 */
    public void enableVersion(String code, int version) {
        if (!StringUtils.hasText(code)) {
            throw new IllegalArgumentException("Prompt code 不能为空");
        }
        promptMapper.update(null, new LambdaUpdateWrapper<AiPrompt>()
                .eq(AiPrompt::getCode, code)
                .set(AiPrompt::getEnabled, 0));
        promptMapper.update(null, new LambdaUpdateWrapper<AiPrompt>()
                .eq(AiPrompt::getCode, code)
                .eq(AiPrompt::getVersion, version)
                .set(AiPrompt::getEnabled, 1));
        cache.remove(code);
        log.info("切换 Prompt 启用版本 code={} version={}", code, version);
    }

    /** 某 code 的全部版本，版本号倒序（新的在前），供管理页列表。 */
    public List<AiPrompt> listVersions(String code) {
        if (!StringUtils.hasText(code)) {
            return List.of();
        }
        List<AiPrompt> rows = promptMapper.selectList(new LambdaQueryWrapper<AiPrompt>()
                .eq(AiPrompt::getCode, code)
                .orderByDesc(AiPrompt::getVersion));
        return rows == null ? new ArrayList<>() : rows;
    }

    /** enabled=1 中 version 最大的那行；没有则 null。 */
    private AiPrompt latestEnabled(String code) {
        List<AiPrompt> rows = promptMapper.selectList(new LambdaQueryWrapper<AiPrompt>()
                .eq(AiPrompt::getCode, code)
                .eq(AiPrompt::getEnabled, 1)
                .orderByDesc(AiPrompt::getVersion));
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        // 不依赖数据库排序稳定性：本地再取一次最大 version
        AiPrompt latest = null;
        for (AiPrompt row : rows) {
            if (row == null || row.getVersion() == null) {
                continue;
            }
            if (latest == null || row.getVersion() > latest.getVersion()) {
                latest = row;
            }
        }
        return latest;
    }

    /** 某 code 当前最大版本号；无记录返回 0（首个版本即 1）。 */
    private int maxVersion(String code) {
        List<AiPrompt> rows = promptMapper.selectList(new LambdaQueryWrapper<AiPrompt>()
                .eq(AiPrompt::getCode, code));
        int max = 0;
        if (rows == null) {
            return max;
        }
        for (AiPrompt row : rows) {
            if (row != null && row.getVersion() != null && row.getVersion() > max) {
                max = row.getVersion();
            }
        }
        return max;
    }
}
