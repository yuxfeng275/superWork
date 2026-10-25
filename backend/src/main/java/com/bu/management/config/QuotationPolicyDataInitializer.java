package com.bu.management.config;

import com.bu.management.entity.QuotationPolicy;
import com.bu.management.entity.QuotationPolicyItem;
import com.bu.management.entity.QuotationTemplate;
import com.bu.management.entity.QuotationTemplateClause;
import com.bu.management.mapper.QuotationPolicyItemMapper;
import com.bu.management.mapper.QuotationPolicyMapper;
import com.bu.management.mapper.QuotationTemplateClauseMapper;
import com.bu.management.mapper.QuotationTemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@Order(999)
@org.springframework.context.annotation.Profile("!test")
public class QuotationPolicyDataInitializer implements CommandLineRunner {

    private final QuotationPolicyMapper policyMapper;
    private final QuotationPolicyItemMapper itemMapper;
    private final QuotationTemplateMapper templateMapper;
    private final QuotationTemplateClauseMapper clauseMapper;

    @Override
    public void run(String... args) {
        seedTemplates();
        Long count = policyMapper.selectCount(null);
        if (count != null && count > 0) {
            log.info("Quotation policies already initialized, skipping seed data.");
            return;
        }
        log.info("Initializing quotation policy seed data...");
        initPolicies();
        bindTemplates();
        log.info("Quotation policy seed data initialized successfully.");
    }

