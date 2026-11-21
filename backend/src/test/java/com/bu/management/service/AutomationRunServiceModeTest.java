package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 产出模式的契约：只有夜间批才要求写晨报，其它模式不许带「先写晨报」的产出要求。
 *
 * <p>这条契约是 2026-11-21 事故的直接对策：试跑规则被公共提示词要求写晨报，
 * 结果覆盖了夜间批写入的次日晨报。
 */
class AutomationRunServiceModeTest {

    @Test
    @DisplayName("缺省与未知模式都按夜间批处理（旧规则行为不变）")
    void defaultsToNightBrief() {
        assertThat(AutomationRunService.Mode.from(null)).isEqualTo(AutomationRunService.Mode.NIGHT_BRIEF);
        assertThat(AutomationRunService.Mode.from("")).isEqualTo(AutomationRunService.Mode.NIGHT_BRIEF);
        assertThat(AutomationRunService.Mode.from("unknown-mode"))
                .isEqualTo(AutomationRunService.Mode.NIGHT_BRIEF);
        assertThat(AutomationRunService.Mode.from("night_brief"))
                .isEqualTo(AutomationRunService.Mode.NIGHT_BRIEF);
        assertThat(AutomationRunService.Mode.from("plan")).isEqualTo(AutomationRunService.Mode.PLAN);
    }

    @Test
    @DisplayName("夜间批：要求写明天晨报，并写明日期")
    void nightBriefAsksForTomorrowBrief() {
        String prompt = AutomationRunService.systemPrompt(
                AutomationRunService.Mode.NIGHT_BRIEF, 1L, null);
        assertThat(prompt).contains("save_daily_brief");
        assertThat(prompt).contains("明天");
        assertThat(prompt).contains("目标项目 ID = 1");
        // 铁律仍在
        assertThat(prompt).contains("project_facts");
        assertThat(prompt).contains("绝不对外发送");
    }

    @Test
    @DisplayName("方案 / 补丁 / 巡检：不带写晨报的产出要求")
    void nonBriefModesNeverAskForBrief() {
        for (AutomationRunService.Mode mode : new AutomationRunService.Mode[] {
                AutomationRunService.Mode.PLAN,
                AutomationRunService.Mode.PATCH,
                AutomationRunService.Mode.CHECK}) {
            String prompt = AutomationRunService.systemPrompt(mode, 1L, null);
            assertThat(prompt).as(mode + " 不应要求调用 save_daily_brief")
                    .doesNotContain("先用 save_daily_brief");
            assertThat(prompt).as(mode + " 应自带本轮产出说明").isNotBlank();
            // 非晨报模式不写「明天」，避免模型自己编日期
            assertThat(prompt).as(mode + " 不应出现明天").doesNotContain("明天");
        }
        assertThat(AutomationRunService.systemPrompt(AutomationRunService.Mode.PLAN, 1L, null))
                .contains("save_artifact");
        assertThat(AutomationRunService.systemPrompt(AutomationRunService.Mode.CHECK, 1L, null))
                .contains("只做只读检查");
    }

    @Test
    @DisplayName("自定义模式：用调用方给的产出口径替换默认段落")
    void customOverrideReplacesOutputClause() {
        String prompt = AutomationRunService.systemPrompt(AutomationRunService.Mode.CUSTOM, 2L,
                "产出：只输出一段排查结论，不写任何业务数据。");
        assertThat(prompt).contains("产出：只输出一段排查结论");
        assertThat(prompt).doesNotContain("先用 save_daily_brief");
        assertThat(prompt).contains("目标项目 ID = 2");
    }

    @Test
    @DisplayName("无项目时明确要求不要猜 projectId")
    void noProjectMeansDoNotGuess() {
        String prompt = AutomationRunService.systemPrompt(
                AutomationRunService.Mode.NIGHT_BRIEF, null, null);
        assertThat(prompt).contains("不要猜测 projectId");
    }
}
