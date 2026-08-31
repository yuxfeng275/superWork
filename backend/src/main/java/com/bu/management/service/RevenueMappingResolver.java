package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.BusinessLine;
import com.bu.management.entity.Project;
import com.bu.management.entity.RevenueNameMapping;
import com.bu.management.entity.RevenueSalesProject;
import com.bu.management.mapper.BusinessLineMapper;
import com.bu.management.mapper.ProjectMapper;
import com.bu.management.mapper.RevenueNameMappingMapper;
import com.bu.management.mapper.RevenueSalesProjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 营收导入的归属解析：把 Excel 原始业务线/项目名映射到系统业务线与项目。
 * 规则来自《BU业务营收管理.xlsx》说明：
 * - 【交付】【产研】归为「项目」；【销售】归为「销售」；「其他事项」归为销售下的「其他」
 * - 业务线级【销售】（项目名=业务线名）归「商机集合」，按工作说明打品牌/项目/商机标签
 * - 「京博【销售】」这类具体名称注册为销售项目，可手动关联商机
 * - 匹配不上业务线或项目的行标记 pending，进待映射清单
 */
@Component
@RequiredArgsConstructor
public class RevenueMappingResolver {

    private static final Pattern TYPE_SUFFIX = Pattern.compile("^(.*?)【(交付|产研|销售|项目)】$");
    private static final Pattern PROJECT_TRAILING = Pattern.compile("(全域|全渠道)?项目$");
    private static final String OTHER_NAME = "其他事项";

    private final BusinessLineMapper businessLineMapper;
    private final ProjectMapper projectMapper;
    private final RevenueSalesProjectMapper salesProjectMapper;
    private final RevenueNameMappingMapper nameMappingMapper;

    public enum WorkType { PROJECT, SALES }

    public enum SalesKind { SPECIFIC, POOL, OTHER }

    /** 解析结果：workType/salesKind + 归属；业务线或项目缺失时 pending=true */
    public record Resolved(Long businessLineId, Long projectId, Long salesProjectId, String workType,
                           String salesKind, String cleanName, boolean lineLevel, boolean pending) {
    }

    public Resolved resolve(String rawBusinessLine, String rawProjectName) {
        // 人工映射记忆优先：待映射清单里确认过的归属，后续导入直接套用
        RevenueNameMapping remembered = rawProjectName == null ? null : nameMappingMapper.selectOne(
                new LambdaQueryWrapper<RevenueNameMapping>()
                        .eq(RevenueNameMapping::getRawBusinessLine, rawBusinessLine)
                        .eq(RevenueNameMapping::getRawProjectName, rawProjectName));
        Long rememberedProjectId = remembered == null ? null : remembered.getProjectId();
        Long businessLineId = remembered != null ? remembered.getBusinessLineId() : matchBusinessLine(rawBusinessLine);
        String projectPart = rawProjectName == null ? "" : rawProjectName.trim();
        String tag = null;
        Matcher matcher = TYPE_SUFFIX.matcher(projectPart);
        if (matcher.matches()) {
            projectPart = matcher.group(1).trim();
            tag = matcher.group(2);
        }

        if (OTHER_NAME.equals(projectPart)) {
            return new Resolved(businessLineId, null, null, "sales",
                    SalesKind.OTHER.name().toLowerCase(), projectPart, false, businessLineId == null);
        }

        boolean lineLevel = projectPart.equals(rawBusinessLine == null ? null : rawBusinessLine.trim());
        if (lineLevel) {
            // 业务线级：【销售】进商机集合，【交付/产研】进项目集
            if ("销售".equals(tag)) {
                return new Resolved(businessLineId, null, null, "sales",
                        SalesKind.POOL.name().toLowerCase(), projectPart, true, businessLineId == null);
            }
            return new Resolved(businessLineId, null, null, "project", null, projectPart, true, businessLineId == null);
        }

        String token = PROJECT_TRAILING.matcher(projectPart).replaceFirst("").trim();
        if ("销售".equals(tag)) {
            RevenueSalesProject salesProject = registerSalesProject(businessLineId, token);
            return new Resolved(businessLineId, null, salesProject == null ? null : salesProject.getId(),
                    "sales", SalesKind.SPECIFIC.name().toLowerCase(), token, false, businessLineId == null);
        }

        Long projectId;
        boolean pending;
        if (remembered != null) {
            // 记忆中的 projectId 为空 = 人工确认为业务线级，视为已映射
            projectId = rememberedProjectId;
            pending = false;
        } else {
            projectId = businessLineId == null ? null : matchProject(businessLineId, token);
            pending = businessLineId == null || projectId == null;
        }
        return new Resolved(businessLineId, projectId, null, "project", null, token, false, pending);
    }

