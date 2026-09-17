package com.bu.management.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bu.management.entity.WorkLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 工时记录 Mapper
 *
 * @author BU Team
 * @since 2026-04-03
 */
@Mapper
public interface WorkLogMapper extends BaseMapper<WorkLog> {

    /**
     * KPI 当月人工估算用：只取两端 Schema（新旧 work_log 表结构漂移）都存在的列，
     * 经 task 关联出 requirement_id 以归集业务线。
     */
    @Select("""
            SELECT wl.work_date AS workDate, wl.hours AS hours, t.requirement_id AS requirementId
            FROM work_log wl JOIN task t ON t.id = wl.task_id
            WHERE wl.work_date >= #{start} AND wl.work_date <= #{end}
            """)
    List<Map<String, Object>> findEstimateRows(@Param("start") LocalDate start, @Param("end") LocalDate end);
}
