package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.dto.AcceptRequirementDTO;
import com.bu.management.dto.CreateRequirementDeliveryDTO;
import com.bu.management.entity.Requirement;
import com.bu.management.entity.RequirementDelivery;
import com.bu.management.mapper.RequirementDeliveryMapper;
import com.bu.management.mapper.RequirementMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 需求交付服务
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Service
@RequiredArgsConstructor
public class RequirementDeliveryService {

    private final RequirementDeliveryMapper deliveryMapper;
    private final RequirementMapper requirementMapper;

    /**
     * 创建需求交付
     */
    @Transactional
    public RequirementDelivery createDelivery(CreateRequirementDeliveryDTO dto) {
        // 验证需求存在
        Requirement requirement = requirementMapper.selectById(dto.getRequirementId());
        if (requirement == null) {
            throw new RuntimeException("需求不存在");
        }

        // 验证需求状态
        if (!"已上线".equals(requirement.getStatus())) {
            throw new RuntimeException("需求状态必须为已上线");
        }

        // 检查是否已存在交付记录
        LambdaQueryWrapper<RequirementDelivery> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RequirementDelivery::getRequirementId, dto.getRequirementId());
        RequirementDelivery existing = deliveryMapper.selectOne(wrapper);
        if (existing != null) {
            throw new RuntimeException("该需求已存在交付记录");
        }

        // 创建交付记录
        RequirementDelivery delivery = new RequirementDelivery();
        delivery.setRequirementId(dto.getRequirementId());
        delivery.setDeliveredAt(LocalDateTime.now());
        delivery.setDeliveredBy(dto.getDeliveredBy());
        delivery.setDeliveryNotes(dto.getDeliveryNotes());
        delivery.setCreatedAt(LocalDateTime.now());
        delivery.setUpdatedAt(LocalDateTime.now());

        deliveryMapper.insert(delivery);

        // 更新需求状态为已交付
        requirement.setStatus("已交付");
        requirement.setUpdatedAt(LocalDateTime.now());
        requirementMapper.updateById(requirement);

        return delivery;
    }

    /**
     * 验收需求
     */
    @Transactional
    public RequirementDelivery acceptRequirement(Long requirementId, AcceptRequirementDTO dto) {
        // 查询交付记录
        LambdaQueryWrapper<RequirementDelivery> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RequirementDelivery::getRequirementId, requirementId);
        RequirementDelivery delivery = deliveryMapper.selectOne(wrapper);
        if (delivery == null) {
            throw new RuntimeException("交付记录不存在");
        }

        // 验证需求状态
        Requirement requirement = requirementMapper.selectById(requirementId);
        if (!"已交付".equals(requirement.getStatus())) {
            throw new RuntimeException("需求状态必须为已交付");
        }

        // 更新验收信息
        delivery.setAcceptedBy(dto.getAcceptedBy());
        delivery.setAcceptedAt(LocalDateTime.now());
        delivery.setAcceptanceNotes(dto.getAcceptanceNotes());
        delivery.setUpdatedAt(LocalDateTime.now());

        deliveryMapper.updateById(delivery);

        // 更新需求状态为已验收
        requirement.setStatus("已验收");
        requirement.setUpdatedAt(LocalDateTime.now());
        requirementMapper.updateById(requirement);

        return delivery;
    }

    /**
     * 查询需求交付
     */
    public RequirementDelivery getDeliveryByRequirementId(Long requirementId) {
        LambdaQueryWrapper<RequirementDelivery> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RequirementDelivery::getRequirementId, requirementId);
        return deliveryMapper.selectOne(wrapper);
    }
}
