package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WeeklyReportSheetPatcherTest {

    @Test
    void patchesMarkdownKColumnForMatchingWeek() {
        String body = """
                | 周期 | 团队 | C | D | E | F | G | H | I | J | 纪要 |
                | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
                | 09.07-09.11 | 电商业务BU |  |  |  |  |  |  |  |  | 旧链接 |
                | 09.14-09.18 | 电商业务BU |  |  |  |  |  |  |  |  |  |
                """;

        String patched = WeeklyReportSheetPatcher.patch(
                body, "电商业务", "09.14-09.18", "电商业务BU", "https://yuque.example/minutes");

        assertThat(patched).contains("| 09.14-09.18 | 电商业务BU |");
        assertThat(patched).contains("https://yuque.example/minutes");
        assertThat(patched).contains("旧链接");
        assertThat(patched.indexOf("https://yuque.example/minutes"))
                .isGreaterThan(patched.indexOf("09.14-09.18"));
    }

    @Test
    void patchesJsonGridKColumn() {
        String body = """
                {"name":"电商业务","data":[
                  ["周期","团队","C","D","E","F","G","H","I","J","纪要"],
                  ["09.14-09.18","电商业务BU","","","","","","","","",""]
                ]}
                """;

        String patched = WeeklyReportSheetPatcher.patch(
                body, "电商业务", "09.14-09.18", "电商业务BU", "https://yuque.example/minutes");

        assertThat(patched).contains("https://yuque.example/minutes");
        assertThat(patched).contains("09.14-09.18");
    }

    @Test
    void patchesHtmlKColumn() {
        String body = "<table><tr><td>09.14-09.18</td><td>电商业务BU</td><td></td><td></td><td></td><td></td><td></td><td></td><td></td><td></td><td></td></tr></table>";
        String patched = WeeklyReportSheetPatcher.patch(
                body, "电商业务", "09.14-09.18", "电商业务BU", "https://yuque.example/minutes");
        assertThat(patched).contains("<td>https://yuque.example/minutes</td>");
    }

    @Test
    void failsWhenWeekRowMissing() {
        assertThatThrownBy(() -> WeeklyReportSheetPatcher.patch(
                "| 周期 |\n| 08.01-08.05 |", "电商业务", "09.14-09.18", "电商业务BU", "https://x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("09.14-09.18");
    }
}
