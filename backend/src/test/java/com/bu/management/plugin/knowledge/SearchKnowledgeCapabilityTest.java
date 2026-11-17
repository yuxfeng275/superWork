package com.bu.management.plugin.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bu.management.capability.CapabilityContext;
import com.bu.management.capability.CapabilityResult;
import com.bu.management.service.KbHit;
import com.bu.management.service.KbSearchException;
import com.bu.management.service.KbSearchService;
import com.bu.management.service.ProjectFactAccessGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 知识检索能力的三个边界：默认只查公司/本人范围、项目范围必须先过准入、
 * 检索故障必须以错误码回给模型而不是空命中。
 */
@ExtendWith(MockitoExtension.class)
class SearchKnowledgeCapabilityTest {

    @Mock
    private KbSearchService kbSearchService;

    @Mock
    private ProjectFactAccessGuard accessGuard;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SearchKnowledgeCapability capability;

    @BeforeEach
    void setUp() {
        capability = new SearchKnowledgeCapability(kbSearchService, accessGuard, objectMapper);
    }

    @Test
    @DisplayName("不传 projectId：按公司/本人范围检索，返回命中与条数")
    void withoutProjectUsesNoneScope() throws Exception {
        when(kbSearchService.search(eq("会员通"), eq(7L), eq(KbSearchService.Scope.none()), eq(5)))
                .thenReturn(List.of(new KbHit(1L, 2L, "会员通方案", "交付周期", "标准交付 6 周", 2.5d)));

        CapabilityResult result = capability.execute(
                objectMapper.readTree("{\"query\":\"会员通\"}"), CapabilityContext.agent(7L, "run-1", false));

        assertThat(result.ok()).isTrue();
        JsonNode data = objectMapper.readTree(result.dataJson());
        assertThat(data.path("count").asInt()).isEqualTo(1);
        assertThat(data.path("hits").get(0).path("title").asText()).isEqualTo("会员通方案");
    }

    @Test
    @DisplayName("传了无权限的项目：直接拒绝，不查库")
    void projectWithoutAccessIsDeniedBeforeSearch() throws Exception {
        when(accessGuard.canRead(7L, 12L)).thenReturn(false);

        CapabilityResult result = capability.execute(
                objectMapper.readTree("{\"query\":\"会员通\",\"projectId\":12}"),
                CapabilityContext.agent(7L, "run-1", false));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("KB_SCOPE_DENIED");
        verify(kbSearchService, never()).search(eq("会员通"), eq(7L),
                eq(KbSearchService.Scope.project(12L)), eq(5));
    }

    @Test
    @DisplayName("有权限的项目：按 project scope 检索")
    void projectWithAccessUsesProjectScope() throws Exception {
        when(accessGuard.canRead(7L, 12L)).thenReturn(true);
        when(kbSearchService.search(eq("会员通"), eq(7L),
                eq(KbSearchService.Scope.project(12L)), eq(5))).thenReturn(List.of());

        CapabilityResult result = capability.execute(
                objectMapper.readTree("{\"query\":\"会员通\",\"projectId\":12}"),
                CapabilityContext.agent(7L, "run-1", false));

        assertThat(result.ok()).isTrue();
        assertThat(objectMapper.readTree(result.dataJson()).path("count").asInt()).isZero();
    }

    @Test
    @DisplayName("检索故障：回 KB_SEARCH_FAILED + 人话，不伪装成空命中")
    void searchFailureReturnsStableErrorCode() throws Exception {
        when(kbSearchService.search(eq("会员通"), eq(7L), eq(KbSearchService.Scope.none()), eq(5)))
                .thenThrow(new KbSearchException("知识库检索暂时不可用，请稍后重试",
                        new IllegalStateException("ngram 索引缺失")));

        CapabilityResult result = capability.execute(
                objectMapper.readTree("{\"query\":\"会员通\"}"), CapabilityContext.agent(7L, "run-1", false));

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo(KbSearchException.CODE);
        assertThat(result.errorMessage()).contains("知识库检索暂时不可用");
    }

    @Test
    @DisplayName("无用户上下文（系统调用）不得借 projectId 越权")
    void systemContextCannotUseProjectScope() throws Exception {
        CapabilityResult result = capability.execute(
                objectMapper.readTree("{\"query\":\"会员通\",\"projectId\":12}"),
                CapabilityContext.system());

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("KB_SCOPE_DENIED");
        verify(kbSearchService, never()).search(eq("会员通"), isNull(),
                eq(KbSearchService.Scope.project(12L)), eq(5));
    }
}
