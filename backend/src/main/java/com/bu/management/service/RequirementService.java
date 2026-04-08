package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.dto.RequirementRequest;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.CustomerContact;
import com.bu.management.entity.Project;
import com.bu.management.entity.Requirement;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.CustomerContactMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RequirementMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 需求服务
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Service
@RequiredArgsConstructor
public class RequirementService {

    private final RequirementMapper requirementMapper;
    private final BusinessLineMapper businessLineMapper;
    private final ProjectMapper projectMapper;
    private final CustomerContactMapper customerContactMapper;
    private final DataPermissionService dataPermissionService;

    /**
     * 创建需求
     */
    @Transactional(rollbackFor = Exception.class)
    public Requirement create(RequirementRequest request, Long creatorId) {
        // 验证业务线是否存在
        BusinessLine businessLine = businessLineMapper.selectById(request.getBusinessLineId());
        if (businessLine == null) {
            throw new RuntimeException("业务线不存在");
        }

        // 验证项目（如果提供）
        if (request.getProjectId() != null) {
            Project project = projectMapper.selectById(request.getProjectId());
            if (project == null) {
                throw new RuntimeException("项目不存在");
            }
        }

        // 项目需求必须提供客户联系人
        if ("项目需求".equals(request.getType())) {
            if (request.getCustomerContactId() == null) {
                throw new RuntimeException("项目需求必须提供客户联系人");
            }
            CustomerContact contact = customerContactMapper.selectById(request.getCustomerContactId());
            if (contact == null) {
                throw new RuntimeException("客户联系人不存在");
            }
        }

        Requirement requirement = new Requirement();
        requirement.setReqNo(generateReqNo());
        requirement.setTitle(request.getTitle());
        requirement.setDescription(request.getDescription());
        requirement.setType(request.getType());
        requirement.setBusinessLineId(request.getBusinessLineId());
        requirement.setProjectId(request.getProjectId());
        requirement.setCustomerContactId(request.getCustomerContactId());
        requirement.setStatus("待评估");
        requirement.setPriority(request.getPriority() != null ? request.getPriority() : "中");
        requirement.setSource(request.getSource());
        requirement.setExpectedOnlineDate(request.getExpectedOnlineDate());
        requirement.setCreatorId(creatorId);

        requirementMapper.insert(requirement);
        return requirement;
    }

    /**
     * 更新需求
     */
    @Transactional(rollbackFor = Exception.class)
    public Requirement update(Long id, RequirementRequest request) {
        Requirement requirement = requirementMapper.selectById(id);
        if (requirement == null) {
            throw new ResourceNotFoundException("需求不存在");
        }

        // 验证业务线是否存在
        BusinessLine businessLine = businessLineMapper.selectById(request.getBusinessLineId());
        if (businessLine == null) {
            throw new RuntimeException("业务线不存在");
        }

        // 验证项目（如果提供）
        if (request.getProjectId() != null) {
            Project project = projectMapper.selectById(request.getProjectId());
            if (project == null) {
                throw new RuntimeException("项目不存在");
            }
        }

        // 项目需求必须提供客户联系人
        if ("项目需求".equals(request.getType())) {
            if (request.getCustomerContactId() == null) {
                throw new RuntimeException("项目需求必须提供客户联系人");
            }
            CustomerContact contact = customerContactMapper.selectById(request.getCustomerContactId());
            if (contact == null) {
                throw new RuntimeException("客户联系人不存在");
            }
        }

        requirement.setTitle(request.getTitle());
        requirement.setDescription(request.getDescription());
        requirement.setType(request.getType());
        requirement.setBusinessLineId(request.getBusinessLineId());
        requirement.setProjectId(request.getProjectId());
        requirement.setCustomerContactId(request.getCustomerContactId());
        requirement.setPriority(request.getPriority());
        requirement.setSource(request.getSource());
        requirement.setExpectedOnlineDate(request.getExpectedOnlineDate());

        requirementMapper.updateById(requirement);
        return requirement;
    }

