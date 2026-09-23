package com.bu.management.sync.collector;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.dto.RevenueImportResultVO;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.Project;
import com.bu.management.entity.RevenueContractEntry;
import com.bu.management.entity.RevenueContractImportBatch;
import com.bu.management.integration.SeeyonOaClient;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RevenueContractEntryMapper;
import com.bu.management.mapper.RevenueContractImportBatchMapper;
import com.bu.management.service.RevenueContractAssignment;
import com.bu.management.service.RevenueContractImportService;
import com.bu.management.service.SeeyonOaConfigService;
import com.bu.management.service.ConnectorRegistryService;
import com.bu.management.sync.SyncOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * OA 销售合同采集器：CAP4 报表「销售合同查询(全域-云鹿and会员通and微信定制)」→ 统一管道。
 *
 * 取数自适应两种形态（浏览器端"导出"实为查询接口 + 前端生成 Excel）：
 * 1. 返回 xlsx（PK 头）→ 走 {@link RevenueContractImportService#importContractsStream} Excel 管道；
 * 2. 返回 JSON（/seeyon/rest/cap4/report/{id}/query/{id}/{page} 分页）→ 字段多候选键映射为合同明细，
 *    归属判定复用 {@link RevenueContractAssignment}，按 detail_no  upsert（mapping_locked 保护不变）。
 *
 * 地址在「连接器管理 → OA（致远）」的合同导出地址维护。JSON 字段名联调校准：
 * 首次运行会把首行字段名写入同步日志与运行日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OaContractCollector implements DataCollector {

    public static final String TASK_CODE = "oa-contract";

    private static final int MAX_PAGES = 200;
    private static final int UPSERT_CHUNK = 500;
    private static final Pattern TRAILING_PAGE = Pattern.compile("^(.*?)/(\\d+)$");

    private final SeeyonOaClient oaClient;
    private final SeeyonOaConfigService oaConfigService;
    private final ConnectorRegistryService registryService;
    private final RevenueContractImportService contractImportService;
    private final RevenueContractEntryMapper contractEntryMapper;
    private final RevenueContractImportBatchMapper batchMapper;
    private final BusinessLineMapper businessLineMapper;
    private final ProjectMapper projectMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String taskCode() {
        return TASK_CODE;
    }

    @Override
    public List<SyncOutcome> collect(String scope, String triggeredBy) {
        if (!oaConfigService.getRuntimeConfig().isConfigured()) {
            return List.of();
        }
        String url = registryService.findByCode(ConnectorRegistryService.CODE_OA)
                .map(connector -> registryService.extra(connector, "contractExportUrl"))
                .orElse(null);
        if (!StringUtils.hasText(url)) {
            throw new IllegalStateException(
                    "未配置 OA 销售合同报表地址（连接器管理 → OA（致远）→ 合同导出地址）");
        }
        url = url.trim();

        byte[] firstPage = oaClient.fetchRaw(pageUrl(url, 1));
        if (isXlsx(firstPage)) {
            // 导出件形态：直接进 Excel 管道
            String fileName = "oa-vreport-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx";
            RevenueImportResultVO result = contractImportService.importContractsStream(
                    new ByteArrayInputStream(firstPage), fileName, "OA", null);
            return List.of(SyncOutcome.success("contract", String.valueOf(LocalDate.now().getYear()),
                    result.getTotalCount(), result.getSuccessCount(), result.getPendingCount(),
                    "OA 合同同步完成（Excel 形态，批次 #" + result.getBatchId() + "）"));
        }
        return List.of(collectFromJson(url, firstPage, triggeredBy));
    }

    // ==================== JSON 分页形态 ====================

    private SyncOutcome collectFromJson(String url, byte[] firstPage, String triggeredBy) {
        AssignmentContext ctx = loadAssignmentContext();
        List<RevenueContractEntry> entries = new ArrayList<>();
        String firstRowKeys = null;
        int page = 1;
        byte[] body = firstPage;
        while (page <= MAX_PAGES) {
            JsonNode root = readJson(body, page);
            List<JsonNode> rows = extractRows(root);
            if (rows.isEmpty()) {
                break;
            }
            if (page == 1) {
                firstRowKeys = joinKeys(rows.get(0));
                log.info("OA 合同报表首行字段: {}", firstRowKeys);
            }
            for (JsonNode row : rows) {
                RevenueContractEntry entry = mapRow(row, ctx);
                if (entry != null) {
                    entries.add(entry);
                }
            }
            page++;
            body = oaClient.fetchRaw(pageUrl(url, page));
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException("未从 OA 报表解析到合同明细，首行字段: " + firstRowKeys
                    + "。请把该字段列表发给开发校准映射");
        }

        // 批次历史（与手工导入/工时链路共用一张表，file_name 标识来源）
        RevenueContractImportBatch batch = new RevenueContractImportBatch();
        batch.setFileName("oa-cap4-sync" + ("schedule".equals(triggeredBy) ? "（定时）" : "（手动）"));
        batch.setCreatedBy(null);
        batch.setTotalCount(entries.size());
        int pendingCount = (int) entries.stream().filter(e -> Integer.valueOf(1).equals(e.getPending())).count();
        batch.setSuccessCount(entries.size() - pendingCount);
        batch.setPendingCount(pendingCount);
        batchMapper.insert(batch);
        entries.forEach(e -> e.setBatchId(batch.getId()));
        for (int i = 0; i < entries.size(); i += UPSERT_CHUNK) {
            contractEntryMapper.upsertBatch(entries.subList(i, Math.min(i + UPSERT_CHUNK, entries.size())));
        }

        String year = String.valueOf(LocalDate.now().getYear());
        return SyncOutcome.success("contract", year, entries.size(), entries.size(), pendingCount,
                "OA 合同同步完成（JSON 形态，批次 #" + batch.getId() + "，共 " + (page - 1) + " 页）");
    }

    // ==================== 行映射（静态，便于联调单测） ====================

    /** 字段候选键：兼容工时系统 drilldown 的 snake_case 与 OA 报表中文列名 */
    static final Map<String, String[]> FIELD_CANDIDATES = Map.ofEntries(
            Map.entry("detailNo", new String[]{"detail_record_id", "detail_no", "detailNo", "明细表记录ID"}),
            Map.entry("contractNo", new String[]{"contract_id", "contract_no", "contractNo", "合同ID"}),
            Map.entry("contractName", new String[]{"contract_name", "contractName", "合同名称"}),
            Map.entry("brand", new String[]{"brand", "品牌"}),
            Map.entry("customer", new String[]{"customer_name", "customer", "customerName", "客户名称"}),
            Map.entry("itemDesc", new String[]{"payment_item_content", "item_desc", "itemDesc", "款项内容"}),
            Map.entry("typeRaw", new String[]{"business_line_original", "biz_line_raw", "business_line", "收款款项类型"}),
            Map.entry("receivable", new String[]{"receivable_amount", "receivableAmount", "应收金额"}),
            Map.entry("saleMonth", new String[]{"payment_sales_month", "sale_month", "saleMonth", "收款销售月份"}),
            Map.entry("receivableDate", new String[]{"receivable_date", "receivableDate", "应收日期"}),
            Map.entry("deliveryDate", new String[]{"project_delivery_date", "delivery_date", "deliveryDate", "项目交付日期"}),
            Map.entry("smsCost", new String[]{"sms_cost", "smsCost", "短信成本"}),
            Map.entry("directCost", new String[]{"direct_cost", "directCost", "直接成本"}),
            Map.entry("thirdPartyCost", new String[]{"third_party_procurement_cost", "third_party_cost", "thirdPartyCost", "第三方采购成本"}),
            Map.entry("received", new String[]{"received_amount", "receivedAmount", "已收金额", "实收金额"}),
            Map.entry("paymentStatus", new String[]{"payment_status", "paymentStatus", "收款状态"}),
            // 销售：承接人优先，报价人兜底
            Map.entry("salesOwner", new String[]{"undertaker", "承接人", "sales_owner", "salesOwner",
                    "quoter", "报价人"}));

    record AssignmentContext(List<BusinessLine> lines, Map<Long, String> lineMode,
                             List<Project> projects, List<Long> enabledLineIds) {
    }

    private AssignmentContext loadAssignmentContext() {
        List<BusinessLine> lines = businessLineMapper.selectList(new LambdaQueryWrapper<BusinessLine>()
                .eq(BusinessLine::getStatus, 1));
        Map<Long, String> lineMode = lines.stream().collect(Collectors.toMap(BusinessLine::getId,
                line -> StringUtils.hasText(line.getRevenueMode()) ? line.getRevenueMode() : "full", (a, b) -> a));
        List<Project> projects = projectMapper.selectList(null);
        List<Long> enabledLineIds = lines.stream().map(BusinessLine::getId).toList();
        return new AssignmentContext(lines, lineMode, projects, enabledLineIds);
    }

    static RevenueContractEntry mapRow(JsonNode row, AssignmentContext ctx) {
        String detailNo = field(row, "detailNo");
        if (!StringUtils.hasText(detailNo)) {
            return null;
        }
        BigDecimal amount = decimal(field(row, "receivable"));
        if (amount == null) {
            return null;
        }
        RevenueContractEntry entry = new RevenueContractEntry();
        entry.setSourceSystem("OA");
        entry.setDetailNo(detailNo.trim());
        entry.setContractNo(field(row, "contractNo"));
        String contractName = field(row, "contractName");
        if (contractName != null && contractName.trim().startsWith("测试")) {
            return null; // OA 报表里的测试合同不进营收统计
        }
        entry.setContractName(contractName);
        entry.setBrand(field(row, "brand"));
        entry.setCustomer(field(row, "customer"));
        entry.setItemDesc(field(row, "itemDesc"));
        String typeRaw = field(row, "typeRaw");
        entry.setBizLineRaw(typeRaw);
        entry.setSalesOwner(field(row, "salesOwner"));
        entry.setReceivableAmount(amount);
        entry.setReceivableDate(dateText(field(row, "receivableDate")));
        String saleMonth = monthText(field(row, "saleMonth"));
        if (saleMonth == null) {
            saleMonth = monthText(field(row, "receivableDate"));
        }
        entry.setSaleMonth(saleMonth);
        entry.setDeliveryDate(dateText(field(row, "deliveryDate")));
        entry.setSmsCost(decimal(field(row, "smsCost")));
        entry.setDirectCost(decimal(field(row, "directCost")));
        entry.setThirdPartyCost(decimal(field(row, "thirdPartyCost")));
        entry.setReceivedAmount(decimal(field(row, "received")));
        entry.setPaymentStatus(field(row, "paymentStatus"));

        RevenueContractAssignment.Assigned assigned = RevenueContractAssignment.assign(
                entry.getBrand(), typeRaw, ctx.lines(), ctx.lineMode(), ctx.projects(), ctx.enabledLineIds());
        entry.setBizLineId(assigned.lineId());
        entry.setProjectId(assigned.projectId());
        entry.setPending(assigned.pending() ? 1 : 0);
        return entry;
    }

    /** 取字段：按候选键依次尝试；值兼容 {value}/{showValue} 包装 */
    static String field(JsonNode row, String logicalName) {
        String[] candidates = FIELD_CANDIDATES.get(logicalName);
        if (candidates == null) {
            return null;
        }
        for (String key : candidates) {
            JsonNode node = row.get(key);
            String text = unwrap(node);
            if (StringUtils.hasText(text)) {
                return text.trim();
            }
        }
        return null;
    }

    private static String unwrap(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            JsonNode value = node.get("value");
            if (value != null && !value.isNull()) {
                return value.asText(null);
            }
            JsonNode showValue = node.get("showValue");
            if (showValue != null && !showValue.isNull()) {
                return showValue.asText(null);
            }
            return null;
        }
        return node.asText(null);
    }

    static BigDecimal decimal(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return new BigDecimal(text.replace(",", "").replace("¥", "").replace("元", "").trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static final Pattern MONTH_PATTERN = Pattern.compile("(20\\d{2})[-/年.](\\d{1,2})");

    static String monthText(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Matcher matcher = MONTH_PATTERN.matcher(text.trim());
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1) + "-" + String.format("%02d", Integer.parseInt(matcher.group(2)));
    }

    static LocalDate dateText(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String trimmed = text.trim().replace("/", "-").replace(".", "-");
        if (trimmed.length() >= 10) {
            trimmed = trimmed.substring(0, 10);
        }
        try {
            return LocalDate.parse(trimmed);
        } catch (Exception ex) {
            return null;
        }
    }

    // ==================== 内部工具 ====================

    /** 报表地址末位为页码（…/query/{id}/1）时替换页码，否则原样（仅拉第一页） */
    static String pageUrl(String url, int page) {
        Matcher matcher = TRAILING_PAGE.matcher(url);
        if (matcher.matches()) {
            return matcher.group(1) + "/" + page;
        }
        return url;
    }

    private static boolean isXlsx(byte[] body) {
        return body != null && body.length > 2 && body[0] == 'P' && body[1] == 'K';
    }

    private JsonNode readJson(byte[] body, int page) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            String snippet = body == null ? ""
                    : new String(body, 0, Math.min(body.length, 300), java.nio.charset.StandardCharsets.UTF_8);
            throw new IllegalStateException("OA 报表第 " + page + " 页响应非 JSON/xlsx，响应片段: " + snippet);
        }
    }

    /** 从响应中找行数组：支持数组根、{data:[...]}、{data:{records|list|rows:[...]}} 等形态 */
    static List<JsonNode> extractRows(JsonNode root) {
        if (root == null || root.isNull()) {
            return List.of();
        }
        if (root.isArray()) {
            List<JsonNode> rows = new ArrayList<>();
            root.forEach(rows::add);
            return rows;
        }
        for (String key : List.of("records", "list", "rows", "data")) {
            JsonNode child = root.get(key);
            if (child != null && child.isArray()) {
                List<JsonNode> rows = new ArrayList<>();
                child.forEach(rows::add);
                return rows;
            }
        }
        JsonNode data = root.get("data");
        if (data != null && data.isObject()) {
            return extractRows(data);
        }
        return List.of();
    }

    private static String joinKeys(JsonNode row) {
        if (row == null || !row.isObject()) {
            return String.valueOf(row);
        }
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = row.fieldNames();
        while (it.hasNext()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(it.next());
        }
        return sb.toString();
    }
}
