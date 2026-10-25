package com.bu.management.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** 报价有效期巡检：已批准/已发送且超过有效期的报价单自动置为 EXPIRED。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuotationExpiryScheduler {

    private final QuotationWorkflowService workflowService;

    @Scheduled(cron = "${quotation.expire-cron:0 10 1 * * *}", zone = "Asia/Shanghai")
    public void expireOverdueQuotations() {
        List<Long> expired = workflowService.expireOverdue();
        if (!expired.isEmpty()) {
            log.info("Quotation expiry sweep marked {} quotations as EXPIRED: {}", expired.size(), expired);
        }
    }
}