    /** 按策略类型绑定同类型已发布模板，避免生成报价时看不到条款。 */
    private void bindTemplates() {
        for (QuotationPolicy policy : policyMapper.selectList(null)) {
            if (policy.getTemplateId() != null) continue;
            QuotationTemplate template = templateMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QuotationTemplate>()
                            .eq(QuotationTemplate::getType, policy.getType())
                            .eq(QuotationTemplate::getStatus, "PUBLISHED")
                            .last("LIMIT 1"));
            if (template == null) continue;
            policy.setTemplateId(template.getId());
            policyMapper.updateById(policy);
        }
    }

    private void initPolicies() {
        LocalDate effectiveDate = LocalDate.now();

        // ============ SAAS strategies ============
        initSaasPolicy(effectiveDate, "TAX_INCLUDED");
        initSaasPolicy(effectiveDate, "TAX_EXCLUDED");

        // ============ PRIVATE_DEPLOYMENT strategies ============
        initPrivateDeploymentPolicy(effectiveDate, "TAX_INCLUDED");
        initPrivateDeploymentPolicy(effectiveDate, "TAX_EXCLUDED");

        // ============ MEMBERSHIP strategies ============
        initMembershipPolicy(effectiveDate, "TAX_INCLUDED");
        initMembershipPolicy(effectiveDate, "TAX_EXCLUDED");
    }

    private QuotationPolicy createPolicy(String name, String type, String taxMode, LocalDate effectiveDate) {
        QuotationPolicy policy = new QuotationPolicy();
        policy.setName(name);
        policy.setType(type);
        policy.setTaxMode(taxMode);
        policy.setVersion(1);
        policy.setStatus("PUBLISHED");
        policy.setMinDiscountRate(new BigDecimal("0.85"));
        policy.setApprovalRequired(1);
        policy.setEffectiveDate(effectiveDate);
        policy.setCreatedAt(LocalDateTime.now());
        policyMapper.insert(policy);
        return policy;
    }

    private QuotationPolicyItem createItem(Long policyId, String section, String category, String itemKey,
                                            String itemName, String description, String priceDescription,
                                            int isRequired, String unitPrice, String taxRate,
                                            String chargeMethod, String chargeUnit, int sortOrder) {
        QuotationPolicyItem item = new QuotationPolicyItem();
        item.setPolicyId(policyId);
        item.setSection(section);
        item.setCategory(category);
        item.setItemKey(itemKey);
        item.setItemName(itemName);
        item.setDescription(description);
        item.setPriceDescription(priceDescription);
        item.setIsRequired(isRequired);
        item.setUnitPrice(unitPrice != null ? new BigDecimal(unitPrice) : null);
        item.setTaxRate(taxRate != null ? new BigDecimal(taxRate) : null);
        item.setChargeMethod(chargeMethod);
        item.setChargeUnit(chargeUnit);
        item.setFirstYearFree(0);
        item.setSortOrder(sortOrder);
        item.setCreatedAt(LocalDateTime.now());
        itemMapper.insert(item);
        return item;
    }

    /**
     * 按比例计费明细项：unitPrice 留空，由 chargeRate × 基数分组小计 得出金额。
     * 历史上运维服务单价为空导致金额恒为 0，这里把“产品模块总价的 20%/年”真正落到数据里。
     */
    private void createPercentItem(Long policyId, String section, String itemKey, String itemName,
                                   String description, String priceDescription, int isRequired,
                                   String baseSection, String rate, String taxRate,
                                   int isRequiredFlag, int firstYearFree, int sortOrder) {
        QuotationPolicyItem item = new QuotationPolicyItem();
        item.setPolicyId(policyId);
        item.setSection(section);
        item.setCategory(baseSection);
        item.setItemKey(itemKey);
        item.setItemName(itemName);
        item.setDescription(description);
        item.setPriceDescription(priceDescription);
        item.setIsRequired(isRequiredFlag);
        item.setTaxRate(new BigDecimal(taxRate));
        item.setChargeMethod("PERCENT_OF_BASE");
        item.setChargeBaseSection(baseSection);
        item.setChargeRate(new BigDecimal(rate));
        item.setFirstYearFree(firstYearFree);
        item.setChargeUnit("年");
        item.setSortOrder(sortOrder);
        item.setCreatedAt(LocalDateTime.now());
        itemMapper.insert(item);
    }

    // ==================== 报价模板 ====================
    /** 幂等：已存在同名的已发布模板则跳过，保证重启不重复插入条款。 */
    private void seedTemplates() {
        Long existing = templateMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QuotationTemplate>()
                        .eq(QuotationTemplate::getStatus, "PUBLISHED"));
        if (existing != null && existing > 0) return;

        Long saas = createTemplate("SaaS 标准报价模板", "SAAS", "云鹿 SaaS 全渠道产品报价单",
                "产品模块与服务费", 30);
        Long priv = createTemplate("私有化部署报价模板", "PRIVATE_DEPLOYMENT", "云鹿私有化部署报价单",
                "产品模块与服务费", 45);
        Long member = createTemplate("会员通报价模板", "MEMBERSHIP", "会员通对接报价单",
                "产品模块与服务费", 30);

        for (QuotationTemplate template : List.of(saas, priv, member).stream()
                .map(templateMapper::selectById).filter(java.util.Objects::nonNull).toList()) {
            addClause(template.getId(), "报价说明", "报价有效期",
                    "本报价自 {{quoteDate}} 起 {{validityDays}} 天内有效，逾期需重新确认。", 0);
            addClause(template.getId(), "报价说明", "价格口径",
                    "本报价单货币为 {{currency}}，发票类型：{{invoiceType}}。", 1);
            addClause(template.getId(), "交付与付款", "交付周期",
                    "合同签订后 {{deliveryPeriod}} 内完成交付。", 2);
            addClause(template.getId(), "交付与付款", "付款条件",
                    "签约首付 50%，上线验收后支付 50%。", 3);
            addClause(template.getId(), "其他", "定制开发",
                    "本报价中定制开发项按需求确认后的工作量评估，超出范围另行报价。", 4);
        }
    }

    private Long createTemplate(String name, String type, String title, String issuer, int validityDays) {
        QuotationTemplate template = new QuotationTemplate();
        template.setName(name);
        template.setType(type);
        template.setTitle(title);
        template.setIssuer(issuer);
        template.setDefaultValidityDays(validityDays);
        template.setDefaultDeliveryPeriod("30 个工作日");
        template.setInvoiceType("增值税专用发票");
        template.setStatus("PUBLISHED");
        template.setCreatedAt(LocalDateTime.now());
        templateMapper.insert(template);
        return template.getId();
    }

    /**
     * 定制开发类明细项：业务上是区间报价，这里落一个默认基准单价（区间中位数），
     * 避免出现 unitPrice = null 导致生成 0 元报价；实际价格在报价单里按需覆盖并留痕。
     */
    private void createCustomItem(Long policyId, String itemKey, String itemName, String description,
                                  String defaultPrice, String priceRange, String taxRate, int sortOrder) {
        QuotationPolicyItem item = createItem(policyId, "定制开发", "定制开发", itemKey, itemName, description,
                priceRange + "（默认按基准价，按实际需求调整）", 0, defaultPrice, taxRate, "按模块收取", "模块", sortOrder);
        item.setCostPrice(new BigDecimal(defaultPrice).multiply(new BigDecimal("0.55")).setScale(2, java.math.RoundingMode.HALF_UP));
        itemMapper.updateById(item);
    }

    private void addClause(Long templateId, String section, String title, String content, int sort) {
        QuotationTemplateClause clause = new QuotationTemplateClause();
        clause.setTemplateId(templateId);
        clause.setSection(section);
        clause.setTitle(title);
        clause.setContent(content);
        clause.setIsEnabled(1);
        clause.setSortOrder(sort);
        clause.setCreatedAt(LocalDateTime.now());
        clauseMapper.insert(clause);
    }

    // ==================== SAAS 全渠道 ====================
    private void initSaasPolicy(LocalDate effectiveDate, String taxMode) {
        QuotationPolicy policy = createPolicy("SaaS全渠道报价策略(" + ("TAX_INCLUDED".equals(taxMode) ? "含税" : "未税") + ")",
                "SAAS", taxMode, effectiveDate);
        Long pid = policy.getId();
        int sort = 0;

        // 产品模块
        createItem(pid, "产品模块", "SaaS产品", "SAAS-CORE",
                "云鹿-全渠道版SaaS产品", "提供全渠道会员管理、订单管理、营销管理等核心功能模块的SaaS服务",
                "200,000元/年", 1, "200000", "0.06", "按年收取", "年", sort++);

        // 定制开发：取区间中位数作为默认报价基准，实际按需求评估后在报价单里覆盖（需填改价原因）
        createCustomItem(pid, "CUSTOM-BI", "BI报表定制开发",
                "根据客户需求定制BI看板、数据报表及数据导出功能",
                "60000", "20,000-100,000元/项", "0.06", sort++);
        createCustomItem(pid, "CUSTOM-TAG", "标签体系定制开发",
                "客户标签体系搭建、标签规则配置、标签计算引擎开发",
                "50000", "20,000-80,000元/项", "0.06", sort++);
        createCustomItem(pid, "CUSTOM-FUNC", "系统功能定制开发",
                "个性化功能模块开发，包括但不限于特殊业务流程、第三方系统对接等",
                "90000", "30,000-150,000元/项", "0.06", sort++);
        createCustomItem(pid, "CUSTOM-MKT", "营销模型定制开发",
                "智能推荐算法、营销自动化策略模型、用户画像模型等定制开发",
                "125000", "50,000-200,000元/项", "0.06", sort++);

        // 会员通对接
        initMembershipIntegrationItems(pid, sort);
    }

    // ==================== 私有化全渠道 ====================
    private void initPrivateDeploymentPolicy(LocalDate effectiveDate, String taxMode) {
        QuotationPolicy policy = createPolicy("私有化全渠道报价策略(" + ("TAX_INCLUDED".equals(taxMode) ? "含税" : "未税") + ")",
                "PRIVATE_DEPLOYMENT", taxMode, effectiveDate);
        Long pid = policy.getId();
        int sort = 0;
        String licenseTaxRate = "0.13";
        String serviceTaxRate = "0.06";

        // 产品模块
        createItem(pid, "产品模块", "CDP", "PRIV-CDP",
                "客户数据平台(CDP)", "私有化部署的客户数据平台，支持多渠道数据接入、客户画像、标签管理等",
                "180,000元/套（含首年标准运维）", 1, "180000", licenseTaxRate, "按模块收取", "模块", sort++);
        createItem(pid, "产品模块", "Loyalty", "PRIV-LOYALTY",
                "忠诚度管理(Loyalty)", "积分、等级、权益等忠诚度管理体系",
                "50,000元/套", 0, "50000", licenseTaxRate, "按模块收取", "模块", sort++);
        createItem(pid, "产品模块", "MA", "PRIV-MA",
                "营销自动化(MA)", "营销活动管理、自动化营销流程、消息触达等",
                "120,000元/套", 0, "120000", licenseTaxRate, "按模块收取", "模块", sort++);
        createItem(pid, "产品模块", "BI", "PRIV-BI",
                "商业智能(BI)", "数据分析看板、报表系统、数据导出等",
                "50,000元/套", 0, "50000", licenseTaxRate, "按模块收取", "模块", sort++);

        // 运维服务：按产品模块总价的比例计费，首年赠送（只计入次年及以后）
        createPercentItem(pid, "运维服务", "OPS-BASIC",
                "基础运维服务", "系统监控、故障处理、安全补丁、技术支持（5×8）",
                "产品模块总价的20%/年（首年赠送）", 1, "产品模块", "0.20", serviceTaxRate, 1, 1, sort++);
        createPercentItem(pid, "运维服务", "OPS-UPGRADE",
                "产品升级服务", "版本升级、功能更新、兼容性适配",
                "产品模块总价的8%/年", 0, "产品模块", "0.08", serviceTaxRate, 0, 0, sort++);

        // 定制开发：取区间中位数作为默认报价基准，实际按需求评估后在报价单里覆盖（需填改价原因）
        createCustomItem(pid, "PRIV-CUSTOM-BI", "BI报表定制开发",
                "根据客户需求定制BI看板、数据报表及数据导出功能",
                "60,000", "20,000-100,000元/项", serviceTaxRate, sort++);
        createCustomItem(pid, "PRIV-CUSTOM-TAG", "标签体系定制开发",
                "客户标签体系搭建、标签规则配置、标签计算引擎开发",
                "50,000", "20,000-80,000元/项", serviceTaxRate, sort++);
        createCustomItem(pid, "PRIV-CUSTOM-FUNC", "系统功能定制开发",
                "个性化功能模块开发，包括但不限于特殊业务流程、第三方系统对接等",
                "90,000", "30,000-150,000元/项", serviceTaxRate, sort++);
        createCustomItem(pid, "PRIV-CUSTOM-MKT", "营销模型定制开发",
                "智能推荐算法、营销自动化策略模型、用户画像模型等定制开发",
                "125,000", "50,000-200,000元/项", serviceTaxRate, sort++);

        // 会员通对接
        initMembershipIntegrationItems(pid, sort);
    }

    // ==================== 会员通 ====================
    private void initMembershipPolicy(LocalDate effectiveDate, String taxMode) {
        QuotationPolicy policy = createPolicy("会员通独立报价策略(" + ("TAX_INCLUDED".equals(taxMode) ? "含税" : "未税") + ")",
                "MEMBERSHIP", taxMode, effectiveDate);
        Long pid = policy.getId();
        initMembershipIntegrationItems(pid, 0);
    }

    // ==================== 会员通对接明细（共用） ====================
    private void initMembershipIntegrationItems(Long policyId, int startSort) {
        Long pid = policyId;
        int sort = startSort;

        String[][] platforms = {
            {"天猫", "TMALL"},
            {"京东", "JD"},
            {"抖音", "DOUYIN"},
            {"拼多多", "PDD"},
            {"小红书", "XHS"},
            {"微信小程序", "WX-MINI"},
            {"美团", "MEITUAN"},
            {"饿了么", "ELEME"},
            {"快手", "KUAISHOU"},
            {"唯品会", "VIP"},
            {"苏宁", "SUNING"}
        };

        for (String[] platform : platforms) {
            String platformName = platform[0];
            String platformCode = platform[1];

            createItem(pid, "会员通对接", platformName + "对接", "INTEG-" + platformCode + "-TECH",
                    platformName + "技术对接", "完成与" + platformName + "平台的会员系统技术对接，包括会员数据同步、积分互通、等级映射等",
                    "5,300元/次", 0, "5300", "0.06", "按次收取", "次", sort++);

            createItem(pid, "会员通对接", platformName + "对接", "INTEG-" + platformCode + "-ORDER",
                    platformName + "订单对接", "完成与" + platformName + "平台的订单系统对接，包括订单同步、状态回传、售后处理等",
                    "2,120元/次", 0, "2120", "0.06", "按次收取", "次", sort++);

            createItem(pid, "会员通对接", platformName + "对接", "INTEG-" + platformCode + "-LOYALTY",
                    platformName + "忠诚度管理对接", "完成与" + platformName + "平台的忠诚度体系对接，包括积分规则同步、权益互通等",
                    "10,000元/次", 0, "10000", "0.06", "按次收取", "次", sort++);
        }
    }
}