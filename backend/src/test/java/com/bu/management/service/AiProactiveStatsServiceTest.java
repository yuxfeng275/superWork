package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bu.management.entity.AiProactiveSuggestion;
import com.bu.management.mapper.AiProactivePushMapper;
import com.bu.management.mapper.AiProactiveSuggestionMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiProactiveStatsServiceTest {

    @Mock private AiProactiveSuggestionMapper suggestionMapper;
    @Mock private AiProactivePushMapper pushMapper;

    private AiProactiveStatsService service;

    @BeforeEach
    void setUp() {
        service = new AiProactiveStatsService(suggestionMapper, pushMapper);
        when(pushMapper.selectCount(any(Wrapper.class))).thenReturn(3L, 2L);
    }

    @Test
    void computesAdoptionAndNoiseRates() {
        when(suggestionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                row("weekly_report", AiProactiveSuggestion.STATUS_DONE),
                row("weekly_report", AiProactiveSuggestion.STATUS_DONE),
                row("weekly_report", AiProactiveSuggestion.STATUS_DISMISSED),
                row("email_digest", AiProactiveSuggestion.STATUS_NEW)));

        AiProactiveStatsService.Stats stats = service.stats(7L, 30);

        assertThat(stats.days()).isEqualTo(30);
        assertThat(stats.total()).isEqualTo(4);
        assertThat(stats.done()).isEqualTo(2);
        assertThat(stats.dismissed()).isEqualTo(1);
        assertThat(stats.autoResolved()).isZero();
        assertThat(stats.open()).isEqualTo(1);
        assertThat(stats.adoptRate()).isEqualTo(67);
        assertThat(stats.noiseRate()).isEqualTo(25);
        assertThat(stats.byKind()).hasSize(2);
        assertThat(stats.byKind().get(0).kind()).isEqualTo("weekly_report");
        assertThat(stats.byKind().get(0).adoptRate()).isEqualTo(67);
        assertThat(stats.pushes()).isEqualTo(3);
        assertThat(stats.pushesSent()).isEqualTo(2);
    }

    @Test
    void autoResolvedRowsDoNotCountAsUserNoise() {
        // 条件自愈被系统收回（AUTO_DISMISSED）不是用户忽略：既不进采纳分母也不拉高打扰率
        when(suggestionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                row("weekly_report", AiProactiveSuggestion.STATUS_DONE),
                row("weekly_report", AiProactiveSuggestion.STATUS_AUTO_DISMISSED),
                row("weekly_report", AiProactiveSuggestion.STATUS_AUTO_DISMISSED),
                row("weekly_report", AiProactiveSuggestion.STATUS_DISMISSED)));

        AiProactiveStatsService.Stats stats = service.stats(7L, 30);

        assertThat(stats.total()).isEqualTo(4);
        assertThat(stats.done()).isEqualTo(1);
        assertThat(stats.dismissed()).isEqualTo(1);
        assertThat(stats.autoResolved()).isEqualTo(2);
        assertThat(stats.adoptRate()).isEqualTo(50);
        assertThat(stats.noiseRate()).isEqualTo(50);
        assertThat(stats.byKind().get(0).autoResolved()).isEqualTo(2);
        assertThat(stats.byKind().get(0).noiseRate()).isEqualTo(50);
    }

    @Test
    void staysZeroSafeWithoutData() {
        when(suggestionMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        AiProactiveStatsService.Stats stats = service.stats(7L, 30);

        assertThat(stats.total()).isZero();
        assertThat(stats.adoptRate()).isZero();
        assertThat(stats.noiseRate()).isZero();
        assertThat(stats.byKind()).isEmpty();
    }

    private AiProactiveSuggestion row(String kind, String status) {
        AiProactiveSuggestion row = new AiProactiveSuggestion();
        row.setKind(kind);
        row.setStatus(status);
        row.setCreatedAt(LocalDateTime.now().minusDays(1));
        return row;
    }
}
