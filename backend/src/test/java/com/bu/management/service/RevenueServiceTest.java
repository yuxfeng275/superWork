package com.bu.management.service;

import com.bu.management.dto.RevenueImportResultVO;
import com.bu.management.dto.RevenueInitResultVO;
import com.bu.management.dto.RevenueManualEntryDTO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.dto.RevenueSummaryVO;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.Project;
import com.bu.management.entity.RevenueImportRecord;
import com.bu.management.entity.RevenueManualEntry;
import com.bu.management.entity.RevenueMonthlyCost;
import com.bu.management.entity.RevenueMonthlyIncome;
import com.bu.management.entity.RevenueProjectMapping;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RevenueImportRecordMapper;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RevenueServiceTest {
    @Mock
    private RevenueImportRecordMapper importRecordMapper;
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
                projectMapper, businessLineMapper, importRecordMapper);
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

    // The service's ReentrantLock is sufficient for single-instance deployment; multi-instance deployment
    // would require SELECT FOR UPDATE or a distributed lock.
    @Test
    void costImportUpsertsBusinessLineLevelRowsWhenImportedTwice() throws Exception {
        RevenueProjectMapping memberMapping = mapping("cost_project", "会员通项目", null, 30L, "delivery");
        List<RevenueMonthlyCost> storedCosts = new ArrayList<>();
        when(mappingMapper.selectOne(any())).thenReturn(memberMapping);
        when(projectMapper.selectList(any())).thenReturn(List.of());
        when(businessLineMapper.selectList(any())).thenReturn(List.of(businessLine(30L, "会员通")));
        when(costMapper.selectOne(any())).thenAnswer(invocation -> storedCosts.stream().findFirst().orElse(null));
        doAnswer(invocation -> {
            RevenueMonthlyCost inserted = invocation.getArgument(0);
            inserted.setId(1L);
            storedCosts.add(inserted);
            return 1;
        }).when(costMapper).insert(any());

        MockMultipartFile file = costWorkbook("2026-01", "会员通项目", "2", "100");
        service.importCostExcel(file);
        service.importCostExcel(file);

        assertThat(storedCosts).hasSize(1);
        verify(costMapper, times(1)).insert(any());
        verify(costMapper, times(1)).updateById(storedCosts.get(0));
    }

    @Test
    void incomeImportAggregatesRowsForSameMonthAndBrand() throws Exception {
        when(mappingMapper.selectOne(any()))
                .thenReturn(mapping("contract_brand", "皇家宠物", 1L, 10L, "delivery"));
        when(projectMapper.selectList(any())).thenReturn(List.of(project(1L, 10L, "皇家项目")));
        when(businessLineMapper.selectList(any())).thenReturn(List.of(businessLine(10L, "全渠道云鹿定制")));
        when(incomeMapper.selectOne(any())).thenReturn(null);
        MockMultipartFile file = incomeWorkbook(List.of(
                new IncomeRow("2026-02", "皇家宠物", "项目交付", 800000L, 700000L),
                new IncomeRow("2026-02", "皇家宠物", "项目交付", 200000L, 150000L)));

        RevenueImportResultVO result = service.importIncomeExcel(file);

        assertThat(result.getSuccessCount()).isEqualTo(2);
        ArgumentCaptor<RevenueMonthlyIncome> captor = ArgumentCaptor.forClass(RevenueMonthlyIncome.class);
        verify(incomeMapper).insert(captor.capture());
        assertThat(captor.getValue().getContractCount()).isEqualTo(2);
        assertThat(captor.getValue().getReceivableAmount()).isEqualTo(1000000L);
        assertThat(captor.getValue().getReceivedAmount()).isEqualTo(850000L);
    }

    @Test
    void autoMappingAssignsMemberSalesCostToMemberBusinessLine() throws Exception {
        when(mappingMapper.selectOne(any())).thenReturn(null);
        when(projectMapper.selectList(any())).thenReturn(List.of());
        when(businessLineMapper.selectList(any())).thenReturn(List.of(businessLine(30L, "会员通")));
        when(costMapper.selectOne(any())).thenReturn(null);

        RevenueImportResultVO result = service.importCostExcel(
                costWorkbook("2026-05", "会员通【销售】", "1", "888"));

        assertThat(result.getSuccessCount()).isEqualTo(1);
        ArgumentCaptor<RevenueProjectMapping> captor = ArgumentCaptor.forClass(RevenueProjectMapping.class);
        verify(mappingMapper).insert(captor.capture());
        assertThat(captor.getValue().getBusinessLineId()).isEqualTo(30L);
        assertThat(captor.getValue().getCategory()).isEqualTo("sales");
    }

    @Test
    void disabledMappingIsReactivatedDuringImport() throws Exception {
        RevenueProjectMapping disabledMapping = mapping("cost_project", "逢时项目", 99L, 99L, "sales");
        disabledMapping.setStatus(0);
        when(mappingMapper.selectOne(any())).thenReturn(null, disabledMapping);
        when(projectMapper.selectList(any())).thenReturn(List.of(project(1L, 10L, "逢时项目")));
        when(businessLineMapper.selectList(any())).thenReturn(List.of(businessLine(10L, "全渠道云鹿定制")));
        when(costMapper.selectOne(any())).thenReturn(null);

        RevenueImportResultVO result = service.importCostExcel(
                costWorkbook("2026-05", "逢时项目", "1", "888"));

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getNewMappingCount()).isZero();
        assertThat(disabledMapping.getStatus()).isEqualTo(1);
        assertThat(disabledMapping.getProjectId()).isEqualTo(1L);
        assertThat(disabledMapping.getBusinessLineId()).isNull();
        assertThat(disabledMapping.getCategory()).isEqualTo("product");
        verify(mappingMapper).updateById(disabledMapping);
        verify(mappingMapper, never()).insert(any());
    }

    @Test
    void categoryUsesSalesSuffixProductKeywordAndDefaultsToDelivery() throws Exception {
        when(mappingMapper.selectOne(any())).thenReturn(null);
        when(projectMapper.selectList(any())).thenReturn(List.of());
        when(businessLineMapper.selectList(any())).thenReturn(List.of(businessLine(10L, "全渠道云鹿定制")));

        service.importCostExcel(costWorkbook("2026-05", "逢时项目", "1", "100"));
        service.importCostExcel(costWorkbook("2026-05", "黄天鹅项目", "1", "100"));
        service.importCostExcel(costWorkbook("2026-05", "京博项目", "1", "100"));

        ArgumentCaptor<RevenueProjectMapping> captor = ArgumentCaptor.forClass(RevenueProjectMapping.class);
        verify(mappingMapper, times(3)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(RevenueProjectMapping::getCategory)
                .containsExactly("product", "product", "delivery");
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

        assertThat(summary.getH1Receivable()).isEqualTo(1500L);
        assertThat(summary.getH2Receivable()).isZero();
        assertThat(summary.getH2Estimate()).isEqualTo(300L);
        assertThat(summary.getTotalCost()).isEqualTo(775L);
        assertThat(summary.getProfit()).isEqualTo(1025L);
        assertThat(summary.getProfitRate()).isEqualByComparingTo("0.6833");
        RevenueSummaryVO.BusinessLineSummary custom = findBusinessLine(summary, 10L);
        assertThat(custom.getType()).isEqualTo("project_breakdown");
        assertThat(custom.getH1Receivable()).isEqualTo(1000L);
        assertThat(custom.getH2Receivable()).isZero();
        assertThat(custom.getH1DeliveryCost()).isEqualTo(500L);
        assertThat(custom.getTotalCost()).isEqualTo(550L);
        assertThat(custom.getProfit()).isEqualTo(750L);
        RevenueSummaryVO.ProjectSummary project = custom.getProjects().get(0);
        assertThat(project.getH1Receivable()).isEqualTo(1000L);
        assertThat(project.getH2Receivable()).isZero();
        assertThat(project.getH1Hours()).isEqualByComparingTo("4.5");
        assertThat(project.getH2Hours()).isEqualByComparingTo("0");
        assertThat(project.getH1DeliveryCost()).isEqualTo(500L);
        assertThat(project.getH2DeliveryCost()).isZero();
        assertThat(project.getPartnerCost()).isEqualTo(50L);
        assertThat(project.getH2Estimate()).isEqualTo(300L);
        assertThat(project.getTotalCost()).isEqualTo(550L);
        assertThat(project.getProfit()).isEqualTo(750L);
        RevenueSummaryVO.BusinessLineSummary member = findBusinessLine(summary, 30L);
        assertThat(member.getType()).isEqualTo("business_line_summary");
        assertThat(member.getProjects()).isNull();
        assertThat(member.getH1Receivable()).isEqualTo(500L);
        assertThat(member.getServerCost()).isEqualTo(25L);
        assertThat(member.getTotalCost()).isEqualTo(225L);
        assertThat(member.getProfit()).isEqualTo(275L);
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

        assertThat(summary.getProfit()).isEqualTo(750L);
        assertThat(summary.getProfitRate()).isEqualByComparingTo("0.7500");
        assertThat(findBusinessLine(summary, 10L).getProjects().get(0).getProfitRate())
                .isEqualByComparingTo("0.7500");

        stubSummaryData(List.of(cost("2026-04", 1L, 10L, "delivery", "1", 100L)), List.of(), List.of());
        RevenueSummaryVO zeroIncome = service.getSummary(2026);
        assertThat(zeroIncome.getProfitRate()).isNull();
        assertThat(findBusinessLine(zeroIncome, 10L).getProjects().get(0).getProfitRate()).isNull();
    }

    @Test
    void manualEntryRejectsProjectFromAnotherBusinessLine() {
        RevenueManualEntryDTO request = new RevenueManualEntryDTO();
        request.setYearMonth("2026-01");
        request.setBusinessLineId(10L);
        request.setProjectId(5L);
        request.setEntryType("partner_cost");
        request.setAmount(100L);
        when(businessLineMapper.selectById(10L)).thenReturn(businessLine(10L, "全渠道云鹿定制"));
        when(projectMapper.selectById(5L)).thenReturn(project(5L, 30L, "其他项目"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.createManualEntry(request, 7L))
                .withMessageContaining("不属于所选业务线");
        verify(manualEntryMapper, never()).insert(any());
    }

    @Test
    void manualEntryRejectsUnknownProject() {
        RevenueManualEntryDTO request = new RevenueManualEntryDTO();
        request.setYearMonth("2026-01");
        request.setBusinessLineId(10L);
        request.setProjectId(999L);
        request.setEntryType("partner_cost");
        request.setAmount(100L);
        when(businessLineMapper.selectById(10L)).thenReturn(businessLine(10L, "全渠道云鹿定制"));
        when(projectMapper.selectById(999L)).thenReturn(null);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.createManualEntry(request, 7L))
                .withMessageContaining("project_id");
        verify(manualEntryMapper, never()).insert(any());
    }

    @Test
    void updateMappingRejectsInvalidBusinessLineReference() {
        RevenueProjectMapping existing = mapping("cost_project", "皇家项目", 1L, 10L, "delivery");
        RevenueProjectMapping request = mapping("cost_project", "皇家项目", 1L, 999L, "delivery");
        when(mappingMapper.selectById(1L)).thenReturn(existing);
        when(projectMapper.selectById(1L)).thenReturn(project(1L, 10L, "皇家项目"));
        when(businessLineMapper.selectById(999L)).thenReturn(null);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.updateMapping(1L, request))
                .withMessageContaining("business_line_id");
        verify(mappingMapper, never()).updateById(any());
    }

    @Test
    void updateMappingRejectsProjectNotInSelectedBusinessLine() {
        RevenueProjectMapping existing = mapping("cost_project", "皇家项目", 1L, 10L, "delivery");
        RevenueProjectMapping request = mapping("cost_project", "皇家项目", 1L, 30L, "delivery");
        when(mappingMapper.selectById(1L)).thenReturn(existing);
        when(projectMapper.selectById(1L)).thenReturn(project(1L, 10L, "皇家项目"));
        when(businessLineMapper.selectById(30L)).thenReturn(businessLine(30L, "会员通"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.updateMapping(1L, request))
                .withMessageContaining("不属于所选业务线");
        verify(mappingMapper, never()).updateById(any());
    }

    @Test
    void importCostRecordsImportHistoryWithUser() throws Exception {
        when(mappingMapper.selectOne(any())).thenReturn(null);
        when(projectMapper.selectList(any())).thenReturn(List.of(project(1L, 10L, "皇家项目")));
        when(businessLineMapper.selectList(any())).thenReturn(List.of(businessLine(10L, "全渠道云鹿定制")));
        when(costMapper.selectOne(any())).thenReturn(null);

        RevenueImportResultVO result = service.importCostExcel(
                costWorkbook("2026-01", "皇家宠物", "2", "100"), 7L);

        assertThat(result.getSuccessCount()).isEqualTo(1);
        ArgumentCaptor<RevenueImportRecord> captor = ArgumentCaptor.forClass(RevenueImportRecord.class);
        verify(importRecordMapper).insert(captor.capture());
        assertThat(captor.getValue().getImportType()).isEqualTo("cost");
        assertThat(captor.getValue().getFileName()).isEqualTo("cost.xlsx");
        assertThat(captor.getValue().getSuccessCount()).isEqualTo(1);
        assertThat(captor.getValue().getErrorCount()).isZero();
        assertThat(captor.getValue().getCreatedBy()).isEqualTo(7L);
    }

    @Test
    void listImportRecordsFiltersByType() {
        RevenueImportRecord costRecord = new RevenueImportRecord();
        costRecord.setImportType("cost");
        when(importRecordMapper.selectList(any())).thenReturn(List.of(costRecord));

        List<RevenueImportRecord> records = service.listImportRecords("cost");

        assertThat(records).hasSize(1);
        assertThat(records.get(0).getImportType()).isEqualTo("cost");
    }

    @Test
    void updateMappingRejectsInvalidProjectReference() {
        RevenueProjectMapping existing = mapping("cost_project", "皇家项目", 1L, 10L, "delivery");
        RevenueProjectMapping request = mapping("cost_project", "皇家项目", 999L, 10L, "delivery");
        when(mappingMapper.selectById(1L)).thenReturn(existing);
        when(projectMapper.selectById(999L)).thenReturn(null);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.updateMapping(1L, request))
                .withMessageContaining("project_id");
        verify(mappingMapper, never()).updateById(any());
    }

    @Test
    void manualEntryRejectsInvalidBusinessLineReference() {
        RevenueManualEntryDTO request = new RevenueManualEntryDTO();
        request.setYearMonth("2026-01");
        request.setBusinessLineId(999L);
        request.setEntryType("partner_cost");
        request.setAmount(100L);
        when(businessLineMapper.selectById(999L)).thenReturn(null);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.createManualEntry(request, 7L))
                .withMessageContaining("business_line_id");
        verify(manualEntryMapper, never()).insert(any());
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

    @Test
    void initializeWritesCostsManualsAndH1Income() throws Exception {
        when(projectMapper.selectList(any())).thenReturn(List.of(
                project(1L, 10L, "皇家项目"), project(10L, 20L, "Speedo项目")));
        when(businessLineMapper.selectList(any())).thenReturn(List.of(
                businessLine(10L, "全渠道云鹿定制"), businessLine(20L, "全渠道云鹿SAAS")));
        when(costMapper.selectOne(any())).thenReturn(null);
        when(incomeMapper.selectOne(any())).thenReturn(null);
        MockMultipartFile file = initWorkbook();

        RevenueInitResultVO result = service.initializeFromWorkbook(file, 2026, 7L);

        assertThat(result.getImportedProjectCount()).isEqualTo(1);
        assertThat(result.getErrors()).isEmpty();
        verify(costMapper).delete(any());
        verify(manualEntryMapper).delete(any());
        verify(incomeMapper).delete(any());
        // 皇家：12 个月工时成本 + H1交付分摊 6 个月 + H2预估 1条
        ArgumentCaptor<RevenueMonthlyCost> costCaptor = ArgumentCaptor.forClass(RevenueMonthlyCost.class);
        verify(costMapper, times(12)).insert(costCaptor.capture());
        assertThat(costCaptor.getAllValues()).first().satisfies(cost -> {
            assertThat(cost.getYearMonth()).isEqualTo("2026-01");
            assertThat(cost.getProjectId()).isEqualTo(1L);
            assertThat(cost.getBusinessLineId()).isEqualTo(10L);
            assertThat(cost.getCategory()).isEqualTo("delivery");
            assertThat(cost.getWorkHours()).isEqualByComparingTo("4.77");
        });
        ArgumentCaptor<RevenueMonthlyIncome> incomeCaptor = ArgumentCaptor.forClass(RevenueMonthlyIncome.class);
        verify(incomeMapper, times(6)).insert(incomeCaptor.capture());
        assertThat(incomeCaptor.getAllValues()).hasSize(6);
        assertThat(incomeCaptor.getAllValues()).filteredOn(
                        item -> "2026-06".equals(item.getYearMonth()))
                .singleElement().satisfies(item -> {
                    assertThat(item.getReceivableAmount()).isEqualTo(149335L);
                    assertThat(item.getReceivedAmount()).isZero();
                });
        assertThat(incomeCaptor.getAllValues()).filteredOn(
                        item -> "2026-01".equals(item.getYearMonth()))
                .singleElement().satisfies(item -> assertThat(item.getReceivableAmount()).isEqualTo(149333L));
        ArgumentCaptor<RevenueManualEntry> manualCaptor = ArgumentCaptor.forClass(RevenueManualEntry.class);
        verify(manualEntryMapper, times(1)).insert(manualCaptor.capture());
        assertThat(manualCaptor.getValue().getEntryType()).isEqualTo("h2_estimate");
        assertThat(manualCaptor.getValue().getYearMonth()).isEqualTo("2026-12");
        assertThat(manualCaptor.getValue().getAmount()).isEqualTo(1280000L);
        assertThat(manualCaptor.getValue().getCreatedBy()).isEqualTo(7L);
        assertThat(result.getManualRowCount()).isEqualTo(1);
        assertThat(result.getIncomeRowCount()).isEqualTo(6);
    }

    @Test
    void initializeFromWorkbookSkipsUnknownProject() throws Exception {
        when(projectMapper.selectList(any())).thenReturn(List.of());
        when(businessLineMapper.selectList(any())).thenReturn(List.of());
        MockMultipartFile file = initWorkbook();

        RevenueInitResultVO result = service.initializeFromWorkbook(file, 2026, 7L);

        assertThat(result.getImportedProjectCount()).isZero();
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0)).contains("未找到项目映射");
        verify(costMapper, never()).insert(any());
        verify(manualEntryMapper, never()).insert(any());
    }

    @Test
    void initializeWritesAoyouAsSingleProject() throws Exception {
        when(projectMapper.selectList(any())).thenReturn(List.of(
                project(1L, 10L, "皇家项目"), project(20L, 10L, "澳优")));
        when(businessLineMapper.selectList(any())).thenReturn(List.of(businessLine(10L, "全渠道云鹿定制")));
        when(costMapper.selectOne(any())).thenReturn(null);

        RevenueInitResultVO result = service.initializeFromWorkbook(initWorkbook(true), 2026, 7L);

        assertThat(result.getImportedProjectCount()).isEqualTo(2);
        assertThat(result.getErrors()).isEmpty();
        // 皇家 12 个月 + 澳优 1 个月
        ArgumentCaptor<RevenueMonthlyCost> costCaptor = ArgumentCaptor.forClass(RevenueMonthlyCost.class);
        verify(costMapper, times(13)).insert(costCaptor.capture());
        assertThat(costCaptor.getAllValues()).filteredOn(c -> c.getProjectId() == 20L)
                .singleElement().satisfies(c -> {
                    assertThat(c.getWorkHours()).isEqualByComparingTo("2");
                    assertThat(c.getWorkCost()).isEqualTo(30000L);
                });
        // 皇家 H2预估 128万 + 澳优 H2预估 100万（全额，不拆分）
        ArgumentCaptor<RevenueManualEntry> manualCaptor = ArgumentCaptor.forClass(RevenueManualEntry.class);
        verify(manualEntryMapper, times(2)).insert(manualCaptor.capture());
        assertThat(manualCaptor.getAllValues()).filteredOn(
                        item -> item.getProjectId() == 20L && "h2_estimate".equals(item.getEntryType()))
                .singleElement().satisfies(item -> assertThat(item.getAmount()).isEqualTo(1000000L));
        // H1交付：皇家 89.6万 + 澳优 19.09万，各分摊 6 个月
        ArgumentCaptor<RevenueMonthlyIncome> incomeCaptor = ArgumentCaptor.forClass(RevenueMonthlyIncome.class);
        verify(incomeMapper, times(12)).insert(incomeCaptor.capture());
        assertThat(incomeCaptor.getAllValues()).filteredOn(item -> item.getProjectId() == 20L)
                .hasSize(6)
                .allSatisfy(item -> assertThat(item.getBusinessLineId()).isEqualTo(10L));
        assertThat(incomeCaptor.getAllValues()).filteredOn(
                        item -> item.getProjectId() == 20L && "2026-06".equals(item.getYearMonth()))
                .singleElement().satisfies(item -> assertThat(item.getReceivableAmount()).isEqualTo(31820L));
    }

    private MockMultipartFile initWorkbook() throws Exception {
        return initWorkbook(false);
    }

    private MockMultipartFile initWorkbook(boolean includeAoyou) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("一页");
            // 工时明细区域（Excel 行 3 表头 / 4-9 项目）
            Row hoursHeader = sheet.createRow(2);
            hoursHeader.createCell(2).setCellValue("项目");
            for (int m = 0; m < 12; m++) {
                hoursHeader.createCell(3 + m).setCellValue((m + 1) + "月");
            }
            Row royalHours = sheet.createRow(3);
            royalHours.createCell(2).setCellValue("皇家");
            double[] hours = {4.77, 9.9646, 6.388, 4.138, 4.825, 4.366, 4.046, 3.8, 5, 8, 8, 8};
            for (int m = 0; m < 12; m++) {
                royalHours.createCell(3 + m).setCellValue(hours[m]);
            }
            // 成本明细区域（Excel 行 13 表头 / 14-19 项目，万元）
            Row costHeader = sheet.createRow(12);
            costHeader.createCell(2).setCellValue("项目");
            Row royalCost = sheet.createRow(13);
            royalCost.createCell(2).setCellValue("皇家");
            double[] costs = {10.774476, 19.9120210296, 13.0875, 6.2783, 7.4106, 6.82347586666668,
                    5.87, 5.93889333333335, 7.81433333333335, 12.5029333333334, 12.5029333333334, 12.5029333333334};
            for (int m = 0; m < 12; m++) {
                royalCost.createCell(3 + m).setCellValue(costs[m]);
            }
            // 交付营收区域（Excel 行 24 表头 / 25-30 项目）
            Row revenueHeader = sheet.createRow(23);
            revenueHeader.createCell(3).setCellValue("H1交付(万)");
            revenueHeader.createCell(7).setCellValue("H2预估(万)");
            revenueHeader.createCell(10).setCellValue("协力成本(万)");
            revenueHeader.createCell(11).setCellValue("服务器成本(万)");
            revenueHeader.createCell(12).setCellValue("其他成本(万)");
            Row royalRevenue = sheet.createRow(24);
            royalRevenue.createCell(2).setCellValue("皇家");
            royalRevenue.createCell(3).setCellValue(89.6);
            royalRevenue.createCell(7).setCellValue(128.0);
            royalRevenue.createCell(10).setCellValue(0.0);
            royalRevenue.createCell(11).setCellValue(0.0);
            royalRevenue.createCell(12).setCellValue(0.0);
            if (includeAoyou) {
                // 澳优：工时 1月=2，成本 1月=3万，H1交付=19.09万，H2预估=100万
                Row aoyouHours = sheet.createRow(5);
                aoyouHours.createCell(2).setCellValue("澳优");
                aoyouHours.createCell(3).setCellValue(2.0);
                Row aoyouCost = sheet.createRow(15);
                aoyouCost.createCell(2).setCellValue("澳优");
                aoyouCost.createCell(3).setCellValue(3.0);
                Row aoyouRevenue = sheet.createRow(26);
                aoyouRevenue.createCell(2).setCellValue("澳优");
                aoyouRevenue.createCell(3).setCellValue(19.09);
                aoyouRevenue.createCell(7).setCellValue(100.0);
            }
            workbook.write(output);
            return new MockMultipartFile("file", "项目营收拆解.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
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
            String[] headers = {"收款销售月份", "品牌", "收款款项类型", "应收金额", "实收金额"};
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
