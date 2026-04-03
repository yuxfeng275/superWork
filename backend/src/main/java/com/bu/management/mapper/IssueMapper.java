package com.bu.management.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bu.management.entity.Issue;
import org.apache.ibatis.annotations.Mapper;

/**
 * 事项 Mapper
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Mapper
public interface IssueMapper extends BaseMapper<Issue> {
}
