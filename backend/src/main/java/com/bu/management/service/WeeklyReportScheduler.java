package com.bu.management.service;

import com.bu.management.entity.WeeklyReport;
import com.bu.management.integration.WeComClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 周五 17:00 自动生成本周周报草稿，并企微提醒负责人审阅。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class WeeklyReportScheduler {

    private final WeeklyReportService reportService;
    private final WeComClient weComClient;

    @Value("${weekly.report-notify-wecom-user:}")
    private String notifyWeComUser;

    @Value("${app.public-base-url:}")
    private String publicBaseUrl;

    @Scheduled(cron = "${weekly.report-cron:0 0 17 * * FRI}", zone = "Asia/Shanghai")
    public void generateWeekly() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        if (today.getDayOfWeek() != DayOfWeek.FRIDAY) {
            return;
        }
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        try {
            WeeklyReport report = reportService.generateAsync(weekStart);
            if (report != null && StringUtils.hasText(notifyWeComUser)) {
                String link = StringUtils.hasText(publicBaseUrl)
                        ? publicBaseUrl.replaceAll("/+$", "") + "/weekly-report"
                        : "/weekly-report";
                weComClient.pushText(notifyWeComUser,
                        "本周周报草稿已生成，请登录系统审阅并确认：\n" + link);
            }
        } catch (Exception e) {
            log.error("周五周报自动生成失败", e);
        }
    }
}
