package com.bu.management.sync.collector;

import com.bu.management.dto.RevenueImportResultVO;
import com.bu.management.integration.SeeyonOaClient;
import com.bu.management.service.RevenueContractImportService;
import com.bu.management.service.SeeyonOaConfigService;
import com.bu.management.service.SystemConfigService;
import com.bu.management.sync.SyncOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * OA 销售合同采集器：vReport「销售合同查询(全域-云鹿and会员通and微信定制)」导出 →
 * xlsx 流 → 复用 {@link RevenueContractImportService#importContractsStream} 统一管道
 * （upsert / 归属判定 / mapping_locked 保护与手工导入一致）。
 * 导出地址在系统配置 oa-vreport.sales-contract-export-url 维护（浏览器抓包获取）。
 */
@Component
@RequiredArgsConstructor
public class OaContractCollector implements DataCollector {

    public static final String TASK_CODE = "oa-contract";

    private static final String CONFIG_GROUP = "oa-vreport";
    private static final String CONFIG_KEY = "sales-contract-export-url";

    private final SeeyonOaClient oaClient;
    private final SeeyonOaConfigService oaConfigService;
    private final SystemConfigService systemConfigService;
    private final RevenueContractImportService contractImportService;

    @Override
    public String taskCode() {
        return TASK_CODE;
    }

    @Override
    public List<SyncOutcome> collect(String scope, String triggeredBy) {
        if (!oaConfigService.getRuntimeConfig().isConfigured()) {
            return List.of();
        }
        String exportUrl = systemConfigService.getValue(CONFIG_GROUP, CONFIG_KEY, null);
        if (!StringUtils.hasText(exportUrl)) {
            throw new IllegalStateException(
                    "未配置 OA 销售合同导出地址（系统配置 oa-vreport.sales-contract-export-url），"
                            + "请在浏览器中抓取「销售合同查询(全域)」导出按钮的请求地址后填入");
        }
        byte[] xlsx = oaClient.fetchVReportExport(exportUrl.trim());
        String fileName = "oa-vreport-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx";
        RevenueImportResultVO result = contractImportService.importContractsStream(
                new ByteArrayInputStream(xlsx), fileName, null);
        String year = String.valueOf(LocalDate.now().getYear());
        return List.of(SyncOutcome.success("contract", year,
                result.getTotalCount(), result.getSuccessCount(), result.getPendingCount(),
                "OA vReport 合同同步完成（批次 #" + result.getBatchId() + "）"));
    }
}
