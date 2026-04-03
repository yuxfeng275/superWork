package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.dto.CreateRequirementDesignDTO;
import com.bu.management.dto.UpdateRequirementDesignDTO;
import com.bu.management.entity.Requirement;
import com.bu.management.entity.RequirementDesign;
import com.bu.management.mapper.RequirementDesignMapper;
import com.bu.management.mapper.RequirementMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 需求设计服务
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Service
@RequiredArgsConstructor
public class RequirementDesignService {

    private final RequirementDesignMapper designMapper;
    private final RequirementMapper requirementMapper;

    /**
     * 创建需求设计
     */
    @Transactional
    public RequirementDesign createDesign(CreateRequirementDesignDTO dto) {
        // 验证需求存在
        Requirement requirement = requirementMapper.selectById(dto.getRequirementId());
        if (requirement == null) {
            throw new RuntimeException("需求不存在");
        }

        // 验证需求状态
        if (!"待设计".equals(requirement.getStatus())) {
            throw new RuntimeException("需求状态必须为待设计");
        }

        // 检查是否已存在设计记录
        LambdaQueryWrapper<RequirementDesign> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RequirementDesign::getRequirementId, dto.getRequirementId());
        RequirementDesign existing = designMapper.selectOne(wrapper);
        if (existing != null) {
            throw new RuntimeException("该需求已存在设计记录");
        }

        // 创建设计记录
        RequirementDesign design = new RequirementDesign();
        design.setRequirementId(dto.getRequirementId());
        design.setPrototypeStatus(dto.getPrototypeStatus() != null ? dto.getPrototypeStatus() : "未开始");
        design.setUiStatus(dto.getUiStatus() != null ? dto.getUiStatus() : "未开始");
        design.setTechSolutionStatus(dto.getTechSolutionStatus() != null ? dto.getTechSolutionStatus() : "未开始");
        design.setCreatedAt(LocalDateTime.now());
        design.setUpdatedAt(LocalDateTime.now());

        designMapper.insert(design);

        // 更新需求状态为设计中
        requirement.setStatus("设计中");
        requirement.setUpdatedAt(LocalDateTime.now());
        requirementMapper.updateById(requirement);

        return design;
    }

    /**
     * 更新需求设计
     */
    @Transactional
    public RequirementDesign updateDesign(Long requirementId, UpdateRequirementDesignDTO dto) {
        // 查询设计记录
        LambdaQueryWrapper<RequirementDesign> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RequirementDesign::getRequirementId, requirementId);
        RequirementDesign design = designMapper.selectOne(wrapper);
        if (design == null) {
            throw new RuntimeException("设计记录不存在");
        }

        // 更新设计状态
        if (dto.getPrototypeStatus() != null) {
            design.setPrototypeStatus(dto.getPrototypeStatus());
        }
        if (dto.getUiStatus() != null) {
            design.setUiStatus(dto.getUiStatus());
        }
        if (dto.getTechSolutionStatus() != null) {
            design.setTechSolutionStatus(dto.getTechSolutionStatus());
        }
        design.setUpdatedAt(LocalDateTime.now());

        // 检查是否全部完成
        if ("已完成".equals(design.getPrototypeStatus()) &&
            "已完成".equals(design.getUiStatus()) &&
            "已完成".equals(design.getTechSolutionStatus())) {
            design.setAllCompletedAt(LocalDateTime.now());

            // 更新需求状态为待确认
            Requirement requirement = requirementMapper.selectById(requirementId);
            requirement.setStatus("待确认");
            requirement.setUpdatedAt(LocalDateTime.now());
            requirementMapper.updateById(requirement);
        }

        designMapper.updateById(design);
        return design;
    }

    /**
     * 查询需求设计
     */
    public RequirementDesign getDesignByRequirementId(Long requirementId) {
        LambdaQueryWrapper<RequirementDesign> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RequirementDesign::getRequirementId, requirementId);
        return designMapper.selectOne(wrapper);
    }
}
