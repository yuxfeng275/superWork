package com.bu.management.vo;

import com.bu.management.entity.WeeklyReport;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class WeeklyReportVO {
    private Long id;
    private LocalDate weekStartDate;
    private LocalDate periodEndDate;
    private String wecomSummary;
    private String manualNotes;
    private String coreWork;
    private String kpiSection;
    private String risks;
    private String nextWeekPlan;
    private String minutesMarkdown;
    private String status;
    private String generationModel;
    private String generationMode;
    private String generationError;
    private Integer yuqueDocId;
    private String yuqueDocSlug;
    private String yuqueDocUrl;
    private String yuqueTocStatus;
    private String sheetSyncStatus;
    private LocalDateTime sheetSyncedAt;
    private String wecomPushStatus;
    private LocalDateTime wecomPushedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 状态非 PUBLISHED 时可编辑 */
    private boolean editable;
    /** 事实预览：前 5 条大事儿标题+状态 */
    private List<Map<String, Object>> factsPreview;
    /** 汇总表回填目标信息 */
    private Map<String, Object> sheetTargetInfo;

    public static WeeklyReportVO from(WeeklyReport report) {
        WeeklyReportVO vo = new WeeklyReportVO();
        vo.setId(report.getId());
        vo.setWeekStartDate(report.getWeekStartDate());
        vo.setPeriodEndDate(report.getPeriodEndDate());
        vo.setWecomSummary(report.getWecomSummary());
        vo.setManualNotes(report.getManualNotes());
        vo.setCoreWork(report.getCoreWork());
        vo.setKpiSection(report.getKpiSection());
        vo.setRisks(report.getRisks());
        vo.setNextWeekPlan(report.getNextWeekPlan());
        vo.setMinutesMarkdown(report.getMinutesMarkdown());
        vo.setStatus(report.getStatus());
        vo.setGenerationModel(report.getGenerationModel());
        vo.setGenerationMode(report.getGenerationMode());
        vo.setGenerationError(report.getGenerationError());
        vo.setYuqueDocId(report.getYuqueDocId());
        vo.setYuqueDocSlug(report.getYuqueDocSlug());
        vo.setYuqueDocUrl(report.getYuqueDocUrl());
        vo.setYuqueTocStatus(report.getYuqueTocStatus());
        vo.setSheetSyncStatus(report.getSheetSyncStatus());
        vo.setSheetSyncedAt(report.getSheetSyncedAt());
        vo.setWecomPushStatus(report.getWecomPushStatus());
        vo.setWecomPushedAt(report.getWecomPushedAt());
        vo.setCreatedAt(report.getCreatedAt());
        vo.setUpdatedAt(report.getUpdatedAt());
        vo.setEditable(!WeeklyReport.STATUS_PUBLISHED.equals(report.getStatus()));
        return vo;
    }
}
