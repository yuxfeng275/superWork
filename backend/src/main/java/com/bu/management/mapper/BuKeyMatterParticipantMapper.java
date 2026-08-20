package com.bu.management.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bu.management.entity.BuKeyMatterParticipant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BuKeyMatterParticipantMapper extends BaseMapper<BuKeyMatterParticipant> {

    @Select("SELECT COUNT(1) > 0 FROM bu_key_matter_participant WHERE user_id = #{userId}")
    boolean existsByUserId(@Param("userId") Long userId);
}
