package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bu.management.entity.AiPrompt;
import com.bu.management.mapper.AiPromptMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** PromptRegistry：兜底、取最大版本、进程内缓存、存新版 version+1。 */
@ExtendWith(MockitoExtension.class)
class PromptRegistryTest {

    @Mock private AiPromptMapper promptMapper;

    private PromptRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PromptRegistry(promptMapper, 60L);
    }

    @Test
    void returnsFallbackWhenNoRecord() {
        when(promptMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertThat(registry.get("weekly-summary", "代码里的兜底提示词"))
                .isEqualTo("代码里的兜底提示词");
    }

    @Test
    void returnsLatestEnabledContentFromDb() {
        when(promptMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(prompt(1, "v1 正文", 1), prompt(3, "v3 正文", 1),
                        prompt(2, "v2 正文", 1)));

        assertThat(registry.get("weekly-summary", "兜底")).isEqualTo("v3 正文");
    }

    @Test
    void secondGetHitsCacheInsteadOfMapper() {
        when(promptMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(prompt(1, "v1 正文", 1)));

        assertThat(registry.get("weekly-summary", "兜底")).isEqualTo("v1 正文");
        // 第二次即使换了兜底文案（模拟另一处调用），也应走缓存返回同一份正文
        assertThat(registry.get("weekly-summary", "另一份兜底")).isEqualTo("v1 正文");

        verify(promptMapper, times(1)).selectList(any(Wrapper.class));
    }

    @Test
    void saveVersionInsertsMaxPlusOne() {
        when(promptMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(prompt(1, "v1 正文", 1), prompt(2, "v2 正文", 1)));

        registry.saveVersion("weekly-summary", "v3 正文", 88L);

        ArgumentCaptor<AiPrompt> captor = ArgumentCaptor.forClass(AiPrompt.class);
        verify(promptMapper).insert(captor.capture());
        AiPrompt saved = captor.getValue();
        assertThat(saved.getCode()).isEqualTo("weekly-summary");
        assertThat(saved.getVersion()).isEqualTo(3);
        assertThat(saved.getContent()).isEqualTo("v3 正文");
        assertThat(saved.getEnabled()).isEqualTo(1);
        assertThat(saved.getCreatedBy()).isEqualTo(88L);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    private AiPrompt prompt(int version, String content, int enabled) {
        AiPrompt row = new AiPrompt();
        row.setCode("weekly-summary");
        row.setVersion(version);
        row.setContent(content);
        row.setEnabled(enabled);
        return row;
    }
}
