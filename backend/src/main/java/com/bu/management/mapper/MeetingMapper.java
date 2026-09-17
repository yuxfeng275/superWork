package com.bu.management.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bu.management.entity.Meeting;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会议 Mapper
 */
@Mapper
public interface MeetingMapper extends BaseMapper<Meeting> {
}
