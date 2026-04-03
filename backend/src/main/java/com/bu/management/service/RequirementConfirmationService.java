package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.dto.CreateRequirementConfirmationDTO;
import com.bu.management.entity.Requirement;
import com.bu.management.entity.RequirementConfirmation;
import com.bu.management.mapper.RequirementConfirmationMapper;
import com.bu.management.mapper.RequirementMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 需求确认服务
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Service
@RequiredArgsConstructor
public class RequirementConfirmationService {

    private final RequirementConfirmationMapper confirmationMapper;
    private final RequirementMapper requirementMapper;

    /**
     * 创建需求确认
     */
    @Transactional
    public RequirementConfirmation createConfirmation(CreateRequirementConfirmationDTO dto) {
        // 验证需求存在
        Requirement requirement = requirementMapper.selectById(dto.getRequirementId());
        if (requirement == null) {
            throw new RuntimeException("需求不存在");
        }

        // 验证需求状态
        if (!"待确认".equals(requirement.getStatus())) {
            throw new RuntimeException("需求状态必须为待确认");
        }

        // 检查是否已存在确认记录
        LambdaQueryWrapper<RequirementConfirmation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RequirementConfirmation::getRequirementId, dto.getRequirementId());
        RequirementConfirmation existing = confirmationMapper.selectOne(wrapper);
        if (existing != null) {
            throw new RuntimeException("该需求已存在确认记录");
        }

        // 创建确认记录
        RequirementConfirmation confirmation = new RequirementConfirmation();
        confirmation.setRequirementId(dto.getRequirementId());
        confirmation.setConfirmationType(dto.getConfirmationType());
        confirmation.setConfirmedBy(dto.getConfirmedBy());
        confirmation.setConfirmedAt(LocalDateTime.now());
        confirmation.setConfirmationNotes(dto.getConfirmationNotes());
        confirmation.setCreatedAt(LocalDateTime.now());
        confirmation.setUpdatedAt(LocalDateTime.now());

        confirmationMapper.insert(confirmation);

        // 更新需求状态为开发中
        requirement.setStatus("开发中");
        requirement.setUpdatedAt(LocalDateTime.now());
        requirementMapper.updateById(requirement);

        return confirmation;
    }

    /**
     * 查询需求确认
     */
    public RequirementConfirmation getConfirmationByRequirementId(Long requirementId) {
        LambdaQueryWrapper<RequirementConfirmation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RequirementConfirmation::getRequirementId, requirementId);
        return confirmationMapper.selectOne(wrapper);
    }
}
