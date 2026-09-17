package com.bu.management.constant;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class PositionRoles {

    public static final String DIRECTOR = "DIRECTOR";
    public static final String DEPUTY_DIRECTOR = "DEPUTY_DIRECTOR";
    public static final String BUSINESS_OWNER = "BUSINESS_OWNER";
    public static final String EFFECTIVENESS_OWNER = "EFFECTIVENESS_OWNER";
    public static final String SOLUTION_MANAGER = "SOLUTION_MANAGER";
    public static final String TECH_ARCHITECT = "TECH_ARCHITECT";
    public static final String FULL_STACK_ENGINEER = "FULL_STACK_ENGINEER";
    public static final String QUALITY_ENGINEER = "QUALITY_ENGINEER";
    public static final String AI_OPERATIONS_ENGINEER = "AI_OPERATIONS_ENGINEER";
    public static final String AI_CUSTOMER_SERVICE = "AI_CUSTOMER_SERVICE";
    public static final String EXPERIENCE_CONTENT_DESIGNER = "EXPERIENCE_CONTENT_DESIGNER";

    public record RolePreset(String code, String name, String description, String sequence, String dataScope) {
    }

    public static final List<RolePreset> DEFAULT_ROLE_PRESETS = List.of(
            new RolePreset(DIRECTOR, "总监", "部门第一负责人，对整体经营结果、能力建设质量负责", "管理序列", "ALL"),
            new RolePreset(DEPUTY_DIRECTOR, "副总监", "协助总监统筹日常运营与专项体系建设", "管理序列", "ALL"),
            new RolePreset(BUSINESS_OWNER, "经营负责人", "业务发展一号位，对业务域经营结果负责", "管理序列", "ALL"),
            new RolePreset(EFFECTIVENESS_OWNER, "成效负责人", "服务支撑一号位，对服务域成效结果负责", "管理序列", "ALL"),
            new RolePreset(SOLUTION_MANAGER, "解决方案经理", "负责客户需求、方案、计划、实施复核与项目交付", "执行序列", "PROJECT"),
            new RolePreset(TECH_ARCHITECT, "技术架构师", "负责技术栈设计、规划、实施、升级与技术赋能", "执行序列", "PROJECT"),
            new RolePreset(FULL_STACK_ENGINEER, "全栈工程师", "负责需求、方案、开发、自测、交付、监控维护完整链路", "执行序列", "PROJECT"),
            new RolePreset(QUALITY_ENGINEER, "质量工程师", "负责测试体系搭建、验收执行与全链路质量保障", "执行序列", "PROJECT"),
            new RolePreset(AI_OPERATIONS_ENGINEER, "智能运营工程师", "负责业务运营、数据分析与 AI 工具落地推广", "执行序列", "BU_LINE"),
            new RolePreset(AI_CUSTOMER_SERVICE, "智能客服专员", "负责全渠道客户咨询、反馈、报修、SLA 与知识库", "执行序列", "SELF"),
            new RolePreset(EXPERIENCE_CONTENT_DESIGNER, "体验与内容设计师", "负责 UI、交互、平面与内容设计交付，沉淀设计规范", "执行序列", "PROJECT")
    );

    public static final Set<String> DEFAULT_ROLE_CODES = DEFAULT_ROLE_PRESETS.stream()
            .map(RolePreset::code)
            .collect(Collectors.toUnmodifiableSet());

    private static final Map<String, RolePreset> DEFAULT_ROLE_BY_CODE = DEFAULT_ROLE_PRESETS.stream()
            .collect(Collectors.toUnmodifiableMap(RolePreset::code, role -> role));

    private static final Set<String> ADMIN_ROLES = Set.of(
            DIRECTOR,
            DEPUTY_DIRECTOR,
            BUSINESS_OWNER,
            EFFECTIVENESS_OWNER,
            "BU_ADMIN"
    );

    private static final Set<String> PROJECT_OWNER_ROLES = Set.of(
            DIRECTOR,
            DEPUTY_DIRECTOR,
            BUSINESS_OWNER,
            EFFECTIVENESS_OWNER,
            SOLUTION_MANAGER,
            "BU_ADMIN",
            "PM"
    );

    private static final Set<String> PROJECT_SCOPED_ROLES = Set.of(
            SOLUTION_MANAGER,
            TECH_ARCHITECT,
            "PM",
            "TECH_MANAGER"
    );

    public static final Map<String, String> ROLE_LABELS = Map.ofEntries(
            Map.entry(DIRECTOR, "总监"),
            Map.entry(DEPUTY_DIRECTOR, "副总监"),
            Map.entry(BUSINESS_OWNER, "经营负责人"),
            Map.entry(EFFECTIVENESS_OWNER, "成效负责人"),
            Map.entry(SOLUTION_MANAGER, "解决方案经理"),
            Map.entry(TECH_ARCHITECT, "技术架构师"),
            Map.entry(FULL_STACK_ENGINEER, "全栈工程师"),
            Map.entry(QUALITY_ENGINEER, "质量工程师"),
            Map.entry(AI_OPERATIONS_ENGINEER, "智能运营工程师"),
            Map.entry(AI_CUSTOMER_SERVICE, "智能客服专员"),
            Map.entry(EXPERIENCE_CONTENT_DESIGNER, "体验与内容设计师")
    );

    public static final Map<String, String> ROLE_ALIASES = Map.ofEntries(
            Map.entry(DIRECTOR, "总监"),
            Map.entry(DEPUTY_DIRECTOR, "副总监"),
            Map.entry(BUSINESS_OWNER, "经营负责人"),
            Map.entry(EFFECTIVENESS_OWNER, "成效负责人"),
            Map.entry(SOLUTION_MANAGER, "解决方案经理"),
            Map.entry(TECH_ARCHITECT, "技术架构师"),
            Map.entry(FULL_STACK_ENGINEER, "全栈工程师"),
            Map.entry(QUALITY_ENGINEER, "质量工程师"),
            Map.entry(AI_OPERATIONS_ENGINEER, "智能运营工程师"),
            Map.entry(AI_CUSTOMER_SERVICE, "智能客服专员"),
            Map.entry(EXPERIENCE_CONTENT_DESIGNER, "体验与内容设计师"),
            Map.entry("BU_ADMIN", "经营负责人"),
            Map.entry("BU负责人", "经营负责人"),
            Map.entry("BU管理员", "经营负责人"),
            Map.entry("PM", "解决方案经理"),
            Map.entry("项目经理", "解决方案经理"),
            Map.entry("PRODUCT", "解决方案经理"),
            Map.entry("PRODUCT_MANAGER", "解决方案经理"),
            Map.entry("产品经理", "解决方案经理"),
            Map.entry("TECH_MANAGER", "技术架构师"),
            Map.entry("技术经理", "技术架构师"),
            Map.entry("DEVELOPER", "全栈工程师"),
            Map.entry("研发", "全栈工程师"),
            Map.entry("开发", "全栈工程师"),
            Map.entry("前端开发", "全栈工程师"),
            Map.entry("后端开发", "全栈工程师"),
            Map.entry("TESTER", "质量工程师"),
            Map.entry("测试", "质量工程师"),
            Map.entry("UI_DESIGN", "体验与内容设计师"),
            Map.entry("UI_DESIGNER", "体验与内容设计师"),
            Map.entry("UI设计", "体验与内容设计师")
    );

    public static final Set<String> WORKFLOW_ROLE_LABELS = Set.of(
            "总监",
            "副总监",
            "经营负责人",
            "成效负责人",
            "解决方案经理",
            "技术架构师",
            "全栈工程师",
            "质量工程师",
            "智能运营工程师",
            "智能客服专员",
            "体验与内容设计师",
            "系统自动"
    );

    private PositionRoles() {
    }

    public static boolean isAdminRole(String role) {
        return ADMIN_ROLES.contains(role);
    }

    public static boolean canManageProject(String role) {
        return PROJECT_OWNER_ROLES.contains(role);
    }

    public static boolean isProjectScopedRole(String role) {
        return PROJECT_SCOPED_ROLES.contains(role);
    }

    public static boolean isDefaultRole(String role) {
        return DEFAULT_ROLE_CODES.contains(role);
    }

    public static RolePreset getDefaultRole(String role) {
        return DEFAULT_ROLE_BY_CODE.get(role);
    }

    public static String normalizeWorkflowRole(String role) {
        return ROLE_ALIASES.getOrDefault(role, role);
    }
}
