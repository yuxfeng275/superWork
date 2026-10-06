package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.DeliveryConfirmation;
import com.bu.management.entity.Project;
import com.bu.management.entity.RevenueContractEntry;
import com.bu.management.entity.User;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.DeliveryConfirmationMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RevenueContractEntryMapper;
import com.bu.management.mapper.UserMapper;
import com.bu.management.vo.PendingDeliveryVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 待交付合同确认：分组（逾期/未定月份）、确认/取消确认的幂等与状态机。
 */
@ExtendWith(MockitoExtension.class)
class PendingDeliveryServiceTest {

    @Mock
    private RevenueContractEntryMapper contractEntryMapper;
    @Mock
    private DeliveryConfirmationMapper confirmationMapper;
    @Mock
    private BusinessLineMapper businessLineMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private UserMapper userMapper;

    private PendingDeliveryService service;

    @BeforeEach
    void setUp() {
        service = new PendingDeliveryService(contractEntryMapper, confirmationMapper,
                businessLineMapper, projectMapper, userMapper);
    }

    private RevenueContractEntry entry(Long id, String serviceEnd) {
        RevenueContractEntry entry = new RevenueContractEntry();
        entry.setId(id);
        entry.setContractNo("HT-" + id);
        entry.setContractName("合同" + id);
        entry.setCustomer("客户" + id);
        entry.setSalesOwner("张三");
        entry.setReceivableAmount(new BigDecimal("10600"));
        entry.setServiceEndDate(serviceEnd == null ? null : LocalDate.parse(serviceEnd));
        entry.setDeliveryDate(null);
        entry.setBizLineId(1L);
        entry.setProjectId(100L);
        entry.setPending(0);
        return entry;
    }

    @Test
    @DisplayName("列表分组：未来月份正常、早于当月标记 overdue、服务结束时间为空归未定月份")
    void listYearGrouping() {
        YearMonth current = YearMonth.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM");
        RevenueContractEntry future = entry(1L, current.plusMonths(1).atDay(15).toString());
        RevenueContractEntry overdue = entry(2L, current.minusMonths(1).atDay(15).toString());
        RevenueContractEntry unknown = entry(3L, null);
        when(contractEntryMapper.selectList(any())).thenReturn(List.of(future, overdue, unknown));
        lenient().when(confirmationMapper.selectList(any())).thenReturn(List.of());
        when(businessLineMapper.selectList(any())).thenReturn(List.of());
        when(projectMapper.selectList(any())).thenReturn(List.of());
        when(userMapper.selectList(any())).thenReturn(List.of());

        PendingDeliveryVO vo = service.listYear(current.getYear());

        assertThat(vo.getGroups()).hasSize(3);
        PendingDeliveryVO.MonthGroup overdueGroup = vo.getGroups().get(0);
        assertThat(overdueGroup.getMonth()).isEqualTo(current.minusMonths(1).format(fmt));
        assertThat(overdueGroup.getWarning()).isEqualTo("overdue");
        assertThat(overdueGroup.getEntries().get(0).getOverdue()).isTrue();
        PendingDeliveryVO.MonthGroup futureGroup = vo.getGroups().get(1);
        assertThat(futureGroup.getMonth()).isEqualTo(current.plusMonths(1).format(fmt));
        assertThat(futureGroup.getWarning()).isNull();
        PendingDeliveryVO.MonthGroup unknownGroup = vo.getGroups().get(2);
        assertThat(unknownGroup.getMonth()).isNull();
        assertThat(unknownGroup.getLabel()).isEqualTo("未定月份");
        assertThat(unknownGroup.getWarning()).isEqualTo("unknown");
        assertThat(unknownGroup.getEntries().get(0).getConfirmed()).isFalse();
    }

    @Test
    @DisplayName("确认：新明细插入确认行（expected_month 取服务结束月）；已交付明细跳过")
    void confirmInsertsAndSkipsDelivered() {
        YearMonth current = YearMonth.now();
        RevenueContractEntry open = entry(1L, current.plusMonths(1).atDay(15).toString());
        RevenueContractEntry delivered = entry(2L, current.plusMonths(1).atDay(15).toString());
        delivered.setDeliveryDate(LocalDate.now());
        when(contractEntryMapper.selectBatchIds(any())).thenReturn(List.of(open, delivered));
        when(confirmationMapper.selectList(any())).thenReturn(List.of());

        int affected = service.confirm(List.of(1L, 2L), 7L);

        assertThat(affected).isEqualTo(1);
        ArgumentCaptor<DeliveryConfirmation> captor = ArgumentCaptor.forClass(DeliveryConfirmation.class);
        verify(confirmationMapper).insert(captor.capture());
        DeliveryConfirmation inserted = captor.getValue();
        assertThat(inserted.getContractEntryId()).isEqualTo(1L);
        assertThat(inserted.getExpectedMonth())
                .isEqualTo(current.plusMonths(1).format(DateTimeFormatter.ofPattern("yyyy-MM")));
        assertThat(inserted.getConfirmedBy()).isEqualTo(7L);
        assertThat(inserted.getRevokedAt()).isNull();
        verify(confirmationMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("重复确认：复用已有行，清空 revoked、刷新确认人/时间")
    void reconfirmClearsRevoked() {
        RevenueContractEntry open = entry(1L, null);
        when(contractEntryMapper.selectBatchIds(any())).thenReturn(List.of(open));
        DeliveryConfirmation revoked = new DeliveryConfirmation();
        revoked.setId(9L);
        revoked.setContractEntryId(1L);
        revoked.setRevokedBy(3L);
        revoked.setRevokedAt(java.time.LocalDateTime.now().minusDays(1));
        when(confirmationMapper.selectList(any())).thenReturn(List.of(revoked));

        int affected = service.confirm(List.of(1L), 7L);

        assertThat(affected).isEqualTo(1);
        ArgumentCaptor<DeliveryConfirmation> captor = ArgumentCaptor.forClass(DeliveryConfirmation.class);
        verify(confirmationMapper).updateById(captor.capture());
        DeliveryConfirmation updated = captor.getValue();
        assertThat(updated.getId()).isEqualTo(9L);
        assertThat(updated.getRevokedAt()).isNull();
        assertThat(updated.getRevokedBy()).isNull();
        assertThat(updated.getConfirmedBy()).isEqualTo(7L);
        assertThat(updated.getExpectedMonth()).isNull();   // 未定月份
        verify(confirmationMapper, never()).insert(any());
    }

    @Test
    @DisplayName("取消确认：仅置生效中的行；空 entryIds 抛参数异常")
    void revokeMarksActiveRows() {
        DeliveryConfirmation active = new DeliveryConfirmation();
        active.setId(9L);
        active.setContractEntryId(1L);
        when(confirmationMapper.selectList(any())).thenReturn(List.of(active));

        int affected = service.revoke(List.of(1L), 8L);

        assertThat(affected).isEqualTo(1);
        ArgumentCaptor<DeliveryConfirmation> captor = ArgumentCaptor.forClass(DeliveryConfirmation.class);
        verify(confirmationMapper).updateById(captor.capture());
        assertThat(captor.getValue().getRevokedBy()).isEqualTo(8L);
        assertThat(captor.getValue().getRevokedAt()).isNotNull();

        assertThatThrownBy(() -> service.revoke(List.of(), 8L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
