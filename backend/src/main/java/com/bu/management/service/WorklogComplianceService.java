package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.constant.PositionRoles;
import com.bu.management.dto.YunxiaoExemptionRequest;
import com.bu.management.entity.User;
import com.bu.management.entity.YunxiaoEffortExemption;
import com.bu.management.entity.YunxiaoEffortRecord;
import com.bu.management.entity.YunxiaoProjectMapping;
import com.bu.management.entity.YunxiaoUserMapping;
import com.bu.management.entity.YunxiaoWorkday;
import com.bu.management.entity.YunxiaoWorklogSnapshot;
import com.bu.management.mapper.UserMapper;
import com.bu.management.mapper.YunxiaoEffortExemptionMapper;
import com.bu.management.mapper.YunxiaoEffortRecordMapper;
import com.bu.management.mapper.YunxiaoProjectMappingMapper;
import com.bu.management.mapper.YunxiaoUserMappingMapper;
import com.bu.management.mapper.YunxiaoWorkdayMapper;
import com.bu.management.mapper.YunxiaoWorklogSnapshotMapper;
import com.bu.management.vo.BuDashboardResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorklogComplianceService {

    private static final BigDecimal DEFAULT_DAILY_HOURS = BigDecimal.valueOf(8);
    private static final LocalTime WARNING_TIME = LocalTime.of(18, 30);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final UserMapper userMapper;
    private final YunxiaoUserMappingMapper userMappingMapper;
    private final YunxiaoProjectMappingMapper projectMappingMapper;
    private final YunxiaoEffortRecordMapper effortRecordMapper;
    private final YunxiaoWorkdayMapper workdayMapper;
    private final YunxiaoEffortExemptionMapper exemptionMapper;
    private final YunxiaoWorklogSnapshotMapper snapshotMapper;

    public List<BuDashboardResponse.WorklogItem> audit(LocalDate startDate, LocalDate endDate) {
        return calculate(startDate, endDate, false);
    }

    @Scheduled(cron = "0 30 18 * * *", zone = "Asia/Shanghai")
    @Transactional
    public void snapshotSameDayWarning() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        calculate(today, today, true);
    }

    @Scheduled(cron = "0 0 10 * * *", zone = "Asia/Shanghai")
    @Transactional
    public void snapshotPreviousWorkdayFinal() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        Map<LocalDate, YunxiaoWorkday> calendar = loadCalendar(today.minusDays(31), today);
        if (expectedHours(today, calendar.get(today)).signum() == 0) {
            return;
        }
        LocalDate previousWorkday = previousWorkday(today, calendar);
        calculate(previousWorkday, previousWorkday, true);
    }

    private List<BuDashboardResponse.WorklogItem> calculate(
            LocalDate startDate, LocalDate endDate, boolean persistSnapshot) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        LocalDate effectiveEnd = endDate.isAfter(today) ? today : endDate;
        if (effectiveEnd.isBefore(startDate)) {
            return List.of();
        }
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .eq(User::getStatus, 1)
                .ne(User::getUsername, "admin")
                .orderByAsc(User::getId)).stream()
                .filter(user -> !PositionRoles.BUSINESS_OWNER.equals(user.getRole()))
                .toList();
        Map<Long, YunxiaoUserMapping> mappings = userMappingMapper.selectList(
                        new LambdaQueryWrapper<YunxiaoUserMapping>().eq(YunxiaoUserMapping::getSyncEnabled, 1))
                .stream()
                .collect(Collectors.toMap(YunxiaoUserMapping::getUserId, Function.identity(), (a, b) -> a));
        boolean dataReliable = isDataReliable();
        Map<String, BigDecimal> actualHours = aggregateActualHours(startDate, effectiveEnd);
        Map<LocalDate, YunxiaoWorkday> calendar = loadCalendar(startDate, effectiveEnd);
        Map<String, YunxiaoEffortExemption> exemptions = exemptionMapper.selectList(
                        new LambdaQueryWrapper<YunxiaoEffortExemption>()
                                .between(YunxiaoEffortExemption::getWorkDate, startDate, effectiveEnd))
                .stream()
                .collect(Collectors.toMap(
                        item -> item.getUserId() + "|" + item.getWorkDate(),
                        Function.identity(),
                        (a, b) -> a));

        List<BuDashboardResponse.WorklogItem> result = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(effectiveEnd); date = date.plusDays(1)) {
            BigDecimal expected = expectedHours(date, calendar.get(date));
            if (expected.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            for (User user : users) {
                YunxiaoUserMapping mapping = mappings.get(user.getId());
                BigDecimal actual = mapping == null ? BigDecimal.ZERO
                        : actualHours.getOrDefault(mapping.getYunxiaoUserId() + "|" + date, BigDecimal.ZERO);
                boolean exempt = exemptions.containsKey(user.getId() + "|" + date);
                AuditState state = resolveState(
                        date, expected, actual, mapping != null, exempt, dataReliable);
                if (persistSnapshot) {
                    saveSnapshot(user.getId(), date, expected, actual, state);
                }
                result.add(toView(user, date, expected, actual, state));
            }
        }
        result.sort((a, b) -> {
            int dateCompare = b.getWorkDate().compareTo(a.getWorkDate());
            return dateCompare != 0 ? dateCompare : a.getRealName().compareTo(b.getRealName());
        });
        return result;
    }

    public BigDecimal expectedHours(LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            return BigDecimal.ZERO;
        }
        Map<LocalDate, YunxiaoWorkday> calendar = loadCalendar(startDate, endDate);
        BigDecimal total = BigDecimal.ZERO;
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            total = total.add(expectedHours(date, calendar.get(date)));
        }
        return total;
    }

    public int workdayCount(LocalDate startDate, LocalDate endDate) {
        return expectedHours(startDate, endDate)
                .divide(DEFAULT_DAILY_HOURS, 0, RoundingMode.CEILING)
                .intValue();
    }

    @Transactional
    public YunxiaoEffortExemption saveExemption(YunxiaoExemptionRequest request, Long userId) {
        if (request.getUserId() == null || request.getWorkDate() == null
                || !StringUtils.hasText(request.getReason())) {
            throw new RuntimeException("人员、日期和豁免原因不能为空");
        }
        YunxiaoEffortExemption exemption = exemptionMapper.selectOne(
                new LambdaQueryWrapper<YunxiaoEffortExemption>()
                        .eq(YunxiaoEffortExemption::getUserId, request.getUserId())
                        .eq(YunxiaoEffortExemption::getWorkDate, request.getWorkDate()));
        if (exemption == null) {
            exemption = new YunxiaoEffortExemption();
            exemption.setUserId(request.getUserId());
            exemption.setWorkDate(request.getWorkDate());
            exemption.setCreatedBy(userId);
            exemption.setCreatedAt(LocalDateTime.now());
        }
        exemption.setReason(request.getReason().trim());
        if (exemption.getId() == null) {
            exemptionMapper.insert(exemption);
        } else {
            exemptionMapper.updateById(exemption);
        }
        return exemption;
    }

    private Map<String, BigDecimal> aggregateActualHours(LocalDate startDate, LocalDate endDate) {
        List<YunxiaoEffortRecord> records = effortRecordMapper.selectList(
                new LambdaQueryWrapper<YunxiaoEffortRecord>()
                        .between(YunxiaoEffortRecord::getWorkDate, startDate, endDate));
        Map<String, BigDecimal> result = new HashMap<>();
        records.forEach(record -> result.merge(
                record.getYunxiaoUserId() + "|" + record.getWorkDate(),
                record.getActualHours() == null ? BigDecimal.ZERO : record.getActualHours(),
                BigDecimal::add));
        return result;
    }

    private BigDecimal expectedHours(LocalDate date, YunxiaoWorkday override) {
        if (override != null) {
            return Integer.valueOf(1).equals(override.getIsWorkday())
                    ? (override.getExpectedHours() == null ? DEFAULT_DAILY_HOURS : override.getExpectedHours())
                    : BigDecimal.ZERO;
        }
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
                ? BigDecimal.ZERO : DEFAULT_DAILY_HOURS;
    }

    LocalDate previousWorkday(LocalDate date, Map<LocalDate, YunxiaoWorkday> calendar) {
        LocalDate candidate = date.minusDays(1);
        while (expectedHours(candidate, calendar.get(candidate)).signum() == 0) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }

    private Map<LocalDate, YunxiaoWorkday> loadCalendar(LocalDate startDate, LocalDate endDate) {
        return workdayMapper.selectList(new LambdaQueryWrapper<YunxiaoWorkday>()
                        .between(YunxiaoWorkday::getWorkDate, startDate, endDate))
                .stream()
                .collect(Collectors.toMap(YunxiaoWorkday::getWorkDate, Function.identity(), (a, b) -> a));
    }

    private AuditState resolveState(LocalDate date, BigDecimal expected, BigDecimal actual,
                                    boolean mapped, boolean exempt, boolean dataReliable) {
        if (exempt) {
            return new AuditState("已豁免", date.isBefore(LocalDate.now(BUSINESS_ZONE)));
        }
        if (!mapped) {
            return new AuditState("未映射", date.isBefore(LocalDate.now(BUSINESS_ZONE)));
        }
        if (!dataReliable) {
            return new AuditState("数据未知", date.isBefore(LocalDate.now(BUSINESS_ZONE)));
        }
        if (actual.compareTo(expected) >= 0) {
            return new AuditState("已填写", date.isBefore(LocalDate.now(BUSINESS_ZONE)));
        }
        if (date.isEqual(LocalDate.now(BUSINESS_ZONE))) {
            return LocalTime.now(BUSINESS_ZONE).isBefore(WARNING_TIME)
                    ? new AuditState("待填写", false)
                    : new AuditState(actual.signum() == 0 ? "预警未填" : "预警不足", false);
        }
        return new AuditState(actual.signum() == 0 ? "未填写" : "填写不足", true);
    }

    private boolean isDataReliable() {
        List<YunxiaoProjectMapping> mappings = projectMappingMapper.selectList(
                new LambdaQueryWrapper<YunxiaoProjectMapping>()
                        .eq(YunxiaoProjectMapping::getSyncEnabled, 1));
        return !mappings.isEmpty() && mappings.stream()
                .allMatch(item -> "SUCCESS".equals(item.getLastSyncStatus())
                        && item.getLastSyncedAt() != null);
    }

    private void saveSnapshot(Long userId, LocalDate date, BigDecimal expected,
                              BigDecimal actual, AuditState state) {
        YunxiaoWorklogSnapshot snapshot = snapshotMapper.selectOne(
                new LambdaQueryWrapper<YunxiaoWorklogSnapshot>()
                        .eq(YunxiaoWorklogSnapshot::getUserId, userId)
                        .eq(YunxiaoWorklogSnapshot::getWorkDate, date));
        if (snapshot == null) {
            snapshot = new YunxiaoWorklogSnapshot();
            snapshot.setUserId(userId);
            snapshot.setWorkDate(date);
        }
        snapshot.setExpectedHours(expected);
        snapshot.setActualHours(actual);
        snapshot.setStatus(state.status());
        snapshot.setIsFinal(state.finalResult() ? 1 : 0);
        snapshot.setSource("YUNXIAO");
        snapshot.setComputedAt(LocalDateTime.now());
        if (snapshot.getId() == null) {
            snapshotMapper.insert(snapshot);
        } else {
            snapshotMapper.updateById(snapshot);
        }
    }

    private BuDashboardResponse.WorklogItem toView(User user, LocalDate date, BigDecimal expected,
                                                    BigDecimal actual, AuditState state) {
        BuDashboardResponse.WorklogItem item = new BuDashboardResponse.WorklogItem();
        item.setUserId(user.getId());
        item.setRealName(user.getRealName());
        item.setRole(user.getRole());
        item.setWorkDate(date);
        item.setExpectedHours(expected);
        item.setActualHours(actual);
        item.setStatus(state.status());
        item.setFinalResult(state.finalResult());
        return item;
    }

    private record AuditState(String status, boolean finalResult) {
    }
}
