package com.bu.management.service;

import com.bu.management.dto.RevenueImportResultVO;
import com.bu.management.dto.RevenueManualEntryDTO;
import com.bu.management.dto.RevenueSummaryVO;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.Project;
import com.bu.management.entity.RevenueManualEntry;
import com.bu.management.entity.RevenueMonthlyCost;
import com.bu.management.entity.RevenueMonthlyIncome;
import com.bu.management.entity.RevenueProjectMapping;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RevenueManualEntryMapper;
import com.bu.management.mapper.RevenueMonthlyCostMapper;
import com.bu.management.mapper.RevenueMonthlyIncomeMapper;
import com.bu.management.mapper.RevenueProjectMappingMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RevenueServiceTest {
    @Mock
    private RevenueProjectMappingMapper mappingMapper;
    @Mock
    private RevenueMonthlyCostMapper costMapper;
    @Mock
    private RevenueMonthlyIncomeMapper incomeMapper;
    @Mock
    private RevenueManualEntryMapper manualEntryMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private BusinessLineMapper businessLineMapper;

    private RevenueService service;

    @BeforeEach
    void setUp() {
        service = new RevenueService(mappingMapper, costMapper, incomeMapper, manualEntryMapper,
                projectMapper, businessLineMapper);
    }

    @Test
    void costImportParsesColumnsAndCreatesMapping() throws Exception {
        when(mappingMapper.selectOne(any())).thenReturn(null);
        when(projectMapper.selectList(any())).thenReturn(List.of(project(1L, 10L, "皇家项目")));
        when(businessLineMapper.selectList(any())).thenReturn(List.of(businessLine(10L, "全渠道云鹿定制")));
        when(costMapper.selectOne(any())).thenReturn(null);
        MockMultipartFile file = costWorkbook("2026-01", "皇家宠物【交付】", "2.7500", "123456");

        RevenueImportResultVO result = service.importCostExcel(file);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getNewMappingCount()).isEqualTo(1);
        assertThat(result.getPendingMappingCount()).isZero();
        ArgumentCaptor<RevenueProjectMapping> mappingCaptor = ArgumentCaptor.forClass(RevenueProjectMapping.class);
        verify(mappingMapper).insert(mappingCaptor.capture());
        assertThat(mappingCaptor.getValue()).extracting(
                        RevenueProjectMapping::getSourceType,
                        RevenueProjectMapping::getSourceName,
                        RevenueProjectMapping::getProjectId,
                        RevenueProjectMapping::getBusinessLineId,
                        RevenueProjectMapping::getCategory)
                .containsExactly("cost_project", "皇家宠物【交付】", 1L, null, "delivery");
        ArgumentCaptor<RevenueMonthlyCost> costCaptor = ArgumentCaptor.forClass(RevenueMonthlyCost.class);
        verify(costMapper).insert(costCaptor.capture());
        assertThat(costCaptor.getValue().getYearMonth()).isEqualTo("2026-01");
        assertThat(costCaptor.getValue().getProjectId()).isEqualTo(1L);
        assertThat(costCaptor.getValue().getBusinessLineId()).isEqualTo(10L);
        assertThat(costCaptor.getValue().getWorkHours()).isEqualByComparingTo("2.7500");
        assertThat(costCaptor.getValue().getWorkCost()).isEqualTo(123456L);
    }

    @Test
    void incomeImportMapsBrandsAndRoutesMemberIncomeDirectly() throws Exception {
        RevenueProjectMapping royalMapping = mapping("contract_brand", "皇家宠物", 1L, 10L, "delivery");
        when(mappingMapper.selectOne(any())).thenReturn(royalMapping);
        when(projectMapper.selectList(any())).thenReturn(List.of(project(1L, 10L, "皇家项目")));
        when(businessLineMapper.selectList(any())).thenReturn(List.of(
                businessLine(10L, "全渠道云鹿定制"), businessLine(30L, "会员通")));
        when(incomeMapper.selectOne(any())).thenReturn(null);
        MockMultipartFile file = incomeWorkbook(List.of(
                new IncomeRow("2026-02", "皇家宠物", "项目交付", 800000L, 700000L),
                new IncomeRow("2026-02", "", "会员通服务费", 300000L, 250000L)));

        RevenueImportResultVO result = service.importIncomeExcel(file);

        assertThat(result.getSuccessCount()).isEqualTo(2);
        assertThat(result.getNewMappingCount()).isZero();
        assertThat(result.getPendingMappingCount()).isZero();
        ArgumentCaptor<RevenueMonthlyIncome> captor = ArgumentCaptor.forClass(RevenueMonthlyIncome.class);
        verify(incomeMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(income -> {
            assertThat(income.getProjectId()).isEqualTo(1L);
            assertThat(income.getBusinessLineId()).isEqualTo(10L);
            assertThat(income.getReceivableAmount()).isEqualTo(800000L);
            assertThat(income.getReceivedAmount()).isEqualTo(700000L);
            assertThat(income.getContractCount()).isEqualTo(1);
        }).anySatisfy(income -> {
            assertThat(income.getProjectId()).isNull();
            assertThat(income.getBusinessLineId()).isEqualTo(30L);
            assertThat(income.getReceivableAmount()).isEqualTo(300000L);
        });
    }

    @Test
    void summaryAggregatesProjectAndBusinessLineCosts() {
        stubSummaryData(
                List.of(
                        cost("2026-01", 1L, 10L, "delivery", "4.5", 400L),
                        cost("2026-01", 1L, 10L, "sales", "1.5", 100L),
                        cost("2026-01", null, 30L, "delivery", "2", 200L)),
                List.of(
                        income("2026-01", 1L, 10L, 1000L),
                        income("2026-01", null, 30L, 500L)),
                List.of(
                        manual("2026-01", 1L, 10L, "partner_cost", 50L),
                        manual("2026-07", 1L, 10L, "h2_estimate", 300L),
                        manual("2026-01", null, 30L, "server_cost", 25L)));

        RevenueSummaryVO summary = service.getSummary(2026);

        assertThat(summary.getTotalReceivable()).isEqualTo(1500L);
        assertThat(summary.getTotalCost()).isEqualTo(775L);
        RevenueSummaryVO.BusinessLineSummary custom = findBusinessLine(summary, 10L);
        assertThat(custom.getType()).isEqualTo("project_breakdown");
        assertThat(custom.getTotalReceivable()).isEqualTo(1000L);
        assertThat(custom.getTotalCost()).isEqualTo(550L);
        RevenueSummaryVO.ProjectSummary project = custom.getProjects().get(0);
        assertThat(project.getDeliveryHours()).isEqualByComparingTo("4.5");
        assertThat(project.getDeliveryCost()).isEqualTo(400L);
        assertThat(project.getSalesCost()).isEqualTo(100L);
        assertThat(project.getPartnerCost()).isEqualTo(50L);
        assertThat(project.getH2Estimate()).isEqualTo(300L);
        RevenueSummaryVO.BusinessLineSummary member = findBusinessLine(summary, 30L);
        assertThat(member.getType()).isEqualTo("business_line_summary");
        assertThat(member.getProjects()).isNull();
        assertThat(member.getTotalCost()).isEqualTo(225L);
        assertThat(summary.getMonthlyTrend()).singleElement().satisfies(month -> {
            assertThat(month.getMonth()).isEqualTo("2026-01");
            assertThat(month.getIncome()).isEqualTo(1500L);
            assertThat(month.getCost()).isEqualTo(775L);
        });
    }

    @Test
    void summaryComputesProfitAndProfitRateWithZeroGuard() {
        stubSummaryData(
                List.of(cost("2026-03", 1L, 10L, "delivery", "1", 250L)),
                List.of(income("2026-03", 1L, 10L, 1000L)),
                List.of());

        RevenueSummaryVO summary = service.getSummary(2026);

        assertThat(summary.getTotalProfit()).isEqualTo(750L);
        assertThat(summary.getProfitRate()).isEqualByComparingTo("0.7500");
        assertThat(findBusinessLine(summary, 10L).getProjects().get(0).getProfitRate())
                .isEqualByComparingTo("0.7500");

        stubSummaryData(List.of(cost("2026-04", 1L, 10L, "delivery", "1", 100L)), List.of(), List.of());
        RevenueSummaryVO zeroIncome = service.getSummary(2026);
        assertThat(zeroIncome.getProfitRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(findBusinessLine(zeroIncome, 10L).getProjects().get(0).getProfitRate())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void manualEntryRejectsInvalidEntryType() {
        RevenueManualEntryDTO request = new RevenueManualEntryDTO();
        request.setYearMonth("2026-01");
        request.setBusinessLineId(10L);
        request.setEntryType("delivery_cost");
        request.setAmount(100L);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.createManualEntry(request, 7L))
                .withMessageContaining("entry_type");
        verify(manualEntryMapper, never()).insert(any());
    }

    @Test
    void autoMappingMatchesKnownKeywordAndSalesSuffix() throws Exception {
        when(mappingMapper.selectOne(any())).thenReturn(null);
        when(projectMapper.selectList(any())).thenReturn(List.of(project(10L, 20L, "Speedo")));
        when(businessLineMapper.selectList(any())).thenReturn(List.of(businessLine(20L, "全渠道云鹿SAAS")));
        when(costMapper.selectOne(any())).thenReturn(null);

        RevenueImportResultVO result = service.importCostExcel(
                costWorkbook("2026-05", "Speedo专项【销售】", "1", "888"));

        assertThat(result.getSuccessCount()).isEqualTo(1);
        ArgumentCaptor<RevenueProjectMapping> captor = ArgumentCaptor.forClass(RevenueProjectMapping.class);
        verify(mappingMapper).insert(captor.capture());
        assertThat(captor.getValue().getProjectId()).isEqualTo(10L);
        assertThat(captor.getValue().getBusinessLineId()).isNull();
        assertThat(captor.getValue().getCategory()).isEqualTo("sales");
    }

    private void stubSummaryData(List<RevenueMonthlyCost> costs, List<RevenueMonthlyIncome> incomes,
                                 List<RevenueManualEntry> manualEntries) {
        when(costMapper.selectList(any())).thenReturn(costs);
        when(incomeMapper.selectList(any())).thenReturn(incomes);
        when(manualEntryMapper.selectList(any())).thenReturn(manualEntries);
        when(projectMapper.selectList(any())).thenReturn(List.of(project(1L, 10L, "皇家项目")));
        when(businessLineMapper.selectList(any())).thenReturn(List.of(
                businessLine(10L, "全渠道云鹿定制"), businessLine(30L, "会员通")));
    }

    private RevenueSummaryVO.BusinessLineSummary findBusinessLine(RevenueSummaryVO summary, Long id) {
        return summary.getBusinessLines().stream()
                .filter(line -> id.equals(line.getBusinessLineId()))
                .findFirst()
                .orElseThrow();
    }

    private MockMultipartFile costWorkbook(String month, String projectName, String hours, String cost)
            throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("成本分析-项目视角");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("月份");
            header.createCell(2).setCellValue("项目名");
            header.createCell(5).setCellValue("工时");
            header.createCell(6).setCellValue("工时成本");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(month);
            row.createCell(2).setCellValue(projectName);
            row.createCell(5).setCellValue(Double.parseDouble(hours));
            row.createCell(6).setCellValue(Double.parseDouble(cost));
            workbook.write(output);
            return new MockMultipartFile("file", "cost.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }

    private MockMultipartFile incomeWorkbook(List<IncomeRow> rows) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("合同明细");
            Row header = sheet.createRow(0);
            String[] headers = {"收款销售月份", "品牌", "收款项类型", "应收金额", "实收金额"};
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            for (int i = 0; i < rows.size(); i++) {
                IncomeRow source = rows.get(i);
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(source.month());
                row.createCell(1).setCellValue(source.brand());
                row.createCell(2).setCellValue(source.type());
                row.createCell(3).setCellValue(source.receivable());
                row.createCell(4).setCellValue(source.received());
            }
            workbook.write(output);
            return new MockMultipartFile("file", "income.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }

    private Project project(Long id, Long businessLineId, String name) {
        Project project = new Project();
        project.setId(id);
        project.setBusinessLineId(businessLineId);
        project.setName(name);
        return project;
    }

    private BusinessLine businessLine(Long id, String name) {
        BusinessLine line = new BusinessLine();
        line.setId(id);
        line.setName(name);
        return line;
    }

    private RevenueProjectMapping mapping(String sourceType, String sourceName, Long projectId,
                                          Long businessLineId, String category) {
        RevenueProjectMapping mapping = new RevenueProjectMapping();
        mapping.setSourceType(sourceType);
        mapping.setSourceName(sourceName);
        mapping.setProjectId(projectId);
        mapping.setBusinessLineId(businessLineId);
        mapping.setCategory(category);
        mapping.setStatus(1);
        return mapping;
    }

    private RevenueMonthlyCost cost(String month, Long projectId, Long businessLineId, String category,
                                    String hours, Long amount) {
        RevenueMonthlyCost cost = new RevenueMonthlyCost();
        cost.setYearMonth(month);
        cost.setProjectId(projectId);
        cost.setBusinessLineId(businessLineId);
        cost.setCategory(category);
        cost.setWorkHours(new BigDecimal(hours));
        cost.setWorkCost(amount);
        return cost;
    }

    private RevenueMonthlyIncome income(String month, Long projectId, Long businessLineId, Long receivable) {
        RevenueMonthlyIncome income = new RevenueMonthlyIncome();
        income.setYearMonth(month);
        income.setProjectId(projectId);
        income.setBusinessLineId(businessLineId);
        income.setContractCount(1);
        income.setReceivableAmount(receivable);
        income.setReceivedAmount(receivable);
        return income;
    }

    private RevenueManualEntry manual(String month, Long projectId, Long businessLineId, String type, Long amount) {
        RevenueManualEntry entry = new RevenueManualEntry();
        entry.setYearMonth(month);
        entry.setProjectId(projectId);
        entry.setBusinessLineId(businessLineId);
        entry.setEntryType(type);
        entry.setAmount(amount);
        return entry;
    }

    private record IncomeRow(String month, String brand, String type, Long receivable, Long received) {
    }
}
