package com.bu.management.service;

import com.bu.management.dto.RevenueImportResultVO;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.Project;
import com.bu.management.entity.RevenueContractEntry;
import com.bu.management.entity.RevenueContractImportBatch;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RevenueContractEntryMapper;
import com.bu.management.mapper.RevenueContractImportBatchMapper;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RevenueContractImportServiceTest {

    @Mock private RevenueContractEntryMapper contractEntryMapper;
    @Mock private RevenueContractImportBatchMapper batchMapper;
    @Mock private BusinessLineMapper businessLineMapper;
    @Mock private ProjectMapper projectMapper;

    private RevenueContractImportService service;

    @BeforeEach
    void setUp() {
        service = new RevenueContractImportService(contractEntryMapper, batchMapper,
                businessLineMapper, projectMapper);
        lenient().when(batchMapper.insert(any())).thenAnswer(invocation -> {
            RevenueContractImportBatch batch = invocation.getArgument(0);
            batch.setId(1L);
            return 1;
        });
        lenient().when(businessLineMapper.selectList(any())).thenReturn(lines());
        lenient().when(projectMapper.selectList(null)).thenReturn(projects());
    }

    private List<BusinessLine> lines() {
        List<BusinessLine> lines = new ArrayList<>();
        lines.add(line(1L, "全渠道云鹿定制", "full"));
        lines.add(line(2L, "全渠道云鹿SAAS", "full"));
        lines.add(line(3L, "会员通", "aggregate"));
        lines.add(line(6L, "全域精准", "simple"));
        return lines;
    }

    private BusinessLine line(Long id, String name, String mode) {
        BusinessLine line = new BusinessLine();
        line.setId(id);
        line.setName(name);
        line.setRevenueMode(mode);
        line.setStatus(1);
        return line;
    }

    private List<Project> projects() {
        List<Project> list = new ArrayList<>();
        list.add(project(1L, 1L, "皇家项目"));
        list.add(project(7L, 1L, "飞鹤"));
        list.add(project(8L, 1L, "佳贝艾特"));
        list.add(project(9L, 1L, "海普诺凯"));
        list.add(project(10L, 1L, "Speedo"));
        list.add(project(24L, 1L, "澳优"));
        list.add(project(15L, 2L, "黄天鹅"));
        list.add(project(18L, 2L, "逢时"));
        return list;
    }

    private Project project(Long id, Long lineId, String name) {
        Project project = new Project();
        project.setId(id);
        project.setBusinessLineId(lineId);
        project.setName(name);
        return project;
    }

    private List<String> headers() {
        return List.of("合同ID", "合同名称", "品牌", "客户名称", "款项内容", "收款款项类型",
                "应收日期", "实收日期", "应收金额", "实收金额", "收款销售日期", "收款销售月份",
                "项目交付日期", "明细表记录ID");
    }

    private MockMultipartFile workbookFile(List<List<String>> rows) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("合同明细");
            for (int i = 0; i < rows.size(); i++) {
                Row row = sheet.createRow(i);
                List<String> values = rows.get(i);
                for (int j = 0; j < values.size(); j++) {
                    String value = values.get(j);
                    if (value != null) {
                        row.createCell(j).setCellValue(value);
                    }
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return new MockMultipartFile("file", "本年销售总额明细.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }

    private List<RevenueContractEntry> captured() {
        ArgumentCaptor<List<RevenueContractEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(contractEntryMapper).upsertBatch(captor.capture());
        return captor.getValue();
    }

    private RevenueContractEntry entry(List<RevenueContractEntry> list, String detailNo) {
        return list.stream().filter(e -> detailNo.equals(e.getDetailNo())).findFirst().orElseThrow();
    }

    @Test
    void importParsesByHeaderNameAndAssignsBrandProjectOrLineLevel() throws IOException {
        MockMultipartFile file = workbookFile(List.of(
                headers(),
                // 皇家宠物品牌 → 皇家项目（定制线主项目）
                List.of("HT-1", "RC合同", "皇家宠物", "玛氏", "定制", "全域-全渠道-全域云鹿定制",
                        "2026-06-30", "", "100000", "", "", "2026-06", "2026-06-30", "d1"),
                // speedo 品牌 → Speedo 项目（行收款类型为精准线，但品牌优先）
                List.of("HT-2", "CDP短信", "speedo", "攀岚", "CDP", "全域-全渠道-全域私域精准",
                        "2026-10-16", "", "5000", "", "", "2026-08", "2026-08-07", "d2"),
                // 澳优品牌簇：海普诺凯品牌 → 澳优项目
                List.of("HT-3", "短信充值", "海普诺凯", "澳优", "短信", "全域-全渠道-全域京东文本短信",
                        "2026-08-31", "", "2000", "", "", "2026-08", "", "d3"),
                // 佳贝品牌 → 澳优项目
                List.of("HT-4", "佳贝短信", "佳贝", "澳优", "短信", "全域-全渠道-全域云鹿定制",
                        "2026-09-10", "", "3800", "", "", "2026-08", "2026-08-13", "d4"),
                // 未知名品牌 + 会员通类型 → 会员通业务线聚合行（非待映射）
                List.of("HT-5", "会员通2.0", "惠氏", "雀巢", "开发", "全域-全渠道-会员通",
                        "2026-11-30", "", "21200", "", "", "2026-08", "", "d5"),
                // 未知名品牌 + 定制类型（full 模式）→ 待映射，不静默丢弃
                List.of("HT-6", "测试合同", "", "某客户", "定制", "全域-全渠道-全域云鹿定制",
                        "2026-12-12", "", "85108", "", "", "2026-12", "", "d6"),
                // 完全未知类型 → 待映射
                List.of("HT-7", "短信", "东鹏", "东鹏", "短信", "全域-全渠道-全域未知类型",
                        "2026-07-22", "", "29600", "", "", "2026-07", "2026-08-06", "d7")
        ));
        lenient().when(businessLineMapper.selectList(any())).thenReturn(lines());

        RevenueImportResultVO result = service.importContracts(file, 16L);

        assertThat(result.getTotalCount()).isEqualTo(7);
        assertThat(result.getPendingCount()).isEqualTo(2);
        assertThat(result.getSuccessCount()).isEqualTo(5);

        List<RevenueContractEntry> inserted = captured();
        assertThat(inserted).hasSize(7);
        RevenueContractEntry royal = entry(inserted, "d1");
        assertThat(royal.getProjectId()).isEqualTo(1L);
        assertThat(royal.getBizLineId()).isEqualTo(1L);
        assertThat(royal.getPending()).isZero();
        RevenueContractEntry speedo = entry(inserted, "d2");
        assertThat(speedo.getProjectId()).isEqualTo(10L);
        assertThat(speedo.getBizLineId()).isEqualTo(1L);
        RevenueContractEntry hipro = entry(inserted, "d3");
        assertThat(hipro.getProjectId()).isEqualTo(24L);
        assertThat(hipro.getBizLineId()).isEqualTo(1L);
        RevenueContractEntry jiabe = entry(inserted, "d4");
        assertThat(jiabe.getProjectId()).isEqualTo(24L);
        RevenueContractEntry member = entry(inserted, "d5");
        assertThat(member.getProjectId()).isNull();
        assertThat(member.getBizLineId()).isEqualTo(3L);
        assertThat(member.getPending()).isZero();
        assertThat(member.getReceivableAmount().toString()).isEqualTo("21200");
        RevenueContractEntry pendingCustom = entry(inserted, "d6");
        assertThat(pendingCustom.getProjectId()).isNull();
        assertThat(pendingCustom.getBizLineId()).isEqualTo(1L);
        assertThat(pendingCustom.getPending()).isEqualTo(1);
        RevenueContractEntry pendingType = entry(inserted, "d7");
        assertThat(pendingType.getBizLineId()).isNull();
        assertThat(pendingType.getPending()).isEqualTo(1);
        // 解析字段
        assertThat(royal.getSaleMonth()).isEqualTo("2026-06");
        assertThat(royal.getDeliveryDate().toString()).isEqualTo("2026-06-30");
        assertThat(royal.getBrand()).isEqualTo("皇家宠物");
        assertThat(royal.getContractNo()).isEqualTo("HT-1");
    }

    @Test
    void numericAmountAndLongDetailNoAreParsedAsText() throws IOException {
        MockMultipartFile file = workbookFile(List.of(
                headers(),
                List.of("HT-10", "大额合同", "飞鹤", "飞鹤", "定制", "全域-全渠道-全域云鹿定制",
                        "2026-12-31", "", "85108", "", "", "2026-08", "", "4165688024366007000")
        ));
        service.importContracts(file, 1L);
        RevenueContractEntry row = captured().get(0);
        assertThat(row.getDetailNo()).isEqualTo("4165688024366007000");
        assertThat(row.getReceivableAmount().longValue()).isEqualTo(85108L);
    }

    @Test
    void repeatedImportYieldsSameDetailRowsNotGrowth() throws IOException {
        MockMultipartFile file = workbookFile(List.of(
                headers(),
                List.of("HT-1", "合同", "飞鹤", "飞鹤", "定制", "全域-全渠道-全域云鹿定制",
                        "2026-12-31", "", "85108", "", "", "2026-08", "", "same-detail-1"),
                List.of("HT-2", "合同2", "澳优", "澳优", "短信", "全域-全渠道-会员通",
                        "2026-08-31", "", "1000", "", "", "2026-08", "", "same-detail-2")
        ));
        service.importContracts(file, 1L);
        ArgumentCaptor<List<RevenueContractEntry>> first = ArgumentCaptor.forClass(List.class);
        verify(contractEntryMapper, times(1)).upsertBatch(first.capture());
        assertThat(first.getValue()).hasSize(2);

        service.importContracts(file, 1L);
        ArgumentCaptor<List<RevenueContractEntry>> second = ArgumentCaptor.forClass(List.class);
        verify(contractEntryMapper, times(2)).upsertBatch(second.capture());
        // 同一文件再导：去重键（detail_no）不变 → 落库不新增（DB 唯一键 + ON DUPLICATE KEY UPDATE）
        assertThat(second.getValue()).hasSize(2);
        assertThat(second.getValue().get(0).getDetailNo()).isEqualTo("same-detail-1");
        assertThat(second.getValue().get(1).getDetailNo()).isEqualTo("same-detail-2");
    }

    @Test
    void realTemplateFileParsesWithoutFailure() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        Path file1 = root.resolve("docs/销售报表/本年销售总额明细.xlsx");
        Path file2 = root.resolve("docs/销售报表/本年交付总金额明细 (3).xlsx");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(file1) && Files.exists(file2),
                "模板文件需存在于仓库 docs/销售报表");

        RevenueImportResultVO result = service.importContracts(
                new MockMultipartFile("file", file1.getFileName().toString(),
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        Files.readAllBytes(file1)), 1L);
        assertThat(result.getTotalCount()).isBetween(100, 200);
        List<RevenueContractEntry> rows = captured();
        assertThat(rows).hasSize(result.getTotalCount());
        // 关键列按表头名定位成功：明细表记录ID 均非空
        assertThat(rows).allSatisfy(r -> assertThat(r.getDetailNo()).isNotBlank());
        // 澳优品牌行命中澳优项目、speedo 命中 Speedo 项目
        assertThat(rows).anySatisfy(r -> {
            if ("speedo".equalsIgnoreCase(String.valueOf(r.getBrand()))) {
                assertThat(r.getProjectId()).isEqualTo(10L);
            }
        });
        // 会员通类型（品牌未命中项目）→ 会员通聚合行
        assertThat(rows).anySatisfy(r -> {
            if (r.getBizLineId() != null && r.getBizLineId() == 3L) {
                assertThat(r.getProjectId()).isNull();
            }
        });

        RevenueImportResultVO result2 = service.importContracts(
                new MockMultipartFile("file", file2.getFileName().toString(),
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        Files.readAllBytes(file2)), 1L);
        assertThat(result2.getTotalCount()).isBetween(100, 200);
        verify(contractEntryMapper, times(2)).upsertBatch(any());
    }

    @Test
    void resolvePendingWithProjectReparentsLine() {
        RevenueContractEntry pending = new RevenueContractEntry();
        pending.setId(7L);
        pending.setDetailNo("d6");
        pending.setBizLineId(1L);
        pending.setPending(1);
        when(contractEntryMapper.selectById(7L)).thenReturn(pending);
        when(projectMapper.selectById(24L)).thenReturn(project(24L, 1L, "澳优"));

        service.resolvePending(7L, 24L, null, 1L);

        assertThat(pending.getProjectId()).isEqualTo(24L);
        assertThat(pending.getBizLineId()).isEqualTo(1L);
        assertThat(pending.getPending()).isZero();
        verify(contractEntryMapper).updateById(pending);
    }

    @Test
    void resolvePendingToAggregateLineLevelAllowedButFullLineRejected() {
        RevenueContractEntry pending = new RevenueContractEntry();
        pending.setId(8L);
        pending.setPending(1);
        when(contractEntryMapper.selectById(8L)).thenReturn(pending);
        when(businessLineMapper.selectById(3L)).thenReturn(line(3L, "会员通", "aggregate"));
        service.resolvePending(8L, null, 3L, 1L);
        assertThat(pending.getBizLineId()).isEqualTo(3L);
        assertThat(pending.getProjectId()).isNull();
        assertThat(pending.getPending()).isZero();

        RevenueContractEntry pending2 = new RevenueContractEntry();
        pending2.setId(9L);
        pending2.setPending(1);
        when(contractEntryMapper.selectById(9L)).thenReturn(pending2);
        when(businessLineMapper.selectById(1L)).thenReturn(line(1L, "全渠道云鹿定制", "full"));
        assertThatThrownBy(() -> service.resolvePending(9L, null, 1L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("请选择具体项目");
    }

    @Test
    void resolveRejectsAlreadyMappedOrMissingRows() {
        RevenueContractEntry mapped = new RevenueContractEntry();
        mapped.setId(1L);
        mapped.setPending(0);
        when(contractEntryMapper.selectById(1L)).thenReturn(mapped);
        assertThatThrownBy(() -> service.resolvePending(1L, 24L, null, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已映射");
        when(contractEntryMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.resolvePending(99L, 24L, null, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
        assertThatThrownBy(() -> service.resolvePending(99L, null, null, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void listPendingAndBatchesUseMappers() {
        lenient().when(contractEntryMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.listPending()).isEmpty();
        lenient().when(batchMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.listBatches()).isEmpty();
    }
}
