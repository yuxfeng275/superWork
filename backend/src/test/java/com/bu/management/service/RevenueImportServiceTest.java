package com.bu.management.service;

import com.bu.management.entity.RevenueCostEntry;
import com.bu.management.entity.RevenueImportBatch;
import com.bu.management.entity.RevenueWorklogEntry;
import com.bu.management.mapper.RevenueCostEntryMapper;
import com.bu.management.mapper.RevenueImportBatchMapper;
import com.bu.management.mapper.RevenueWorklogEntryMapper;
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
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RevenueImportServiceTest {

    @Mock private RevenueWorklogEntryMapper worklogEntryMapper;
    @Mock private RevenueCostEntryMapper costEntryMapper;
    @Mock private RevenueImportBatchMapper importBatchMapper;
    @Mock private RevenueMonthService monthService;
    @Mock private RevenueMappingResolver mappingResolver;

    private RevenueImportService service;

    @BeforeEach
    void setUp() {
        service = new RevenueImportService(worklogEntryMapper, costEntryMapper, importBatchMapper,
                mappingResolver, monthService);
        lenient().when(importBatchMapper.insert(any())).thenAnswer(invocation -> {
            RevenueImportBatch batch = invocation.getArgument(0);
            batch.setId(1L);
            return 1;
        });
    }

    private MockMultipartFile workbookFile(String name, List<List<String>> rows) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("data");
            for (int i = 0; i < rows.size(); i++) {
                Row row = sheet.createRow(i);
                List<String> values = rows.get(i);
                for (int j = 0; j < values.size(); j++) {
                    row.createCell(j).setCellValue(values.get(j));
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return new MockMultipartFile("file", name,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }

    @Test
    void worklogImportParsesPersonRowsAndReplacesMonth() throws IOException {
        MockMultipartFile file = workbookFile("工时数据_业务线明细_2026-07.xlsx", List.of(
                List.of("工号", "姓名", "部门", "业务线", "项目", "工时占比", "工作说明", "特殊说明"),
                List.of("01165", "翁擎天", "全域业务拓展部", "全域-全渠道-全域云鹿定制", "澳优项目【交付】", "0.15", "佳贝日常问题", ""),
                List.of("01333", "熊雪聪", "全域业务拓展部", "全域-全渠道-会员通", "全域-全渠道-会员通【销售】", "0.227", "会员通售前", "")
        ));
        lenient().when(mappingResolver.resolve(any(), any())).thenAnswer(invocation ->
                new RevenueMappingResolver.Resolved(1L, 13L, null, "project", null, "澳优", false, false));

        service.importWorklog(file, "2026-07", 16L);

        ArgumentCaptor<RevenueWorklogEntry> captor = ArgumentCaptor.forClass(RevenueWorklogEntry.class);
        verify(worklogEntryMapper, times(2)).insert(captor.capture());
        List<RevenueWorklogEntry> inserted = captor.getAllValues();
        assertThat(inserted.get(0).getEmployeeName()).isEqualTo("翁擎天");
        assertThat(inserted.get(0).getHours()).isEqualByComparingTo(new BigDecimal("0.15"));
        assertThat(inserted.get(0).getYearMonth()).isEqualTo("2026-07");
        assertThat(inserted.get(0).getProjectId()).isEqualTo(13L);
        // 同月重复导入 = 整月覆盖（含手工补录行），以最后导入为准
        ArgumentCaptor<LambdaQueryWrapper<RevenueWorklogEntry>> deleteCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(worklogEntryMapper).delete(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().getSqlSegment()).doesNotContain("batch_id");
    }

    @Test
    void costImportReadsMonthFromFile() throws IOException {
        MockMultipartFile file = workbookFile("成本分析_项目_2026-07.xlsx", List.of(
                List.of("月份", "业务线名称", "项目名", "员工数", "项目数", "工时", "工时成本", "人月成本"),
                List.of("2026-07", "全域-全渠道-全域云鹿定制", "澳优项目【交付】", "14", "1", "2.4", "38098", "15987")
        ));
        lenient().when(mappingResolver.resolve(any(), any())).thenAnswer(invocation ->
                new RevenueMappingResolver.Resolved(1L, 13L, null, "project", null, "澳优", false, false));

        service.importCost(file, 16L);

        ArgumentCaptor<RevenueCostEntry> captor = ArgumentCaptor.forClass(RevenueCostEntry.class);
        verify(costEntryMapper).insert(captor.capture());
        RevenueCostEntry inserted = captor.getValue();
        assertThat(inserted.getYearMonth()).isEqualTo("2026-07");
        assertThat(inserted.getHours()).isEqualByComparingTo(new BigDecimal("2.4"));
        assertThat(inserted.getCostAmount()).isEqualByComparingTo(new BigDecimal("38098"));
        assertThat(inserted.getPersonMonthCost()).isEqualByComparingTo(new BigDecimal("15987"));
        assertThat(inserted.getEmployeeCount()).isEqualTo(14);
    }

    @Test
    void closedMonthRejectsWorklogImport() {
        lenient().doThrow(new IllegalStateException("2026-07 已完结"))
                .when(monthService).assertNotClosed("2026-07");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.importWorklog(new MockMultipartFile("file", new byte[0]), "2026-07", 16L))
                .isInstanceOf(IllegalStateException.class);
    }
}
