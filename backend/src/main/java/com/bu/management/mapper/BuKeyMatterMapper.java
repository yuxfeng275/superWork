package com.bu.management.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bu.management.entity.BuKeyMatter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BuKeyMatterMapper extends BaseMapper<BuKeyMatter> {

    @Select("SELECT * FROM bu_key_matter WHERE id = #{id} FOR UPDATE")
    BuKeyMatter selectByIdForUpdate(@Param("id") Long id);
}
