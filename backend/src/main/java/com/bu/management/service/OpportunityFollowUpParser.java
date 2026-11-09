package com.bu.management.service;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 商机跟进问句的纯文本解析器：把「人话」解析成结构化跟进信息。
 *
 * <p>刻意做成纯静态、无 Spring 依赖，方便直接单测；也不依赖任何模型调用 —— 解析不出来就返回 null，
 * 由上层决定「只记原文、不推进阶段」，绝不猜。
 *
 * <p>支持的下次跟进时间写法：
 * <ul>
 *   <li>相对日：今天 / 明天 / 后天 / 大后天（可带 {@code HH:mm}）；</li>
 *   <li>星期：周三 / 星期三 / 这周三 / 下周三（可带 {@code HH:mm}）；</li>
 *   <li>月日：{@code 11-12} 或 {@code 11/12}（可带 {@code HH:mm}，跨年自动顺延）；</li>
 *   <li>完整日期：{@code 2026-11-12} 或 {@code 2026/11/12}（可带 {@code HH:mm}）。</li>
 * </ul>
 * 不带时间时默认当天 09:00（上班时间），保证同一条文本解析结果稳定可测。
 *
 * @author BU Team
 * @since 2026-11-20
 */
public final class OpportunityFollowUpParser {

    /** 阶段白名单，必须与 {@code SalesOpportunity.status} 取值一致。 */
    public static final List<String> STAGES = List.of("初步接触", "需求确认", "方案报价", "商务谈判", "已成交");

    /** 解析出的回复草稿；字段可为空表示没解析到，{@code confidence} 为 0.2~1.0 的命中置信度。 */
    public record ReplyDraft(String stage, Integer probability, LocalDateTime nextAt, String summary,
                             double confidence) {
    }

    /** 不带时间时的默认时刻。 */
    private static final LocalTime DEFAULT_TIME = LocalTime.of(9, 0);
    private static final int MAX_SUMMARY = 200;

    private static final Pattern DATE_FULL =
            Pattern.compile("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})");
    private static final Pattern DATE_MONTH_DAY =
            Pattern.compile("(?<!\\d)(\\d{1,2})[-/](\\d{1,2})(?!\\d)");
    private static final Pattern WEEKDAY =
            Pattern.compile("(下|本|这)?\\s*(?:周|星期|礼拜)\\s*([一二三四五六日天1-7])");
    private static final Pattern TIME_COLON =
            Pattern.compile("(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)");
    private static final Pattern TIME_DIAN =
            Pattern.compile("(?<!\\d)(\\d{1,2})[点时](\\d{1,2})?分?");
    private static final Pattern PROBABILITY =
            Pattern.compile("(?:概率|成交率|把握|可能性)?\\s*(\\d{1,3})\\s*(?:%|％|个点)");

    private OpportunityFollowUpParser() {
    }

    /**
     * 解析「下次什么时候跟进」。
     *
     * @param text  人话文本（可以为 null / 垃圾文本）
     * @param today 基准日期，空则取 {@link LocalDate#now()}
     * @return 解析成功的时间；解析不出返回 null
     */
    public static LocalDateTime parseNextFollowUp(String text, LocalDate today) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String raw = normalize(text);
        LocalDate base = today == null ? LocalDate.now() : today;

        Matcher full = DATE_FULL.matcher(raw);
        if (full.find()) {
            LocalDate date = safeDate(Integer.parseInt(full.group(1)),
                    Integer.parseInt(full.group(2)), Integer.parseInt(full.group(3)));
            return date == null ? null : date.atTime(parseTime(raw));
        }

        Matcher monthDay = DATE_MONTH_DAY.matcher(raw);
        if (monthDay.find()) {
            int month = Integer.parseInt(monthDay.group(1));
            int day = Integer.parseInt(monthDay.group(2));
            LocalDate date = safeDate(base.getYear(), month, day);
            if (date != null) {
                if (date.isBefore(base)) {
                    LocalDate nextYear = safeDate(base.getYear() + 1, month, day);
                    date = nextYear == null ? date : nextYear;
                }
                return date.atTime(parseTime(raw));
            }
        }

        LocalDate relative = relativeDate(raw, base);
        if (relative != null) {
            return relative.atTime(parseTime(raw));
        }