    /** 业务线匹配：会员通/SAAS/定制 关键字 → 系统业务线 */
    public Long matchBusinessLine(String rawName) {
        if (!StringUtils.hasText(rawName)) {
            return null;
        }
        String raw = rawName.trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        String keyword;
        if (raw.contains("会员通")) {
            keyword = "会员通";
        } else if (raw.contains("精准")) {
            keyword = "精准";
        } else if (lower.contains("saas")) {
            keyword = "saas";
        } else if (raw.contains("定制")) {
            keyword = "定制";
        } else {
            return null;
        }
        return businessLineMapper.selectList(new LambdaQueryWrapper<BusinessLine>().eq(BusinessLine::getStatus, 1))
                .stream()
                .filter(line -> line.getName() != null
                        && line.getName().toLowerCase(Locale.ROOT).contains(keyword))
                .map(BusinessLine::getId)
                .findFirst()
                .orElse(null);
    }

    /** 项目匹配：同一业务线下按品牌词双向包含匹配（皇家 ↔ 皇家项目） */
    public Long matchProject(Long businessLineId, String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        // 营收统计不限项目状态：已完结（status=4）项目仍会产生工时与成本
        List<Project> candidates = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                .eq(Project::getBusinessLineId, businessLineId));
        String needle = normalize(token);
        for (Project project : candidates) {
            String name = normalize(project.getName());
            if (name.equals(needle) || name.contains(needle) || needle.contains(name)) {
                return project.getId();
            }
        }
        return null;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return PROJECT_TRAILING.matcher(value.trim()).replaceFirst("").trim();
    }

    /** 具体销售项目注册（京博【销售】→ 京博），已存在则复用 */
    public RevenueSalesProject registerSalesProject(Long businessLineId, String name) {
        if (businessLineId == null || !StringUtils.hasText(name)) {
            return null;
        }
        RevenueSalesProject existing = salesProjectMapper.selectOne(
                new LambdaQueryWrapper<RevenueSalesProject>()
                        .eq(RevenueSalesProject::getBusinessLineId, businessLineId)
                        .eq(RevenueSalesProject::getName, name));
        if (existing != null) {
            return existing;
        }
        RevenueSalesProject created = new RevenueSalesProject();
        created.setBusinessLineId(businessLineId);
        created.setName(name);
        salesProjectMapper.insert(created);
        return created;
    }

    /** 商机集合标签：从工作说明中识别已知品牌/项目/销售项目名 */
    public String tagWorkNote(String workNote) {
        if (!StringUtils.hasText(workNote)) {
            return null;
        }
        Set<String> tags = new LinkedHashSet<>();
        List<String> keywords = new ArrayList<>();
        projectMapper.selectList(null)
                .forEach(project -> keywords.add(normalize(project.getName())));
        salesProjectMapper.selectList(null)
                .forEach(item -> keywords.add(item.getName()));
        String lowerNote = workNote.toLowerCase(Locale.ROOT);
        keywords.stream().filter(StringUtils::hasText).distinct().forEach(keyword -> {
            if (lowerNote.contains(keyword.toLowerCase(Locale.ROOT))) {
                tags.add(keyword);
            }
        });
        return tags.isEmpty() ? null : String.join(",", tags);
    }
}
