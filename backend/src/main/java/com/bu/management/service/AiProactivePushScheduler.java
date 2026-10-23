package com.bu.management.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 工作日早上把「今天最值得做的几件事」推到企业微信：用户不登录系统也能被温和地提醒到。
 * 关闭开关：ai.proactive.push-enabled=false；时间：ai.proactive.push-cron。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiProactivePushScheduler {

    private final AiProactivePushService pushService;

    @Value("${ai.proactive.push-enabled:true}")
    private boolean enabled;

    @Scheduled(cron = "${ai.proactive.push-cron:0 50 8 * * MON-FRI}", zone = "Asia/Shanghai")
    public void pushMorningBriefing() {
        if (!enabled) {
            return;
        }
        try {
            pushService.pushDaily();
        } catch (Exception e) {
            log.error("AI 主动建议早间推送失败", e);
        }
    }
}