        Matcher week = WEEKDAY.matcher(raw);
        if (week.find()) {
            DayOfWeek target = toDayOfWeek(week.group(2));
            if (target == null) {
                return null;
            }
            String prefix = week.group(1);
            LocalDate date;
            if ("下".equals(prefix)) {
                date = base.with(TemporalAdjusters.nextOrSame(target)).plusWeeks(1);
            } else if ("本".equals(prefix) || "这".equals(prefix)) {
                date = base.with(target);
            } else {
                date = base.with(TemporalAdjusters.nextOrSame(target));
            }
            return date.atTime(parseTime(raw));
        }
        return null;
    }

    /**
     * 解析企微回复里的结构化成份。
     *
     * @param text 回复原文
     * @return 草稿；文本为空返回 null（调用方按「只记原文」处理）
     */
    public static ReplyDraft parseReply(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String raw = normalize(text);
        double confidence = 0.2;

        String stage = matchStage(raw);
        if (stage != null) {
            confidence += 0.4;
        }

        Integer probability = matchProbability(raw);
        if (probability != null) {
            confidence += 0.2;
        }

        LocalDateTime nextAt = parseNextFollowUp(raw, LocalDate.now());
        if (nextAt != null) {
            confidence += 0.2;
        }

        String summary = summarize(raw);
        if (summary != null) {
            confidence += 0.2;
        }

        return new ReplyDraft(stage, probability, nextAt, summary, Math.min(1.0, confidence));
    }

    /** 阶段白名单匹配：命中多个时取最靠后（更进阶）的那个，避免「初步接触到需求确认」被判成初步接触。 */
    private static String matchStage(String text) {
        String hit = null;
        int hitIndex = -1;
        for (String stage : STAGES) {
            int index = text.indexOf(stage);
            if (index >= 0 && STAGES.indexOf(stage) > hitIndex) {
                hit = stage;
                hitIndex = STAGES.indexOf(stage);
            }
        }
        return hit;
    }

    /** 概率：只认 {@code 60%} / {@code 概率 60} 这类明确写法，且必须落在 0~100。 */
    private static Integer matchProbability(String text) {
        Matcher matcher = PROBABILITY.matcher(text);
        while (matcher.find()) {
            try {
                int value = Integer.parseInt(matcher.group(1));
                if (value >= 0 && value <= 100) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // 继续找下一个
            }
        }
        return null;
    }

    /** 摘要：第一行非空内容，截断到 200 字。 */
    private static String summarize(String text) {
        for (String line : text.split("\\R")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                return trimmed.length() <= MAX_SUMMARY ? trimmed : trimmed.substring(0, MAX_SUMMARY);
            }
        }
        return null;
    }

    private static LocalDate relativeDate(String text, LocalDate base) {
        if (text.contains("大后天")) {
            return base.plusDays(3);
        }
        if (text.contains("后天")) {
            return base.plusDays(2);
        }
        if (text.contains("明天") || text.contains("明日")) {
            return base.plusDays(1);
        }
        if (text.contains("今天") || text.contains("今日")) {
            return base;
        }
        return null;
    }

    private static LocalTime parseTime(String text) {
        Matcher colon = TIME_COLON.matcher(text);
        if (colon.find()) {
            return safeTime(Integer.parseInt(colon.group(1)), Integer.parseInt(colon.group(2)));
        }
        Matcher dian = TIME_DIAN.matcher(text);
        if (dian.find()) {
            int hour = Integer.parseInt(dian.group(1));
            int minute = dian.group(2) == null ? 0 : Integer.parseInt(dian.group(2));
            return safeTime(hour, minute);
        }
        return DEFAULT_TIME;
    }

    private static LocalTime safeTime(int hour, int minute) {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            return DEFAULT_TIME;
        }
        return LocalTime.of(hour, minute);
    }

    private static LocalDate safeDate(int year, int month, int day) {
        if (month < 1 || month > 12 || day < 1 || day > 31) {
            return null;
        }
        try {
            return LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static DayOfWeek toDayOfWeek(String token) {
        return switch (token) {
            case "一", "1" -> DayOfWeek.MONDAY;
            case "二", "2" -> DayOfWeek.TUESDAY;
            case "三", "3" -> DayOfWeek.WEDNESDAY;
            case "四", "4" -> DayOfWeek.THURSDAY;
            case "五", "5" -> DayOfWeek.FRIDAY;
            case "六", "6" -> DayOfWeek.SATURDAY;
            case "日", "天", "7" -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }

    /** 全角转半角，去掉空白干扰，便于统一匹配。 */
    private static String normalize(String text) {
        return text.replace('：', ':')
                .replace('／', '/')
                .replace('\u3000', ' ')
                .strip();
    }
}
