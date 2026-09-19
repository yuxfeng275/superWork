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
    void rejectsBlankSlug() {
        assertThatThrownBy(() -> YuqueMcpClient.parseSheetDoc(" "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("汇总表文档标识无效");
    }
}
