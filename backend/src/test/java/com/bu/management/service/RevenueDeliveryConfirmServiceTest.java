package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.RevenueContractEntry;
import com.bu.management.entity.RevenueDeliveryConfirmation;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.RevenueContractEntryMapper;
import com.bu.management.mapper.RevenueDeliveryConfirmationMapper;
import com.bu.management.mapper.UserMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 按月待交付统计核心契约：待交付口径（无交付日期或交付日期晚于今天）、
 * 缺销售姓名归入「未标注销售」、确认状态按 月份×业务线×销售 关联。
 */
@ExtendWith(MockitoExtension.class)
class RevenueDeliveryConfirmServiceTest {

    @Mock private RevenueContractEntryMapper entryMapper;
    @Mock private RevenueDeliveryConfirmationMapper confirmMapper;
    @Mock private BusinessLineMapper businessLineMapper;
    @Mock private UserMapper userMapper;
    @InjectMocks private RevenueDeliveryConfirmService service;

    @org.junit.jupiter.api.BeforeAll
    static void initLambdaCache() {
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(
                new org.apache.ibatis.session.Configuration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                assistant, RevenueContractEntry.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                assistant, RevenueDeliveryConfirmation.class);
    }

    private RevenueContractEntry entry(String saleMonth, Long lineId, String salesOwner,
                                       String amount, LocalDate deliveryDate) {
        RevenueContractEntry entry = new RevenueContractEntry();
        entry.setSaleMonth(saleMonth);
        entry.setBizLineId(lineId);
        entry.setSalesOwner(salesOwner);
        entry.setReceivableAmount(new BigDecimal(amount));
        entry.setDeliveryDate(deliveryDate);
        return entry;
    }

    private BusinessLine line(Long id, String name) {
        BusinessLine line = new BusinessLine();
        line.setId(id);
        line.setName(name);
        return line;
    }

    @Test
    @DisplayName("stats：按月×业务线×销售聚合待交付，已交付（交付日期<=今天）不计入")
    void statsAggregatesPendingOnly() {
        LocalDate today = LocalDate.now();
        int year = today.getYear();
        String month = String.format("%04d-%02d", year, today.getMonthValue());
        when(entryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                entry(month, 1L, "张三", "100", null),                       // 待交付：无交付日期
                entry(month, 1L, "张三", "200", today.plusDays(10)),         // 待交付：未来交付
                entry(month, 1L, "张三", "999", today),                      // 已交付：今天
                entry(month, 1L, "李四", "50", null),
                entry(month, 2L, null, "70", null)));                        // 未标注销售
        when(businessLineMapper.selectList(null)).thenReturn(List.of(
                line(1L, "会员通"), line(2L, "云鹿定制")));
        when(confirmMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        List<RevenueDeliveryConfirmService.StatsRow> rows = service.stats(year);

        assertThat(rows).hasSize(3);
        RevenueDeliveryConfirmService.StatsRow zhangsan = rows.stream()
                .filter(r -> "张三".equals(r.salesOwner())).findFirst().orElseThrow();
        assertThat(zhangsan.entryCount()).isEqualTo(2);
        assertThat(zhangsan.pendingAmount()).isEqualByComparingTo("300");
        assertThat(zhangsan.bizLineName()).isEqualTo("会员通");
        assertThat(zhangsan.status()).isEqualTo(RevenueDeliveryConfirmation.STATUS_PENDING);
        assertThat(rows.stream().filter(r -> "未标注销售".equals(r.salesOwner())).findFirst().orElseThrow()
                .pendingAmount()).isEqualByComparingTo("70");
    }

    @Test
    @DisplayName("stats：已有确认记录时带出状态/备注/确认人")
    void statsJoinsConfirmation() {
        LocalDate today = LocalDate.now();
        int year = today.getYear();
        String month = String.format("%04d-%02d", year, today.getMonthValue());
        when(entryMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                entry(month, 1L, "张三", "100", null)));
        when(businessLineMapper.selectList(null)).thenReturn(List.of(line(1L, "会员通")));
        RevenueDeliveryConfirmation confirm = new RevenueDeliveryConfirmation();
        confirm.setYearMonth(month);
        confirm.setBizLineId(1L);
        confirm.setSalesOwner("张三");
        confirm.setStatus(RevenueDeliveryConfirmation.STATUS_CONFIRMABLE);
        confirm.setRemark("已与张三电话确认，月底交付");
        confirm.setConfirmedByName("王管理");
        when(confirmMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(confirm));

        RevenueDeliveryConfirmService.StatsRow row = service.stats(year).get(0);

        assertThat(row.status()).isEqualTo(RevenueDeliveryConfirmation.STATUS_CONFIRMABLE);
        assertThat(row.remark()).contains("月底交付");
        assertThat(row.confirmedByName()).isEqualTo("王管理");
    }

    @Test
    @DisplayName("confirm：非法月份与非法状态拒绝")
    void confirmValidates() {
        assertThatThrownBy(() -> service.confirm(
                new RevenueDeliveryConfirmService.ConfirmRequest("2026-13", 1L, "张三", "CONFIRMABLE", null), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.confirm(
                new RevenueDeliveryConfirmService.ConfirmRequest("2026-09", 1L, "张三", "MAYBE", null), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.confirm(
                new RevenueDeliveryConfirmService.ConfirmRequest("2026-09", null, "张三", "CONFIRMABLE", null), 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("confirm：缺销售姓名用 _UNSET_ 占位，重复确认走更新")
    void confirmUpserts() {
        RevenueDeliveryConfirmation existing = new RevenueDeliveryConfirmation();
        existing.setId(9L);
        existing.setYearMonth("2026-09");
        existing.setBizLineId(1L);
        existing.setSalesOwner(RevenueDeliveryConfirmService.UNSET_SALES);
        when(confirmMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(userMapper.selectById(1L)).thenReturn(null);

        RevenueDeliveryConfirmation saved = service.confirm(
                new RevenueDeliveryConfirmService.ConfirmRequest(
                        "2026-09", 1L, null, "UNCONFIRMABLE", "客户推迟到 10 月"), 1L);

        assertThat(saved.getId()).isEqualTo(9L);
        assertThat(saved.getSalesOwner()).isEqualTo(RevenueDeliveryConfirmService.UNSET_SALES);
        assertThat(saved.getStatus()).isEqualTo(RevenueDeliveryConfirmation.STATUS_UNCONFIRMABLE);
        assertThat(saved.getRemark()).isEqualTo("客户推迟到 10 月");
        assertThat(saved.getConfirmedAt()).isNotNull();
    }
}
