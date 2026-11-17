package com.bu.management.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 事实入库前的保守脱敏：五类敏感数据在落库那一刻被替换成占位符，原文绝不入库。
 *
 * <p>五类规则（宁漏不误伤，看不懂的一律不脱）：
 * <ol>
 *   <li>手机号：{@code 1[3-9]\\d{9}}，前后不得再贴数字；</li>
 *   <li>身份证：18 位且必须通过 GB 11643 校验位，校验不过按普通数字放过；</li>
 *   <li>银行卡：16~19 位且必须通过 Luhn 校验，全同数字（占位号）不算；</li>
 *   <li>邮箱：只动 @ 前的局部，保留首尾字符、中间打 *；局部只有 1~2 个字符时整体记 {@code [邮箱]}；</li>
 *   <li>金额：只有「工资 / 薪资 / 月薪 / payroll / 年薪」前后 30 字范围内的「数字 + 元」才替换。</li>
 * </ol>
 *
 * <p>订单号、项目编码、合同编号一律不脱：纯数字的业务编号多数过不了 Luhn / 校验位，
 * 余下的再由 {@link #NO_MASK_KEYWORDS} 这类上下文词兜住。日志与异常只记命中条数，绝不打印原文。
 *
 * <p>开关前缀 {@code fact-privacy.redaction.*}：{@code enabled} 总开关 + 五类分开关，默认全开，
 * 默认值直接写在 {@link Value} 里，不依赖 application.yml。
 */
@Slf4j
@Service
public class FactRedactionService {

    /** 占位符：五类统一口径，前端与文档都以它为准。 */
    static final String PHONE_PLACEHOLDER = "[手机号]";
    static final String ID_CARD_PLACEHOLDER = "[身份证]";
    static final String BANK_CARD_PLACEHOLDER = "[银行卡]";
    static final String EMAIL_PLACEHOLDER = "[邮箱]";
    static final String AMOUNT_PLACEHOLDER = "[金额]";

    /** 手机号：11 位大陆号段，前后不贴数字（避免命中订单号片段）。 */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    /** 身份证：18 位，末位可为 X，前后不贴数字。 */
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    /** 银行卡：16~19 位纯数字。 */
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
    /** 邮箱：局部 + 域名。 */
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    /** 金额：数字（允许千分位、两位小数与万字单位）+ 元。 */
    private static final Pattern AMOUNT = Pattern.compile("\\d[\\d,]*(?:\\.\\d{1,2})?\\s*[万千]?\\s*元");

    /** 金额只在这些词前后 {@link #AMOUNT_WINDOW} 字内才认。 */
    private static final List<String> AMOUNT_KEYWORDS =
            List.of("工资", "薪资", "月薪", "payroll", "年薪");
    /** 金额关键词的匹配半径。 */
    private static final int AMOUNT_WINDOW = 30;

    /** 这些业务编号上下文里的数字一律不脱。 */
    private static final List<String> NO_MASK_KEYWORDS =
            List.of("订单号", "订单", "项目编码", "编码", "编号", "合同号", "合同编号", "流水号", "工号");
    /** 上下文词向前回看的字数。 */
    private static final int NO_MASK_CONTEXT = 6;

    /** GB 11643 加权因子。 */
    private static final int[] ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    /** GB 11643 校验码表（sum % 11 索引）。 */
    private static final char[] ID_CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    @Value("${fact-privacy.redaction.enabled:true}")
    private boolean enabled = true;

    @Value("${fact-privacy.redaction.phone:true}")
    private boolean phoneEnabled = true;

    @Value("${fact-privacy.redaction.id-card:true}")
    private boolean idCardEnabled = true;

    @Value("${fact-privacy.redaction.bank-card:true}")
    private boolean bankCardEnabled = true;

    @Value("${fact-privacy.redaction.email:true}")
    private boolean emailEnabled = true;

    @Value("${fact-privacy.redaction.amount:true}")
    private boolean amountEnabled = true;

    /** 一段待替换区间；placeholder 为 null 表示原样保留。 */
    private record Span(int start, int end, String placeholder) {
    }

    /**
     * 脱敏一段文本：五类命中项统一换成占位符，未命中原样返回。
     *
     * <p>null / 空串原样返回；总开关关掉时直接透传（只记一行 debug，不记内容）。
     *
     * @param text 原文（事实的 title / body）
     * @return 脱敏后的文本；入参为 null 时返回 null
     */
    public String redact(String text) {
        if (!enabled || text == null || text.isEmpty()) {
            return text;
        }
        List<Span> spans = new ArrayList<>();
        if (phoneEnabled) {
            collect(PHONE, text, PHONE_PLACEHOLDER, spans);
        }
        if (idCardEnabled) {
            collectIdCards(text, spans);
        }
        if (bankCardEnabled) {
            collectBankCards(text, spans);
        }
        if (emailEnabled) {
            collectEmails(text, spans);
        }
        if (amountEnabled) {
            collectAmounts(text, spans);
        }
        if (spans.isEmpty()) {
            return text;
        }
        spans.sort(Comparator.comparingInt(Span::start));
        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        for (Span span : spans) {
            if (span.start() < cursor) {
                continue;
            }
            out.append(text, cursor, span.start());
            out.append(span.placeholder() == null
                    ? text.substring(span.start(), span.end())
                    : span.placeholder());
            cursor = span.end();
        }
        out.append(text, cursor, text.length());
        log.debug("事实脱敏：命中 {} 处", spans.size());
        return out.toString();
    }

    // ==================== 各类判定 ====================

    private void collect(Pattern pattern, String text, String placeholder, List<Span> spans) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (isBusinessCode(text, matcher.start()) || overlaps(spans, matcher.start(), matcher.end())) {
                continue;
            }
            spans.add(new Span(matcher.start(), matcher.end(), placeholder));
        }
    }

    private void collectIdCards(String text, List<Span> spans) {
        Matcher matcher = ID_CARD.matcher(text);
        while (matcher.find()) {
            if (isBusinessCode(text, matcher.start())
                    || overlaps(spans, matcher.start(), matcher.end())
                    || !validIdCard(matcher.group())) {
                continue;
            }
            spans.add(new Span(matcher.start(), matcher.end(), ID_CARD_PLACEHOLDER));
        }
    }

    private void collectBankCards(String text, List<Span> spans) {
        Matcher matcher = BANK_CARD.matcher(text);
        while (matcher.find()) {
            if (isBusinessCode(text, matcher.start())
                    || overlaps(spans, matcher.start(), matcher.end())
                    || !validLuhn(matcher.group())) {
                continue;
            }
            spans.add(new Span(matcher.start(), matcher.end(), BANK_CARD_PLACEHOLDER));
        }
    }

    private void collectEmails(String text, List<Span> spans) {
        Matcher matcher = EMAIL.matcher(text);
        while (matcher.find()) {
            if (overlaps(spans, matcher.start(), matcher.end())) {
                continue;
            }
            String address = matcher.group();
            int at = address.indexOf('@');
            String local = address.substring(0, at);
            String domain = address.substring(at);
            if (local.length() <= 2) {
                // 首尾即全部，留下等于没脱，整体记占位符
                spans.add(new Span(matcher.start(), matcher.end(), EMAIL_PLACEHOLDER));
                continue;
            }
            String masked = local.charAt(0) + "*".repeat(local.length() - 2)
                    + local.charAt(local.length() - 1) + domain;
            spans.add(new Span(matcher.start(), matcher.end(), masked));
        }
    }

    /** 金额：只在薪酬关键词附近 30 字内认「数字 + 元」。 */
    private void collectAmounts(String text, List<Span> spans) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : AMOUNT_KEYWORDS) {
            String needle = keyword.toLowerCase(Locale.ROOT);
            int from = 0;
            while (true) {
                int index = lower.indexOf(needle, from);
                if (index < 0) {
                    break;
                }
                from = index + needle.length();
                int windowStart = Math.max(0, index - AMOUNT_WINDOW);
                int windowEnd = Math.min(text.length(), index + needle.length() + AMOUNT_WINDOW);
                Matcher matcher = AMOUNT.matcher(text);
                matcher.region(windowStart, windowEnd);
                while (matcher.find()) {
                    if (overlaps(spans, matcher.start(), matcher.end())) {
                        continue;
                    }
                    spans.add(new Span(matcher.start(), matcher.end(), AMOUNT_PLACEHOLDER));
                }
            }
        }
    }

    // ==================== 工具 ====================

    /** 命中位置前方紧邻订单号 / 编码 / 编号这类词时不脱。 */
    private static boolean isBusinessCode(String text, int start) {
        int from = Math.max(0, start - NO_MASK_CONTEXT);
        String head = text.substring(from, start);
        for (String keyword : NO_MASK_KEYWORDS) {
            if (head.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static boolean overlaps(List<Span> spans, int start, int end) {
        for (Span span : spans) {
            if (start < span.end() && span.start() < end) {
                return true;
            }
        }
        return false;
    }

    /** GB 11643 校验位：加权求和取模 11 对齐校验码表。 */
    static boolean validIdCard(String value) {
        if (value == null || value.length() != 18) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            sum += (c - '0') * ID_WEIGHTS[i];
        }
        char last = Character.toUpperCase(value.charAt(17));
        return last == ID_CHECK_CODES[sum % 11];
    }

    /** Luhn 校验：银行卡号必须过；全同数字视为占位号，不算。 */
    static boolean validLuhn(String digits) {
        if (digits == null || digits.length() < 16 || digits.length() > 19) {
            return false;
        }
        boolean sameDigit = true;
        int sum = 0;
        boolean doubled = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            if (c != digits.charAt(0)) {
                sameDigit = false;
            }
            int digit = c - '0';
            if (doubled) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubled = !doubled;
        }
        return !sameDigit && sum % 10 == 0;
    }
}
