package com.bu.management.plugin.facts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.bu.management.capability.CapabilityContext;
import com.bu.management.capability.CapabilityResult;
import com.bu.management.service.PendingDeliveryService;
import com.bu.management.vo.PendingDeliveryVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PendingDeliveryCapabilityTest {
    @Mock private PendingDeliveryService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void returnsUnconfirmedEntriesForRequestedMonth() throws Exception {
        String month = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM"));
        PendingDeliveryVO.Entry entry = new PendingDeliveryVO.Entry();
        entry.setId(1L); entry.setContractNo("C-1"); entry.setCustomer("客户A");
        entry.setReceivableAmount(new BigDecimal("100")); entry.setSalesOwner("张三");
        entry.setExpectedMonth(month); entry.setConfirmed(false);
        PendingDeliveryVO.MonthGroup group = new PendingDeliveryVO.MonthGroup();
        group.setMonth(month); group.setEntries(List.of(entry));
        PendingDeliveryVO vo = new PendingDeliveryVO();
        vo.setYear(Integer.parseInt(month.substring(0, 4))); vo.setGroups(List.of(group));
        when(service.listYear(vo.getYear())).thenReturn(vo);

        CapabilityResult result = new PendingDeliveryCapability(service, mapper)
                .execute(mapper.readTree("{\"month\":\"" + month + "\"}"),
                        CapabilityContext.agent(7L, "r", false));

        assertThat(result.ok()).isTrue();
        assertThat(mapper.readTree(result.dataJson()).path("count").asInt()).isEqualTo(1);
    }
}
