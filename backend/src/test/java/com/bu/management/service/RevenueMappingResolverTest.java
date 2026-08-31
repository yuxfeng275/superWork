package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.Project;
import com.bu.management.entity.RevenueSalesProject;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RevenueSalesProjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RevenueMappingResolverTest {

    @Mock private BusinessLineMapper businessLineMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private RevenueSalesProjectMapper salesProjectMapper;

    private RevenueMappingResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new RevenueMappingResolver(businessLineMapper, projectMapper, salesProjectMapper);

        BusinessLine custom = line(1L, "全渠道云鹿定制");
        BusinessLine saas = line(2L, "全渠道云鹿SAAS");
        BusinessLine member = line(3L, "会员通");
        lenient().when(businessLineMapper.selectList(any())).thenReturn(List.of(custom, saas, member));

        lenient().when(projectMapper.selectList(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<Project> wrapper = invocation.getArgument(0);
            List<Project> all = List.of(
                    project(11L, 1L, "皇家项目"),
                    project(12L, 1L, "Speedo"),
                    project(13L, 1L, "澳优"),
                    project(14L, 1L, "飞鹤"),
                    project(21L, 2L, "逢时"),
                    project(22L, 2L, "黄天鹅")
            );
            String sql = wrapper.getSqlSet() == null ? "" : wrapper.getSqlSet();
            // Mockito 下不解 SQL，直接按测试场景全量返回，由 resolver 内部按业务线过滤
            return all;
        });
    }

    private BusinessLine line(Long id, String name) {
        BusinessLine line = new BusinessLine();
        line.setId(id);
        line.setName(name);
        line.setStatus(1);
        return line;
    }

    private Project project(Long id, Long businessLineId, String name) {
        Project project = new Project();
        project.setId(id);
        project.setBusinessLineId(businessLineId);
        project.setName(name);
        project.setStatus(1);
        return project;
    }

    @Test
    void deliverySuffixMapsToExistingProject() {
        RevenueMappingResolver.Resolved resolved = resolver.resolve("全域-全渠道-全域云鹿定制", "澳优项目【交付】");
        assertThat(resolved.workType()).isEqualTo("project");
        assertThat(resolved.businessLineId()).isEqualTo(1L);
        assertThat(resolved.projectId()).isEqualTo(13L);
        assertThat(resolved.pending()).isFalse();
        assertThat(resolved.cleanName()).isEqualTo("澳优");
    }

    @Test
    void productionSuffixAlsoMapsToProject() {
        RevenueMappingResolver.Resolved resolved = resolver.resolve("全域-全渠道-全域云鹿Saas", "黄天鹅全域项目【产研】");
        assertThat(resolved.workType()).isEqualTo("project");
        assertThat(resolved.businessLineId()).isEqualTo(2L);
        assertThat(resolved.projectId()).isEqualTo(22L);
        assertThat(resolved.pending()).isFalse();
    }

    @Test
    void lineLevelSalesGoesToPool() {
        RevenueMappingResolver.Resolved resolved = resolver.resolve("全域-全渠道-全域云鹿Saas", "全域-全渠道-全域云鹿Saas【销售】");
        assertThat(resolved.workType()).isEqualTo("sales");
        assertThat(resolved.salesKind()).isEqualTo("pool");
        assertThat(resolved.lineLevel()).isTrue();
        assertThat(resolved.projectId()).isNull();
        assertThat(resolved.pending()).isFalse();
    }

    @Test
    void lineLevelProjectGoesToLinePool() {
        RevenueMappingResolver.Resolved resolved = resolver.resolve("全域-全渠道-会员通", "全域-全渠道-会员通【项目】");
        assertThat(resolved.workType()).isEqualTo("project");
        assertThat(resolved.lineLevel()).isTrue();
        assertThat(resolved.businessLineId()).isEqualTo(3L);
        assertThat(resolved.pending()).isFalse();
    }

    @Test
    void specificSalesRegistersSalesProject() {
        when(salesProjectMapper.selectOne(any())).thenReturn(null);
        when(salesProjectMapper.insert(any())).thenAnswer(invocation -> {
            RevenueSalesProject created = invocation.getArgument(0);
            created.setId(99L);
            return 1;
        });
        RevenueMappingResolver.Resolved resolved = resolver.resolve("全域-全渠道-全域云鹿定制", "京博【销售】");
        assertThat(resolved.workType()).isEqualTo("sales");
        assertThat(resolved.salesKind()).isEqualTo("specific");
        assertThat(resolved.cleanName()).isEqualTo("京博");
        assertThat(resolved.salesProjectId()).isEqualTo(99L);
        assertThat(resolved.pending()).isFalse();
    }

    @Test
    void otherMatterGoesToSalesOther() {
        RevenueMappingResolver.Resolved resolved = resolver.resolve("全域-全渠道-全域云鹿Saas", "其他事项【产研】");
        assertThat(resolved.workType()).isEqualTo("sales");
        assertThat(resolved.salesKind()).isEqualTo("other");
        assertThat(resolved.pending()).isFalse();
    }

    @Test
    void unknownBusinessLineIsPending() {
        RevenueMappingResolver.Resolved resolved = resolver.resolve("全域-全渠道-全域私域精准", "全域-全渠道-全域私域精准【项目】");
        assertThat(resolved.businessLineId()).isNull();
        assertThat(resolved.pending()).isTrue();
    }

    @Test
    void unknownProjectIsPending() {
        RevenueMappingResolver.Resolved resolved = resolver.resolve("全域-全渠道-全域云鹿定制", "陌生品牌项目【交付】");
        assertThat(resolved.businessLineId()).isEqualTo(1L);
        assertThat(resolved.projectId()).isNull();
        assertThat(resolved.pending()).isTrue();
    }

    @Test
    void tagsWorkNoteWithKnownBrands() {
        RevenueSalesProject jingbo = new RevenueSalesProject();
        jingbo.setId(99L);
        jingbo.setName("京博");
        when(salesProjectMapper.selectList(null)).thenReturn(List.of(jingbo));
        String tags = resolver.tagWorkNote("speedo 日常问题处理，京博售前支持");
        assertThat(tags).contains("Speedo").contains("京博");
    }
}