    /**
     * 删除需求
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Requirement requirement = requirementMapper.selectById(id);
        if (requirement == null) {
            throw new ResourceNotFoundException("需求不存在");
        }

        // 只有待评估状态的需求才能删除
        if (!"待评估".equals(requirement.getStatus())) {
            throw new RuntimeException("只有待评估状态的需求才能删除");
        }

        requirementMapper.deleteById(id);
    }

    /**
     * 获取需求详情
     */
    public Requirement getById(Long id) {
        Requirement requirement = requirementMapper.selectById(id);
        if (requirement == null) {
            throw new ResourceNotFoundException("需求不存在");
        }
        return requirement;
    }

    /**
     * 分页查询需求列表（无权限过滤 - 管理员使用）
     */
    public Page<Requirement> list(Integer page, Integer size, Long businessLineId, Long projectId,
                                   String type, String status, String priority, String title) {
        Page<Requirement> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<Requirement> wrapper = buildQueryWrapper(businessLineId, projectId, type, status, priority, title);
        wrapper.orderByDesc(Requirement::getCreatedAt);
        return requirementMapper.selectPage(pageParam, wrapper);
    }

    /**
     * 分页查询需求列表（带数据权限过滤）
     */
    public Page<Requirement> listWithPermission(Long userId, String role, Integer page, Integer size,
                                                Long businessLineId, Long projectId,
                                                String type, String status, String priority, String title) {
        Page<Requirement> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<Requirement> wrapper = buildQueryWrapper(businessLineId, projectId, type, status, priority, title);

        // 数据权限过滤：项目经理和技术经理只能看到其参与项目的需求
        if (!dataPermissionService.isBuAdmin(role)) {
            if (dataPermissionService.isProjectRole(role)) {
                List<Long> userProjectIds = dataPermissionService.getUserProjectIds(userId);
                if (userProjectIds.isEmpty()) {
                    // 用户没有任何项目成员资格，返回空结果
                    wrapper.eq(Requirement::getId, -1L);
                } else {
                    wrapper.in(Requirement::getProjectId, userProjectIds);
                }
            }
            // 其他角色（产品、开发、测试等）暂时不做项目级别过滤，可以查看所有
        }

        wrapper.orderByDesc(Requirement::getCreatedAt);
        return requirementMapper.selectPage(pageParam, wrapper);
    }

    /**
     * 构建查询条件
     */
    private LambdaQueryWrapper<Requirement> buildQueryWrapper(Long businessLineId, Long projectId,
                                                               String type, String status, String priority, String title) {
        LambdaQueryWrapper<Requirement> wrapper = new LambdaQueryWrapper<>();

        // 业务线筛选
        if (businessLineId != null) {
            wrapper.eq(Requirement::getBusinessLineId, businessLineId);
        }

        // 项目筛选
        if (projectId != null) {
            wrapper.eq(Requirement::getProjectId, projectId);
        }

        // 类型筛选
        if (StringUtils.hasText(type)) {
            wrapper.eq(Requirement::getType, type);
        }

        // 状态筛选
        if (StringUtils.hasText(status)) {
            wrapper.eq(Requirement::getStatus, status);
        }

        // 优先级筛选
        if (StringUtils.hasText(priority)) {
            wrapper.eq(Requirement::getPriority, priority);
        }

        // 标题模糊查询
        if (StringUtils.hasText(title)) {
            wrapper.like(Requirement::getTitle, title);
        }

        return wrapper;
    }

    /**
     * 生成需求编号
     */
    private String generateReqNo() {
        String prefix = "REQ";
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));

        // 查询当天已有的需求数量
        LambdaQueryWrapper<Requirement> wrapper = new LambdaQueryWrapper<>();
        wrapper.likeRight(Requirement::getReqNo, prefix + timestamp.substring(0, 8));
        long count = requirementMapper.selectCount(wrapper);

        String sequence = String.format("%04d", count + 1);
        return prefix + timestamp + sequence;
    }
}
