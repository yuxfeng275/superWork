package com.bu.management.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 会议模块运行时配置：system_config 组 meeting（模块开关 / 音频上限 / ASR 连接）。
 * 未启用或缺 key 不在此抛错，由调用方决定行为（如转写前校验）。
 */
@Service
@RequiredArgsConstructor
public class MeetingConfigService {

    private static final String GROUP = "meeting";

    private final SystemConfigService systemConfigService;

    /**
     * 运行时配置快照；token 为 PASSWORD 型，SystemConfigService 已自动解密。
     */
    public record MeetingRuntimeConfig(boolean enabled, String asrBaseUrl, String asrToken,
            int asrTimeoutSeconds, String asrHotwords, int audioMaxSizeMb) {
    }

    public MeetingRuntimeConfig load() {
        return new MeetingRuntimeConfig(
                systemConfigService.getBoolean(GROUP, "meeting.enabled", false),
                systemConfigService.getValue(GROUP, "meeting.asr.base-url", ""),
                systemConfigService.getValue(GROUP, "meeting.asr.token", ""),
                intOrDefault(systemConfigService.getValue(GROUP, "meeting.asr.timeout-seconds", "3600"), 3600),
                systemConfigService.getValue(GROUP, "meeting.asr.hotwords", ""),
                intOrDefault(systemConfigService.getValue(GROUP, "meeting.audio.max-size-mb", "100"), 100));
    }

    private static int intOrDefault(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
