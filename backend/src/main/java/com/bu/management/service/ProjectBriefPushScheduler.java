package com.bu.management.service;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 工作日 07:30 把夜间产出的晨报推给每个人（一人一条）。
 * 关闭开关：project.brief.push-enabled=false；时间：project.brief.push-cron。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectBriefPushScheduler {

    private final ProjectBriefPushService pushService;

    @Value("${project.brief.push-enabled:true}")
    private boolean enabled;

    @Scheduled(cron = "${project.brief.push-cron:0 30 7 * * MON-FRI}", zone = "Asia/Shanghai")
    public void pushMorningBriefs() {
        if (!enabled) {
            return;
        }
        try {
            int sent = pushService.pushDate(LocalDate.now());
            if (sent > 0) {
                log.info("晨报已推送 {} 人", sent);
            }
        } catch (Exception e) {
            log.error("晨报推送失败", e);
        }
    }
}
