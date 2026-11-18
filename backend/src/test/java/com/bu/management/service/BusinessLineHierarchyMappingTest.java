package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

/** 工时系统层级业务线名到本地业务线的回落口径。 */
class BusinessLineHierarchyMappingTest {
    @Test
    void mapsKnownGroups() {
        assertThat(BusinessLineProfitService.hierarchyBusinessLineName("定制-品类定制-品类定制"))
                .isEqualTo("全渠道云鹿定制");
        assertThat(BusinessLineProfitService.hierarchyBusinessLineName("精准-电商-精准投放"))
                .isEqualTo("全域精准");
        assertThat(BusinessLineProfitService.hierarchyBusinessLineName("全域-全渠道-会员通"))
                .isEqualTo("会员通");
        assertThat(BusinessLineProfitService.hierarchyBusinessLineName("全域-全渠道-全域云鹿Saas"))
                .isEqualTo("全渠道云鹿SAAS");
        assertThat(BusinessLineProfitService.hierarchyBusinessLineName("软件-工具-订阅"))
                .isNull();
    }
}
