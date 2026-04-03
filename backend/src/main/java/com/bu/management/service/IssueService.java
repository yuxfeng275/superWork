package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bu.management.dto.CreateIssueDTO;
import com.bu.management.dto.UpdateIssueDTO;
import com.bu.management.entity.Issue;
import com.bu.management.mapper.IssueMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事项服务
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Service
@RequiredArgsConstructor
public class IssueService {

    private final IssueMapper issueMapper;

    /**
     * 创建事项
     */
    @Transactional
    public Issue createIssue(CreateIssueDTO dto) {
        Issue issue = new Issue();
        issue.setRelatedType(dto.getRelatedType());
        issue.setRelatedId(dto.getRelatedId());
        issue.setTitle(dto.getTitle());
        issue.setDescription(dto.getDescription());
        issue.setIssueType(dto.getIssueType());
        issue.setSeverity(dto.getSeverity() != null ? dto.getSeverity() : "中");
        issue.setStatus("待处理");
        issue.setAssigneeId(dto.getAssigneeId());
        issue.setCreatedBy(dto.getCreatedBy());
        issue.setCreatedAt(LocalDateTime.now());
        issue.setUpdatedAt(LocalDateTime.now());

        issueMapper.insert(issue);
        return issue;
    }

    /**
     * 更新事项
     */
    @Transactional
    public Issue updateIssue(Long id, UpdateIssueDTO dto) {
        Issue issue = issueMapper.selectById(id);
        if (issue == null) {
            throw new RuntimeException("事项不存在");
        }

        if (dto.getTitle() != null) {
            issue.setTitle(dto.getTitle());
        }
        if (dto.getDescription() != null) {
            issue.setDescription(dto.getDescription());
        }
        if (dto.getIssueType() != null) {
            issue.setIssueType(dto.getIssueType());
        }
        if (dto.getSeverity() != null) {
            issue.setSeverity(dto.getSeverity());
        }
        if (dto.getStatus() != null) {
            issue.setStatus(dto.getStatus());
            if ("已解决".equals(dto.getStatus()) || "已关闭".equals(dto.getStatus())) {
                issue.setResolvedAt(LocalDateTime.now());
            }
        }
        if (dto.getAssigneeId() != null) {
            issue.setAssigneeId(dto.getAssigneeId());
        }
        if (dto.getResolution() != null) {
            issue.setResolution(dto.getResolution());
        }
        issue.setUpdatedAt(LocalDateTime.now());

        issueMapper.updateById(issue);
        return issue;
    }

    /**
     * 查询事项详情
     */
    public Issue getIssueById(Long id) {
        return issueMapper.selectById(id);
    }

    /**
     * 查询关联对象的事项列表
     */
    public List<Issue> getIssuesByRelated(String relatedType, Long relatedId) {
        LambdaQueryWrapper<Issue> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Issue::getRelatedType, relatedType);
        wrapper.eq(Issue::getRelatedId, relatedId);
        wrapper.orderByDesc(Issue::getCreatedAt);
        return issueMapper.selectList(wrapper);
    }

    /**
     * 分页查询事项
     */
    public Page<Issue> getIssuesPage(int page, int size, String relatedType, Long relatedId,
                                      String issueType, String severity, String status, Long assigneeId) {
        Page<Issue> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<Issue> wrapper = new LambdaQueryWrapper<>();

        if (relatedType != null && !relatedType.isEmpty()) {
            wrapper.eq(Issue::getRelatedType, relatedType);
        }
        if (relatedId != null) {
            wrapper.eq(Issue::getRelatedId, relatedId);
        }
        if (issueType != null && !issueType.isEmpty()) {
            wrapper.eq(Issue::getIssueType, issueType);
        }
        if (severity != null && !severity.isEmpty()) {
            wrapper.eq(Issue::getSeverity, severity);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(Issue::getStatus, status);
        }
        if (assigneeId != null) {
            wrapper.eq(Issue::getAssigneeId, assigneeId);
        }

        wrapper.orderByDesc(Issue::getCreatedAt);
        return issueMapper.selectPage(pageParam, wrapper);
    }

    /**
     * 删除事项
     */
    @Transactional
    public void deleteIssue(Long id) {
        issueMapper.deleteById(id);
    }
}
