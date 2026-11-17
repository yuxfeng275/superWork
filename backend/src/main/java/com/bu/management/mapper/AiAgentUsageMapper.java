package com.bu.management.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bu.management.entity.AiAgentUsage;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiAgentUsageMapper extends BaseMapper<AiAgentUsage> {

    /** 近 N 天用过助手的用户：站外推送的活跃度过滤口径，避免给长期不用的人发提醒。 */
    @Select("SELECT DISTINCT user_id FROM ai_agent_usage WHERE created_at >= #{since} AND user_id IS NOT NULL")
    List<Long> activeUserIdsSince(@Param("since") LocalDateTime since);
}
