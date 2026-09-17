package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.bu.management.entity.User;
import com.bu.management.entity.YunxiaoEffortRecord;
import com.bu.management.entity.YunxiaoProjectMapping;
import com.bu.management.entity.YunxiaoUserMapping;
import com.bu.management.entity.YunxiaoWorkday;
import com.bu.management.mapper.UserMapper;
import com.bu.management.mapper.YunxiaoEffortExemptionMapper;
import com.bu.management.mapper.YunxiaoEffortRecordMapper;
import com.bu.management.mapper.YunxiaoProjectMappingMapper;
import com.bu.management.mapper.YunxiaoUserMappingMapper;
import com.bu.management.mapper.YunxiaoWorkdayMapper;
import com.bu.management.mapper.YunxiaoWorklogSnapshotMapper;
import com.bu.management.vo.BuDashboardResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorklogComplianceServiceTest {

    @Mock private UserMapper userMapper;
    @Mock private YunxiaoUserMappingMapper userMappingMapper;
    @Mock private YunxiaoProjectMappingMapper projectMappingMapper;
    @Mock private YunxiaoEffortRecordMapper effortRecordMapper;
    @Mock private YunxiaoWorkdayMapper workdayMapper;
    @Mock private YunxiaoEffortExemptionMapper exemptionMapper;
    @Mock private YunxiaoWorklogSnapshotMapper snapshotMapper;

    @Test
    void auditDistinguishesInsufficientHoursFromMissingUserMapping() {
        LocalDate workDate = LocalDate.of(2026, 7, 29);
        User mappedUser = user(7L, "石家乐");
        User unmappedUser = user(8L, "刘双升");
        YunxiaoUserMapping mapping = new YunxiaoUserMapping();
        mapping.setUserId(7L);
        mapping.setYunxiaoUserId("cloud-user-7");
        mapping.setSyncEnabled(1);
        YunxiaoEffortRecord record = new YunxiaoEffortRecord();
        record.setYunxiaoUserId("cloud-user-7");
        record.setWorkDate(workDate);
        record.setActualHours(BigDecimal.valueOf(4));
        YunxiaoProjectMapping projectMapping = successfulProjectMapping();

        when(userMapper.selectList(any(Wrapper.class))).thenReturn(List.of(mappedUser, unmappedUser));
        when(userMappingMapper.selectList(any(Wrapper.class))).thenReturn(List.of(mapping));
        when(projectMappingMapper.selectList(any(Wrapper.class))).thenReturn(List.of(projectMapping));
        when(effortRecordMapper.selectList(any(Wrapper.class))).thenReturn(List.of(record));
        when(workdayMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(exemptionMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        WorklogComplianceService service = new WorklogComplianceService(
                userMapper, userMappingMapper, projectMappingMapper, effortRecordMapper, workdayMapper,
                exemptionMapper, snapshotMapper);
        List<BuDashboardResponse.WorklogItem> result = service.audit(workDate, workDate);

        assertThat(result).hasSize(2);
        assertThat(result).filteredOn(item -> item.getUserId().equals(7L))
                .extracting(BuDashboardResponse.WorklogItem::getStatus)
                .containsExactly("填写不足");
        assertThat(result).filteredOn(item -> item.getUserId().equals(8L))
                .extracting(BuDashboardResponse.WorklogItem::getStatus)
                .containsExactly("未映射");
    }

    @Test
    void previousWorkdayRespectsHolidayOverrides() {
        WorklogComplianceService service = new WorklogComplianceService(
                userMapper, userMappingMapper, projectMappingMapper, effortRecordMapper, workdayMapper,
                exemptionMapper, snapshotMapper);
        LocalDate monday = LocalDate.of(2026, 8, 3);
        YunxiaoWorkday fridayHoliday = new YunxiaoWorkday();
        fridayHoliday.setWorkDate(LocalDate.of(2026, 7, 31));
        fridayHoliday.setIsWorkday(0);

        LocalDate result = service.previousWorkday(
                monday,
                Map.of(fridayHoliday.getWorkDate(), fridayHoliday));

        assertThat(result).isEqualTo(LocalDate.of(2026, 7, 30));
    }

    @Test
    void auditUsesUnknownWhenProjectSyncIsNotReliable() {
        LocalDate workDate = LocalDate.of(2026, 7, 29);
        User mappedUser = user(7L, "石家乐");
        YunxiaoUserMapping mapping = new YunxiaoUserMapping();
        mapping.setUserId(7L);
        mapping.setYunxiaoUserId("cloud-user-7");
        mapping.setSyncEnabled(1);
        YunxiaoProjectMapping failedProject = successfulProjectMapping();
        failedProject.setLastSyncStatus("FAILED");

        when(userMapper.selectList(any(Wrapper.class))).thenReturn(List.of(mappedUser));
        when(userMappingMapper.selectList(any(Wrapper.class))).thenReturn(List.of(mapping));
        when(projectMappingMapper.selectList(any(Wrapper.class))).thenReturn(List.of(failedProject));
        when(effortRecordMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(workdayMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(exemptionMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        WorklogComplianceService service = new WorklogComplianceService(
                userMapper, userMappingMapper, projectMappingMapper, effortRecordMapper, workdayMapper,
                exemptionMapper, snapshotMapper);

        List<BuDashboardResponse.WorklogItem> result = service.audit(workDate, workDate);

        assertThat(result).singleElement()
                .extracting(BuDashboardResponse.WorklogItem::getStatus)
                .isEqualTo("数据未知");
    }

    @Test
    void auditExcludesBusinessOwnersFromWorklogChecks() {
        LocalDate workDate = LocalDate.of(2026, 8, 4);
        User engineer = user(7L, "石家乐");
        User firstOwner = user(16L, "于峰");
        firstOwner.setRole("BUSINESS_OWNER");
        User secondOwner = user(17L, "张群成");
        secondOwner.setRole("BUSINESS_OWNER");

        when(userMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(engineer, firstOwner, secondOwner));
        when(userMappingMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(projectMappingMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(successfulProjectMapping()));
        when(effortRecordMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(workdayMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(exemptionMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        WorklogComplianceService service = new WorklogComplianceService(
                userMapper, userMappingMapper, projectMappingMapper, effortRecordMapper, workdayMapper,
                exemptionMapper, snapshotMapper);

        List<BuDashboardResponse.WorklogItem> result = service.audit(workDate, workDate);

        assertThat(result)
                .extracting(BuDashboardResponse.WorklogItem::getRealName)
                .containsExactly("石家乐");
    }

    private YunxiaoProjectMapping successfulProjectMapping() {
        YunxiaoProjectMapping mapping = new YunxiaoProjectMapping();
        mapping.setSyncEnabled(1);
        mapping.setLastSyncStatus("SUCCESS");
        mapping.setLastSyncedAt(LocalDateTime.now());
        return mapping;
    }

    private User user(Long id, String name) {
        User user = new User();
        user.setId(id);
        user.setRealName(name);
        user.setRole("FULL_STACK_ENGINEER");
        user.setStatus(1);
        return user;
    }
}
