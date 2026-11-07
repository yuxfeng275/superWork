package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.AiBrief;
import com.bu.management.mapper.AiBriefMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 每人每日晨报：夜间产出汇总后的收件箱。
 *
 * <p>口径（方案 §5.4）：一人一份、不群发；(user_id, brief_date, project_id) 唯一，
 * 夜间重复生成只覆盖当天那条，不会给同一个人发多条。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectBriefService {

    private final AiBriefMapper briefMapper;

    /** 写入（或覆盖）某人某天某项目的晨报；返回行 id。 */
    @Transactional
    public Long save(Long userId, Long projectId, LocalDate briefDate, String content, String sourceTask) {
        Long pid = projectId == null ? 0L : projectId;
        LocalDate date = briefDate == null ? LocalDate.now() : briefDate;
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("晨报内容不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        AiBrief existing = briefMapper.selectOne(new LambdaQueryWrapper<AiBrief>()
                .eq(AiBrief::getUserId, userId)
                .eq(AiBrief::getBriefDate, date)
                .eq(AiBrief::getProjectId, pid)
                .last("limit 1"));
        if (existing == null) {
            AiBrief brief = new AiBrief();
            brief.setUserId(userId);
            brief.setProjectId(pid);
            brief.setBriefDate(date);
            brief.setContent(content);
            brief.setSourceTask(sourceTask);
            brief.setCreatedAt(now);
            brief.setUpdatedAt(now);
            briefMapper.insert(brief);
            return brief.getId();
        }
        existing.setContent(content);
        existing.setSourceTask(sourceTask);
        existing.setUpdatedAt(now);
        briefMapper.updateById(existing);
        return existing.getId();
    }

    /** 某人某天的晨报（含跨项目总晨报）。 */
    public List<AiBrief> listByDate(Long userId, LocalDate date) {
        LocalDate target = date == null ? LocalDate.now() : date;
        return briefMapper.selectList(new LambdaQueryWrapper<AiBrief>()
                .eq(AiBrief::getUserId, userId)
                .eq(AiBrief::getBriefDate, target)
                .orderByAsc(AiBrief::getProjectId));
    }

    /** 标记送达（企微/站内），只写一次。 */
    public void markDelivered(Long briefId) {
        AiBrief brief = briefMapper.selectById(briefId);
        if (brief == null || brief.getDeliveredAt() != null) {
            return;
        }
        brief.setDeliveredAt(LocalDateTime.now());
        brief.setUpdatedAt(LocalDateTime.now());
        briefMapper.updateById(brief);
    }
}
