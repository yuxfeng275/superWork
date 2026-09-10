package com.bu.management.vo;

import java.time.LocalDate;

/**
 * 邮件管理「本月价值」面板：北极星 = 待办闭环率。
 * converted: 本月转化总数（转任务/事项/大事儿）
 * closed:    本月转化中已完成闭环数
 * closeRate: closed/converted（0-1，converted=0 时为 null）
 * digests:   本月有摘要的天数
 * useful / useless: 摘要反馈计数
 * avgResponseMinutes: 收到 → 首次回复/转化的平均时长（分钟，无法计算为 null）
 */
public record EmailActionMetrics(
        LocalDate monthStart,
        long converted,
        long closed,
        Double closeRate,
        long digests,
        long useful,
        long useless,
        Double avgResponseMinutes) {}
