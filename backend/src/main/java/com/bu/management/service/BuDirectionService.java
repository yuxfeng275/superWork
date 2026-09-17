package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.dto.BuDirectionRequest;
import com.bu.management.entity.BuDirection;
import com.bu.management.entity.BuDirectionMilestone;
import com.bu.management.entity.BuDirectionProject;
import com.bu.management.entity.Project;
import com.bu.management.entity.User;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.mapper.BuDirectionMapper;
import com.bu.management.mapper.BuDirectionMilestoneMapper;
import com.bu.management.mapper.BuDirectionProjectMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BuDirectionService {

    private static final List<String> VALID_STATUSES = List.of("未开始", "进行中", "已完成", "已暂停");
    private static final List<String> VALID_MILESTONE_STATUSES = List.of("未开始", "进行中", "已完成");

    private final BuDirectionMapper directionMapper;
    private final BuDirectionProjectMapper directionProjectMapper;
    private final BuDirectionMilestoneMapper milestoneMapper;
    private final ProjectMapper projectMapper;
    private final UserMapper userMapper;

    public List<BuDirection> list() {
        return directionMapper.selectList(new LambdaQueryWrapper<BuDirection>()
                .orderByAsc(BuDirection::getSortOrder)
                .orderByAsc(BuDirection::getEndDate));
    }

    public BuDirection get(Long id) {
        BuDirection direction = directionMapper.selectById(id);
        if (direction == null) {
            throw new ResourceNotFoundException("BU方向不存在");
        }
        return direction;
    }

    public List<BuDirectionProject> listProjects(Long directionId) {
        return directionProjectMapper.selectList(new LambdaQueryWrapper<BuDirectionProject>()
                .eq(BuDirectionProject::getDirectionId, directionId));
    }

    public List<BuDirectionMilestone> listMilestones(Long directionId) {
        return milestoneMapper.selectList(new LambdaQueryWrapper<BuDirectionMilestone>()
                .eq(BuDirectionMilestone::getDirectionId, directionId)
                .orderByAsc(BuDirectionMilestone::getSortOrder)
                .orderByAsc(BuDirectionMilestone::getDueDate));
    }

    @Transactional
    public BuDirection create(BuDirectionRequest request, Long userId) {
        validate(request);
        BuDirection direction = new BuDirection();
        apply(direction, request);
        direction.setCreatedBy(userId);
        direction.setCreatedAt(LocalDateTime.now());
        direction.setUpdatedAt(LocalDateTime.now());
        directionMapper.insert(direction);
        replaceChildren(direction.getId(), request);
        return direction;
    }

    @Transactional
    public BuDirection update(Long id, BuDirectionRequest request) {
        validate(request);
        BuDirection direction = get(id);
        apply(direction, request);
        direction.setUpdatedAt(LocalDateTime.now());
        directionMapper.updateById(direction);
        replaceChildren(id, request);
        return direction;
    }

    @Transactional
    public void delete(Long id) {
        get(id);
        directionMapper.deleteById(id);
    }

    private void apply(BuDirection direction, BuDirectionRequest request) {
        direction.setCode(request.getCode().trim());
        direction.setName(request.getName().trim());
        direction.setObjective(request.getObjective());
        direction.setOwnerId(request.getOwnerId());
        direction.setStartDate(request.getStartDate());
        direction.setEndDate(request.getEndDate());
        direction.setStatus(StringUtils.hasText(request.getStatus()) ? request.getStatus() : "未开始");
        direction.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
    }

    private void replaceChildren(Long directionId, BuDirectionRequest request) {
        directionProjectMapper.delete(new LambdaQueryWrapper<BuDirectionProject>()
                .eq(BuDirectionProject::getDirectionId, directionId));
        milestoneMapper.delete(new LambdaQueryWrapper<BuDirectionMilestone>()
                .eq(BuDirectionMilestone::getDirectionId, directionId));

        request.getProjectIds().stream().distinct().forEach(projectId -> {
            Project project = projectMapper.selectById(projectId);
            if (project == null) {
                throw new RuntimeException("关联项目不存在: " + projectId);
            }
            BuDirectionProject relation = new BuDirectionProject();
            relation.setDirectionId(directionId);
            relation.setProjectId(projectId);
            relation.setCreatedAt(LocalDateTime.now());
            directionProjectMapper.insert(relation);
        });

        for (BuDirectionRequest.MilestoneInput input : request.getMilestones()) {
            if (!StringUtils.hasText(input.getName()) || input.getDueDate() == null) {
                throw new RuntimeException("里程碑名称和计划日期不能为空");
            }
            String status = StringUtils.hasText(input.getStatus()) ? input.getStatus() : "未开始";
            if (!VALID_MILESTONE_STATUSES.contains(status)) {
                throw new RuntimeException("不支持的里程碑状态");
            }
            BuDirectionMilestone milestone = new BuDirectionMilestone();
            milestone.setDirectionId(directionId);
            milestone.setName(input.getName().trim());
            milestone.setDueDate(input.getDueDate());
            milestone.setStatus(status);
            milestone.setCompletedAt("已完成".equals(status) ? LocalDateTime.now() : null);
            milestone.setSortOrder(input.getSortOrder() == null ? 0 : input.getSortOrder());
            milestone.setCreatedAt(LocalDateTime.now());
            milestone.setUpdatedAt(LocalDateTime.now());
            milestoneMapper.insert(milestone);
        }
    }

    private void validate(BuDirectionRequest request) {
        if (!StringUtils.hasText(request.getCode()) || !StringUtils.hasText(request.getName())) {
            throw new RuntimeException("方向编码和名称不能为空");
        }
        if (request.getStartDate() == null || request.getEndDate() == null
                || request.getEndDate().isBefore(request.getStartDate())) {
            throw new RuntimeException("方向起止日期不合法");
        }
        String status = StringUtils.hasText(request.getStatus()) ? request.getStatus() : "未开始";
        if (!VALID_STATUSES.contains(status)) {
            throw new RuntimeException("不支持的方向状态");
        }
        if (request.getOwnerId() != null) {
            User owner = userMapper.selectById(request.getOwnerId());
            if (owner == null || !Integer.valueOf(1).equals(owner.getStatus())) {
                throw new RuntimeException("方向负责人不存在或已停用");
            }
        }
    }
}
