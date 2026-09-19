package com.bu.management.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.util.StringUtils;

/**
 * 把周会纪要链接写入汇总表目标行的 K 列（第 11 列）。
 * 支持 Markdown 表、HTML 表、JSON 网格。
 */
public final class WeeklyReportSheetPatcher {

    private static final int K_INDEX = 10;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WeeklyReportSheetPatcher() {}

    public static String patch(String body, String sheetName, String dateRangeLabel,
                               String teamName, String cellValue) {
        if (!StringUtils.hasText(body)) {
            throw new IllegalStateException("汇总表内容为空，无法回填");
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return patchJson(trimmed, dateRangeLabel, teamName, cellValue);
        }
        if (trimmed.contains("<table") || trimmed.contains("<tr") || trimmed.contains("<td")) {
            return patchHtml(body, dateRangeLabel, teamName, cellValue);
        }
        return patchMarkdown(body, dateRangeLabel, teamName, cellValue);
    }

    private static String patchMarkdown(String body, String dateRangeLabel,
                                        String teamName, String cellValue) {
        String[] lines = body.split("\n", -1);
        boolean patched = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.contains("|") || !matchesWeek(line, dateRangeLabel)) {
                continue;
            }
            if (StringUtils.hasText(teamName) && !line.contains(teamName) && cells(line).length > 2) {
                continue;
            }
            lines[i] = replaceMarkdownCell(line, cellValue);
            patched = true;
            break;
        }
        if (!patched) {
            throw missingRow(dateRangeLabel);
        }
        return String.join("\n", lines);
    }

    private static String replaceMarkdownCell(String line, String cellValue) {
        boolean leading = line.startsWith("|");
        boolean trailing = line.endsWith("|");
        String[] raw = cells(line);
        String[] next = new String[Math.max(raw.length, K_INDEX + 1)];
        System.arraycopy(raw, 0, next, 0, raw.length);
        for (int i = 0; i < next.length; i++) {
            if (next[i] == null) next[i] = "";
        }
        next[K_INDEX] = " " + cellValue + " ";
        StringBuilder sb = new StringBuilder();
        if (leading) sb.append('|');
        for (int i = 0; i < next.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(next[i].isBlank() ? "  " : next[i]);
        }
        if (trailing || leading) sb.append('|');
        return sb.toString();
    }

    private static String[] cells(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed.split("\\|", -1);
    }

    private static String patchHtml(String body, String dateRangeLabel,
                                    String teamName, String cellValue) {
        java.util.regex.Pattern rowPattern = java.util.regex.Pattern.compile(
                "(?is)(<tr\\b[^>]*>)(.*?)(</tr>)");
        java.util.regex.Matcher matcher = rowPattern.matcher(body);
        StringBuffer out = new StringBuffer();
        boolean patched = false;
        while (matcher.find()) {
            String inner = matcher.group(2);
            if (!patched && matchesWeek(inner, dateRangeLabel)
                    && (!StringUtils.hasText(teamName) || inner.contains(teamName))) {
                inner = replaceHtmlCell(inner, cellValue);
                patched = true;
            }
            matcher.appendReplacement(out,
                    java.util.regex.Matcher.quoteReplacement(matcher.group(1) + inner + matcher.group(3)));
        }
        matcher.appendTail(out);
        if (!patched) {
            throw missingRow(dateRangeLabel);
        }
        return out.toString();
    }

    private static String replaceHtmlCell(String inner, String cellValue) {
        java.util.regex.Pattern cellPattern = java.util.regex.Pattern.compile(
                "(?is)(<t[dh]\\b[^>]*>)(.*?)(</t[dh]>)");
        java.util.regex.Matcher matcher = cellPattern.matcher(inner);
        StringBuffer out = new StringBuffer();
        int index = 0;
        boolean replaced = false;
        while (matcher.find()) {
            String content = matcher.group(2);
            if (index == K_INDEX) {
                content = cellValue;
                replaced = true;
            }
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                    matcher.group(1) + content + matcher.group(3)));
            index++;
        }
        matcher.appendTail(out);
        if (!replaced) {
            out.append("<td>").append(cellValue).append("</td>");
        }
        return out.toString();
    }

    private static String patchJson(String body, String dateRangeLabel,
                                    String teamName, String cellValue) {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (!patchJsonNode(root, dateRangeLabel, teamName, cellValue)) {
                throw missingRow(dateRangeLabel);
            }
            return MAPPER.writeValueAsString(root);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("汇总表 JSON 无法解析，无法回填", e);
        }
    }

    private static boolean patchJsonNode(JsonNode node, String dateRangeLabel,
                                         String teamName, String cellValue) {
        if (node == null) return false;
        if (node.isArray()) {
            if (patchJsonGrid((ArrayNode) node, dateRangeLabel, teamName, cellValue)) {
                return true;
            }
            for (JsonNode child : node) {
                if (patchJsonNode(child, dateRangeLabel, teamName, cellValue)) return true;
            }
            return false;
        }
        if (node.isObject()) {
            for (JsonNode child : node) {
                if (patchJsonNode(child, dateRangeLabel, teamName, cellValue)) return true;
            }
        }
        return false;
    }

    private static boolean patchJsonGrid(ArrayNode grid, String dateRangeLabel,
                                         String teamName, String cellValue) {
        for (int i = 0; i < grid.size(); i++) {
            JsonNode row = grid.get(i);
            if (!row.isArray() || row.size() == 0) continue;
            String joined = row.toString();
            if (!matchesWeek(joined, dateRangeLabel)) continue;
            if (StringUtils.hasText(teamName) && !joined.contains(teamName) && row.size() > 2) {
                continue;
            }
            ArrayNode writable = (ArrayNode) row;
            while (writable.size() <= K_INDEX) {
                writable.add("");
            }
            writable.set(K_INDEX, TextNode.valueOf(cellValue));
            return true;
        }
        return false;
    }

    private static boolean matchesWeek(String text, String dateRangeLabel) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(dateRangeLabel)) return false;
        String compact = compact(text);
        String target = compact(dateRangeLabel);
        return compact.contains(target);
    }

    private static String compact(String value) {
        return value.replace(" ", "")
                .replace("–", "-")
                .replace("—", "-")
                .replace("/", ".");
    }

    private static IllegalStateException missingRow(String dateRangeLabel) {
        return new IllegalStateException("汇总表未找到 " + dateRangeLabel + " 行，无法回填");
    }
}
