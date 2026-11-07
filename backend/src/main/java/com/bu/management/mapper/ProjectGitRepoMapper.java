package com.bu.management.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bu.management.entity.ProjectGitRepo;
import org.apache.ibatis.annotations.Mapper;

/** 项目绑定仓库 */
@Mapper
public interface ProjectGitRepoMapper extends BaseMapper<ProjectGitRepo> {
}
