package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.dto.BusinessLineRequest;
import com.bu.management.entity.BusinessLine;
import com.bu.management.mapper.BusinessLineMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 业务线服务
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Service
@RequiredArgsConstructor
public class BusinessLineService {

    private final BusinessLineMapper businessLineMapper;

    /**
     * 创建业务线
     */
    @Transactional(rollbackFor = Exception.class)
    public BusinessLine create(BusinessLineRequest request) {
        // 检查名称是否已存在
        LambdaQueryWrapper<BusinessLine> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BusinessLine::getName, request.getName());
        if (businessLineMapper.selectCount(wrapper) > 0) {
            throw new RuntimeException("业务线名称已存在");
        }

        BusinessLine businessLine = new BusinessLine();
        businessLine.setName(request.getName());
        businessLine.setDescription(request.getDescription());
        businessLine.setStatus(request.getStatus() != null ? request.getStatus() : 1);

        businessLineMapper.insert(businessLine);
        return businessLine;
    }

    /**
     * 更新业务线
     */
    @Transactional(rollbackFor = Exception.class)
    public BusinessLine update(Long id, BusinessLineRequest request) {
        BusinessLine businessLine = businessLineMapper.selectById(id);
        if (businessLine == null) {
            throw new RuntimeException("业务线不存在");
        }

        // 检查名称是否已被其他业务线使用
        if (!businessLine.getName().equals(request.getName())) {
            LambdaQueryWrapper<BusinessLine> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(BusinessLine::getName, request.getName());
            if (businessLineMapper.selectCount(wrapper) > 0) {
                throw new RuntimeException("业务线名称已存在");
            }
        }

        businessLine.setName(request.getName());
        businessLine.setDescription(request.getDescription());
        if (request.getStatus() != null) {
            businessLine.setStatus(request.getStatus());
        }

        businessLineMapper.updateById(businessLine);
        return businessLine;
    }

    /**
     * 删除业务线
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        BusinessLine businessLine = businessLineMapper.selectById(id);
        if (businessLine == null) {
            throw new RuntimeException("业务线不存在");
        }

        businessLineMapper.deleteById(id);
    }

    /**
     * 获取业务线详情
     */
    public BusinessLine getById(Long id) {
        BusinessLine businessLine = businessLineMapper.selectById(id);
        if (businessLine == null) {
            throw new RuntimeException("业务线不存在");
        }
        return businessLine;
    }

    /**
     * 分页查询业务线列表
     */
    public Page<BusinessLine> list(Integer page, Integer size, String name, Integer status) {
        Page<BusinessLine> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<BusinessLine> wrapper = new LambdaQueryWrapper<>();

        // 名称模糊查询
        if (StringUtils.hasText(name)) {
            wrapper.like(BusinessLine::getName, name);
        }

        // 状态筛选
        if (status != null) {
            wrapper.eq(BusinessLine::getStatus, status);
        }

        // 按创建时间倒序
        wrapper.orderByDesc(BusinessLine::getCreatedAt);

        return businessLineMapper.selectPage(pageParam, wrapper);
    }
}
