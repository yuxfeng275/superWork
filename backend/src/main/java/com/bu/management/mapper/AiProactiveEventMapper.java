package com.bu.management.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bu.management.entity.AiProactiveEvent;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 主动性事件日志：写入与按用户查最近 N 天。
 *
 * @author BU Team
 * @since 2026-11-17
 */
@Mapper
public interface AiProactiveEventMapper extends BaseMapper<AiProactiveEvent> {

    /** 某用户窗口内的事件，新的在前；用于 E2E 核对「事件确实触发了扫描」。 */
    @Select("SELECT * FROM ai_proactive_event WHERE user_id = #{userId} AND created_at >= #{since} "
            + "ORDER BY id DESC LIMIT #{limit}")
    List<AiProactiveEvent> recent(@Param("userId") Long userId,
                                  @Param("since") LocalDateTime since,
                                  @Param("limit") int limit);
}
