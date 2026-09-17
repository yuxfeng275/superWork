package com.bu.management.config;

import com.bu.management.entity.QuotationPolicy;
import com.bu.management.entity.QuotationPolicyItem;
import com.bu.management.mapper.QuotationPolicyItemMapper;
import com.bu.management.mapper.QuotationPolicyMapper;
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

    @Override
    public void run(String... args) {
        Long count = policyMapper.selectCount(null);
        if (count != null && count > 0) {
            log.info("Quotation policies already initialized, skipping seed data.");
            return;
        }
        log.info("Initializing quotation policy seed data...");
        initPolicies();
        log.info("Quotation policy seed data initialized successfully.");
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
        item.setSortOrder(sortOrder);
        item.setCreatedAt(LocalDateTime.now());
        itemMapper.insert(item);
        return item;
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

        // 定制开发
        createItem(pid, "定制开发", "BI报表", "CUSTOM-BI",
                "BI报表定制开发", "根据客户需求定制BI看板、数据报表及数据导出功能",
                "20,000-100,000元/项（视需求复杂度）", 0, null, null, "按模块收取", "模块", sort++);
        createItem(pid, "定制开发", "标签体系", "CUSTOM-TAG",
                "标签体系定制开发", "客户标签体系搭建、标签规则配置、标签计算引擎开发",
                "20,000-80,000元/项（视需求复杂度）", 0, null, null, "按模块收取", "模块", sort++);
        createItem(pid, "定制开发", "系统功能", "CUSTOM-FUNC",
                "系统功能定制开发", "个性化功能模块开发，包括但不限于特殊业务流程、第三方系统对接等",
                "30,000-150,000元/项（视需求复杂度）", 0, null, null, "按模块收取", "模块", sort++);
        createItem(pid, "定制开发", "营销模型", "CUSTOM-MKT",
                "营销模型定制开发", "智能推荐算法、营销自动化策略模型、用户画像模型等定制开发",
                "50,000-200,000元/项（视需求复杂度）", 0, null, null, "按模块收取", "模块", sort++);

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

        // 运维服务
        createItem(pid, "运维服务", "基础运维", "OPS-BASIC",
                "基础运维服务", "系统监控、故障处理、安全补丁、技术支持（5×8）",
                "产品模块总价的20%/年（首年赠送）", 1, null, serviceTaxRate, "按年收取", "年", sort++);
        createItem(pid, "运维服务", "产品升级", "OPS-UPGRADE",
                "产品升级服务", "版本升级、功能更新、兼容性适配",
                "产品模块总价的8%/年", 0, null, serviceTaxRate, "按年收取", "年", sort++);

        // 定制开发
        createItem(pid, "定制开发", "BI报表", "PRIV-CUSTOM-BI",
                "BI报表定制开发", "根据客户需求定制BI看板、数据报表及数据导出功能",
                "20,000-100,000元/项（视需求复杂度）", 0, null, null, "按模块收取", "模块", sort++);
        createItem(pid, "定制开发", "标签体系", "PRIV-CUSTOM-TAG",
                "标签体系定制开发", "客户标签体系搭建、标签规则配置、标签计算引擎开发",
                "20,000-80,000元/项（视需求复杂度）", 0, null, null, "按模块收取", "模块", sort++);
        createItem(pid, "定制开发", "系统功能", "PRIV-CUSTOM-FUNC",
                "系统功能定制开发", "个性化功能模块开发，包括但不限于特殊业务流程、第三方系统对接等",
                "30,000-150,000元/项（视需求复杂度）", 0, null, null, "按模块收取", "模块", sort++);
        createItem(pid, "定制开发", "营销模型", "PRIV-CUSTOM-MKT",
                "营销模型定制开发", "智能推荐算法、营销自动化策略模型、用户画像模型等定制开发",
                "50,000-200,000元/项（视需求复杂度）", 0, null, null, "按模块收取", "模块", sort++);

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