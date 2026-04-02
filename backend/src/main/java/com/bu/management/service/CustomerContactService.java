package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.dto.CustomerContactRequest;
import com.bu.management.entity.CustomerContact;
import com.bu.management.entity.Project;
import com.bu.management.mapper.CustomerContactMapper;
import com.bu.management.mapper.ProjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 客户联系人服务
 *
 * @author BU Team
 * @since 2026-04-02
 */
@Service
@RequiredArgsConstructor
public class CustomerContactService {

    private final CustomerContactMapper customerContactMapper;
    private final ProjectMapper projectMapper;

    /**
     * 创建客户联系人
     */
    @Transactional(rollbackFor = Exception.class)
    public CustomerContact create(CustomerContactRequest request) {
        // 验证项目是否存在
        Project project = projectMapper.selectById(request.getProjectId());
        if (project == null) {
            throw new RuntimeException("项目不存在");
        }

        CustomerContact contact = new CustomerContact();
        contact.setProjectId(request.getProjectId());
        contact.setName(request.getName());
        contact.setCompany(request.getCompany());
        contact.setPosition(request.getPosition());
        contact.setPhone(request.getPhone());
        contact.setEmail(request.getEmail());
        contact.setIsActive(request.getIsActive() != null ? request.getIsActive() : 1);

        customerContactMapper.insert(contact);
        return contact;
    }

    /**
     * 更新客户联系人
     */
    @Transactional(rollbackFor = Exception.class)
    public CustomerContact update(Long id, CustomerContactRequest request) {
        CustomerContact contact = customerContactMapper.selectById(id);
        if (contact == null) {
            throw new RuntimeException("客户联系人不存在");
        }

        // 验证项目是否存在
        Project project = projectMapper.selectById(request.getProjectId());
        if (project == null) {
            throw new RuntimeException("项目不存在");
        }

        contact.setProjectId(request.getProjectId());
        contact.setName(request.getName());
        contact.setCompany(request.getCompany());
        contact.setPosition(request.getPosition());
        contact.setPhone(request.getPhone());
        contact.setEmail(request.getEmail());
        if (request.getIsActive() != null) {
            contact.setIsActive(request.getIsActive());
        }

        customerContactMapper.updateById(contact);
        return contact;
    }

    /**
     * 删除客户联系人
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        CustomerContact contact = customerContactMapper.selectById(id);
        if (contact == null) {
            throw new RuntimeException("客户联系人不存在");
        }

        customerContactMapper.deleteById(id);
    }

    /**
     * 获取客户联系人详情
     */
    public CustomerContact getById(Long id) {
        CustomerContact contact = customerContactMapper.selectById(id);
        if (contact == null) {
            throw new RuntimeException("客户联系人不存在");
        }
        return contact;
    }

    /**
     * 分页查询客户联系人列表
     */
    public Page<CustomerContact> list(Integer page, Integer size, Long projectId, String name, Integer isActive) {
        Page<CustomerContact> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<CustomerContact> wrapper = new LambdaQueryWrapper<>();

        // 项目筛选
        if (projectId != null) {
            wrapper.eq(CustomerContact::getProjectId, projectId);
        }

        // 姓名模糊查询
        if (StringUtils.hasText(name)) {
            wrapper.like(CustomerContact::getName, name);
        }

        // 状态筛选
        if (isActive != null) {
            wrapper.eq(CustomerContact::getIsActive, isActive);
        }

        // 按创建时间倒序
        wrapper.orderByDesc(CustomerContact::getCreatedAt);

        return customerContactMapper.selectPage(pageParam, wrapper);
    }
}
