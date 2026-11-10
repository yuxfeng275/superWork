package com.bu.management.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bu.management.entity.AiCapabilityCall;
import org.apache.ibatis.annotations.Mapper;

/**
 * 能力调用日志 Mapper：只做 insert，没有查询需求（统计走 SQL 直出）。
 *
 * @author BU Team
 * @since 2026-11-20
 */
@Mapper
public interface AiCapabilityCallMapper extends BaseMapper<AiCapabilityCall> {}
