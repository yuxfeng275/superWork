package com.bu.management.sync.collector;

import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.Project;
import com.bu.management.entity.RevenueContractEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OA 合同采集器的字段映射与解析单测（联调时用于快速校准真实字段名）。
 */
class OaContractCollectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OaContractCollector.AssignmentContext context() {
        List<BusinessLine> lines = new ArrayList<>();
        lines.add(line(1L, "全渠道云鹿定制", "full"));
        lines.add(line(2L, "全渠道云鹿SAAS", "full"));
        Map<Long, String> lineMode = lines.stream().collect(Collectors.toMap(BusinessLine::getId,
                BusinessLine::getRevenueMode));
        List<Project> projects = new ArrayList<>();
        projects.add(project(10L, 1L, "Speedo"));
        return new OaContractCollector.AssignmentContext(lines, lineMode, projects,
                lines.stream().map(BusinessLine::getId).toList());
    }

    private BusinessLine line(Long id, String name, String mode) {
        BusinessLine line = new BusinessLine();
        line.setId(id);
        line.setName(name);
        line.setRevenueMode(mode);
        line.setStatus(1);
        return line;
    }

    private Project project(Long id, Long lineId, String name) {
        Project project = new Project();
        project.setId(id);
        project.setBusinessLineId(lineId);
        project.setName(name);
        return project;
    }

    @Test
    void fieldResolvesCandidatesAndUnwrapsValueWrapper() throws Exception {
        JsonNode row = MAPPER.readTree("""
                {"detail_record_id": "D-1", "品牌": {"value": "Speedo"}, "应收金额": "1,200.50"}
                """);
        assertThat(OaContractCollector.field(row, "detailNo")).isEqualTo("D-1");
        assertThat(OaContractCollector.field(row, "brand")).isEqualTo("Speedo");
        assertThat(OaContractCollector.field(row, "receivable")).isEqualTo("1,200.50");
        assertThat(OaContractCollector.field(row, "contractNo")).isNull();
    }

    @Test
    void monthAndDateParsingCoversCommonFormats() {
        assertThat(OaContractCollector.monthText("2026-08")).isEqualTo("2026-08");
        assertThat(OaContractCollector.monthText("2026/8")).isEqualTo("2026-08");
        assertThat(OaContractCollector.monthText("2026年3月")).isEqualTo("2026-03");
        assertThat(OaContractCollector.monthText("2026-08-15 10:00:00")).isEqualTo("2026-08");
        assertThat(OaContractCollector.monthText("abc")).isNull();

        assertThat(OaContractCollector.dateText("2026-08-15")).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(OaContractCollector.dateText("2026/08/15 12:00")).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(OaContractCollector.dateText("not-a-date")).isNull();
    }

    @Test
    void mapRowAssignsLineAndProjectWithinLine() throws Exception {
        JsonNode row = MAPPER.readTree("""
                {
                  "detail_record_id": "D-100",
                  "contract_id": "C-9",
                  "contract_name": "Speedo 定制开发",
                  "brand": "Speedo",
                  "customer_name": "速比涛",
                  "business_line_original": "定制",
                  "receivable_amount": 10000,
                  "payment_sales_month": "2026-07",
                  "project_delivery_date": "2026-09-01"
                }
                """);
        RevenueContractEntry entry = OaContractCollector.mapRow(row, context());
        assertThat(entry).isNotNull();
        assertThat(entry.getSourceSystem()).isEqualTo("OA");
        assertThat(entry.getDetailNo()).isEqualTo("D-100");
        assertThat(entry.getReceivableAmount()).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(entry.getSaleMonth()).isEqualTo("2026-07");
        assertThat(entry.getDeliveryDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(entry.getBizLineId()).isEqualTo(1L);
        assertThat(entry.getProjectId()).isEqualTo(10L);
        assertThat(entry.getPending()).isZero();
    }

    @Test
    void mapRowSkipsRowsWithoutDetailNoOrAmount() throws Exception {
        assertThat(OaContractCollector.mapRow(MAPPER.readTree("{\"brand\":\"x\"}"), context())).isNull();
        assertThat(OaContractCollector.mapRow(
                MAPPER.readTree("{\"detail_record_id\":\"D-1\"}"), context())).isNull();
    }

    @Test
    void extractRowsSupportsArrayRootAndWrappedShapes() throws Exception {
        assertThat(OaContractCollector.extractRows(MAPPER.readTree("[{\"a\":1},{\"a\":2}]"))).hasSize(2);
        assertThat(OaContractCollector.extractRows(MAPPER.readTree("{\"data\":[{\"a\":1}]}"))).hasSize(1);
        assertThat(OaContractCollector.extractRows(
                MAPPER.readTree("{\"data\":{\"records\":[{\"a\":1},{\"a\":2},{\"a\":3}]}}"))).hasSize(3);
        assertThat(OaContractCollector.extractRows(MAPPER.readTree("{\"total\":0}"))).isEmpty();
    }

    @Test
    void pageUrlReplacesTrailingPageNumber() {
        String base = "https://oa.example.cn/seeyon/rest/cap4/report/-6206045658690666222/query/-3088326160909319764/1";
        assertThat(OaContractCollector.pageUrl(base, 3))
                .isEqualTo("https://oa.example.cn/seeyon/rest/cap4/report/-6206045658690666222/query/-3088326160909319764/3");
        assertThat(OaContractCollector.pageUrl("https://oa.example.cn/seeyon/vreport/vReport.do?method=export", 2))
                .isEqualTo("https://oa.example.cn/seeyon/vreport/vReport.do?method=export");
    }
}
