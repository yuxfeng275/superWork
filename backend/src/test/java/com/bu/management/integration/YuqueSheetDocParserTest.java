package com.bu.management.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class YuqueSheetDocParserTest {

    @Test
    void splitsThreePartSlugIntoNamespaceAndDoc() {
        String[] parts = YuqueMcpClient.parseSheetDoc("staff-qvc012/mghdgg/tyavbayo9ir7tyrk");
        assertThat(parts).containsExactly("staff-qvc012/mghdgg", "tyavbayo9ir7tyrk");
    }

    @Test
    void splitsTwoPartSlug() {
        assertThat(YuqueMcpClient.parseSheetDoc("mghdgg/tyavbayo9ir7tyrk"))
                .containsExactly("mghdgg", "tyavbayo9ir7tyrk");
    }

    @Test
    void explain404AsksToFixTokenOrSlug() {
        String message = YuqueMcpClient.explainSheetReadFailure(
                "staff-qvc012/mghdgg",
                "tyavbayo9ir7tyrk",
                new IllegalStateException("语雀接口调用失败(404) /repos/staff-qvc012/mghdgg/docs/tyavbayo9ir7tyrk"));
        assertThat(message).contains("无权访问");
        assertThat(message).contains("sheet.doc-slug");
        assertThat(message).contains("staff-qvc012/mghdgg/tyavbayo9ir7tyrk");
    }

    @Test
    void rejectsBlankSlug() {
        assertThatThrownBy(() -> YuqueMcpClient.parseSheetDoc(" "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("汇总表文档标识无效");
    }
}
