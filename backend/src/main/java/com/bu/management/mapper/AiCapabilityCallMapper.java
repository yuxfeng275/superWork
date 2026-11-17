package com.bu.management.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bu.management.entity.AiCapabilityCall;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 能力调用日志 Mapper：写入走 BaseMapper，统计走聚合 SQL。
 *
 * @author BU Team
 * @since 2026-11-20
 */
@Mapper
public interface AiCapabilityCallMapper extends BaseMapper<AiCapabilityCall> {

    /**
     * 按能力聚合近 N 天的调用：次数、成功、失败、超时、门禁拒绝、平均耗时、最近调用时间。
     *
     * <p>一次查询把看板要的数字全出：分多条 SQL 会得到互相对不上的快照。
     * 分母用同一批行，成功率 = 成功 / 总次数，不会出现「成功率 + 失败率 > 100%」。
     */
    @Select("SELECT capability_id AS capabilityId, COUNT(*) AS calls, "
            + "SUM(CASE WHEN ok = 1 THEN 1 ELSE 0 END) AS okCalls, "
            + "SUM(CASE WHEN ok = 0 THEN 1 ELSE 0 END) AS failedCalls, "
            + "SUM(CASE WHEN error_code = 'TIMEOUT' THEN 1 ELSE 0 END) AS timeouts, "
            + "SUM(CASE WHEN error_code = 'GATE_DENIED' THEN 1 ELSE 0 END) AS gateDenied, "
            + "ROUND(AVG(latency_ms), 0) AS avgLatencyMs, MAX(created_at) AS lastCalledAt "
            + "FROM ai_capability_call WHERE created_at >= #{since} "
            + "GROUP BY capability_id ORDER BY calls DESC")
    List<Map<String, Object>> statsSince(@Param("since") LocalDateTime since);
}
