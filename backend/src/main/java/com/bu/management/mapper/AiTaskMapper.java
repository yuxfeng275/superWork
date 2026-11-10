package com.bu.management.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bu.management.entity.AiTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 任务队列 Mapper：只走 MyBatis-Plus 的通用 CRUD。
 *
 * <p>抢夺式执行不需要写自定义 XML：{@code update(null, LambdaUpdateWrapper)} 拼出
 * {@code update ai_task set status='RUNNING', attempt=attempt+1 ... where id=? and status='PENDING'}，
 * 靠返回的影响行数判胜负即可。
 *
 * @author BU Team
 * @since 2026-12-10
 */
@Mapper
public interface AiTaskMapper extends BaseMapper<AiTask> {
}
