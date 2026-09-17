package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.dto.CreateWorkLogDTO;
import com.bu.management.dto.UpdateWorkLogDTO;
import com.bu.management.entity.Task;
import com.bu.management.entity.WorkLog;
import com.bu.management.mapper.TaskMapper;
import com.bu.management.mapper.WorkLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 工时记录服务
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Service
@RequiredArgsConstructor
public class WorkLogService {

    private final WorkLogMapper workLogMapper;
    private final TaskMapper taskMapper;

    /**
     * 创建工时记录
     */
    /**
     * Creates a work log with the authenticated actor. The request userId is
     * intentionally ignored; it is a legacy field retained for compatibility.
     */
    @Transactional
    public WorkLog createWorkLog(CreateWorkLogDTO dto, Long actorId) {
        if (actorId == null) {
            throw new RuntimeException("当前登录用户不存在");
        }
        // 验证任务存在
        Task task = taskMapper.selectById(dto.getTaskId());
        if (task == null) {
            throw new RuntimeException("任务不存在");
        }

        // 创建工时记录
        WorkLog workLog = new WorkLog();
        workLog.setTaskId(dto.getTaskId());
        workLog.setUserId(actorId);
        workLog.setWorkDate(dto.getWorkDate() != null ? dto.getWorkDate() : LocalDate.now());
        workLog.setHours(dto.getHours());
        workLog.setWorkContent(dto.getWorkContent());
        workLog.setCreatedAt(LocalDateTime.now());
        workLog.setUpdatedAt(LocalDateTime.now());

        workLogMapper.insert(workLog);

        // 更新任务实际工时
        updateTaskActualHours(dto.getTaskId());

        return workLog;
    }

    /**
     * 更新工时记录
     */
    @Transactional
    public WorkLog updateWorkLog(Long id, UpdateWorkLogDTO dto) {
        WorkLog workLog = workLogMapper.selectById(id);
        if (workLog == null) {
            throw new RuntimeException("工时记录不存在");
        }

        if (dto.getWorkDate() != null) {
            workLog.setWorkDate(dto.getWorkDate());
        }
        if (dto.getHours() != null) {
            workLog.setHours(dto.getHours());
        }
        if (dto.getWorkContent() != null) {
            workLog.setWorkContent(dto.getWorkContent());
        }
        workLog.setUpdatedAt(LocalDateTime.now());

        workLogMapper.updateById(workLog);

        // 更新任务实际工时
        updateTaskActualHours(workLog.getTaskId());

        return workLog;
    }

    /**
     * 查询工时记录详情
     */
    public WorkLog getWorkLogById(Long id) {
        return workLogMapper.selectById(id);
    }

    /**
     * 查询任务的工时记录列表
     */
    public List<WorkLog> getWorkLogsByTaskId(Long taskId) {
        LambdaQueryWrapper<WorkLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkLog::getTaskId, taskId);
        wrapper.orderByDesc(WorkLog::getWorkDate);
        return workLogMapper.selectList(wrapper);
    }

    /**
     * 分页查询工时记录
     */
    public Page<WorkLog> getWorkLogsPage(int page, int size, Long taskId, Long userId, LocalDate startDate, LocalDate endDate) {
        Page<WorkLog> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<WorkLog> wrapper = new LambdaQueryWrapper<>();

        if (taskId != null) {
            wrapper.eq(WorkLog::getTaskId, taskId);
        }
        if (userId != null) {
            wrapper.eq(WorkLog::getUserId, userId);
        }
        if (startDate != null) {
            wrapper.ge(WorkLog::getWorkDate, startDate);
        }
        if (endDate != null) {
            wrapper.le(WorkLog::getWorkDate, endDate);
        }

        wrapper.orderByDesc(WorkLog::getWorkDate);
        return workLogMapper.selectPage(pageParam, wrapper);
    }

    /**
     * 删除工时记录
     */
    @Transactional
    public void deleteWorkLog(Long id) {
        WorkLog workLog = workLogMapper.selectById(id);
        if (workLog != null) {
            workLogMapper.deleteById(id);
            // 更新任务实际工时
            updateTaskActualHours(workLog.getTaskId());
        }
    }

    /**
     * 更新任务实际工时（汇总所有工时记录）
     */
    private void updateTaskActualHours(Long taskId) {
        LambdaQueryWrapper<WorkLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WorkLog::getTaskId, taskId);
        List<WorkLog> workLogs = workLogMapper.selectList(wrapper);

        BigDecimal totalHours = workLogs.stream()
                .map(WorkLog::getHours)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Task task = taskMapper.selectById(taskId);
        if (task != null) {
            task.setActualHours(totalHours);
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.updateById(task);
        }
    }
}
