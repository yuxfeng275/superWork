package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.dto.CreateTaskDTO;
import com.bu.management.dto.UpdateTaskDTO;
import com.bu.management.entity.Requirement;
import com.bu.management.entity.Task;
import com.bu.management.mapper.RequirementMapper;
import com.bu.management.mapper.TaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务服务
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskMapper taskMapper;
    private final RequirementMapper requirementMapper;

    /**
     * 创建任务
     */
    @Transactional
    public Task createTask(CreateTaskDTO dto) {
        // 验证需求存在
        Requirement requirement = requirementMapper.selectById(dto.getRequirementId());
        if (requirement == null) {
            throw new RuntimeException("需求不存在");
        }

        // 创建任务
        Task task = new Task();
        task.setRequirementId(dto.getRequirementId());
        task.setTitle(dto.getTitle());
        task.setDescription(dto.getDescription());
        task.setAssigneeId(dto.getAssigneeId());
        task.setEstimatedHours(dto.getEstimatedHours());
        task.setPriority(dto.getPriority() != null ? dto.getPriority() : "中");
        task.setStatus("待开始");
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());

        taskMapper.insert(task);
        return task;
    }

    /**
     * 更新任务
     */
    @Transactional
    public Task updateTask(Long id, UpdateTaskDTO dto) {
        Task task = taskMapper.selectById(id);
        if (task == null) {
            throw new RuntimeException("任务不存在");
        }

        if (dto.getTitle() != null) {
            task.setTitle(dto.getTitle());
        }
        if (dto.getDescription() != null) {
            task.setDescription(dto.getDescription());
        }
        if (dto.getAssigneeId() != null) {
            task.setAssigneeId(dto.getAssigneeId());
        }
        if (dto.getEstimatedHours() != null) {
            task.setEstimatedHours(dto.getEstimatedHours());
        }
        if (dto.getActualHours() != null) {
            task.setActualHours(dto.getActualHours());
        }
        if (dto.getStatus() != null) {
            task.setStatus(dto.getStatus());
            if ("进行中".equals(dto.getStatus()) && task.getStartedAt() == null) {
                task.setStartedAt(LocalDateTime.now());
            }
            if ("已完成".equals(dto.getStatus())) {
                task.setCompletedAt(LocalDateTime.now());
            }
        }
        if (dto.getPriority() != null) {
            task.setPriority(dto.getPriority());
        }
        task.setUpdatedAt(LocalDateTime.now());

        taskMapper.updateById(task);
        return task;
    }

    /**
     * 查询任务详情
     */
    public Task getTaskById(Long id) {
        return taskMapper.selectById(id);
    }

    /**
     * 查询需求的任务列表
     */
    public List<Task> getTasksByRequirementId(Long requirementId) {
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Task::getRequirementId, requirementId);
        wrapper.orderByDesc(Task::getCreatedAt);
        return taskMapper.selectList(wrapper);
    }

    /**
     * 分页查询任务
     */
    public Page<Task> getTasksPage(int page, int size, Long requirementId, Long assigneeId, String status, String priority) {
        Page<Task> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();

        if (requirementId != null) {
            wrapper.eq(Task::getRequirementId, requirementId);
        }
        if (assigneeId != null) {
            wrapper.eq(Task::getAssigneeId, assigneeId);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(Task::getStatus, status);
        }
        if (priority != null && !priority.isEmpty()) {
            wrapper.eq(Task::getPriority, priority);
        }

        wrapper.orderByDesc(Task::getCreatedAt);
        return taskMapper.selectPage(pageParam, wrapper);
    }

    /**
     * 删除任务
     */
    @Transactional
    public void deleteTask(Long id) {
        taskMapper.deleteById(id);
    }
}
