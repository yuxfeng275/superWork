package com.bu.management.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bu.management.entity.AiPrompt;
import org.apache.ibatis.annotations.Mapper;

/** Prompt 版本库 Mapper：版本读写全部走 BaseMapper，无自定义 SQL。 */
@Mapper
public interface AiPromptMapper extends BaseMapper<AiPrompt> {}
