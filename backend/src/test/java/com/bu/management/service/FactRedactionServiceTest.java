package com.bu.management.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 脱敏五类命中 + 业务编号不误伤 + 开关生效。
 *
 * <p>样本说明：身份证 {@code 11010519491231002X} 与银行卡 {@code 4111111111111111}
 * 分别过 GB 11643 校验位与 Luhn，换成任何一个数字就会变成「不该脱」的反例。
 */
class FactRedactionServiceTest {

    private FactRedactionService service;

    @BeforeEach
    void setUp() {
        service = new FactRedactionService();
    }

    @Test
    @DisplayName("手机号：11 位号段替换成 [手机号]")
    void redactsPhone() {
        assertThat(service.redact("联系人电话 13812345678，今天回电"))
                .isEqualTo("联系人电话 [手机号]，今天回电");
    }

    @Test
    @DisplayName("身份证：校验位通过才脱")
    void redactsIdCardOnlyWhenChecksumPasses() {
        assertThat(service.redact("身份证 11010519491231002X 已核验"))
                .isEqualTo("身份证 [身份证] 已核验");
        // 末位校验不过：按普通数字放过，宁漏不误伤
        assertThat(service.redact("编号 110105194912310021 是别的号"))
                .contains("110105194912310021");
    }

    @Test
    @DisplayName("银行卡：Luhn 通过才脱")
    void redactsBankCardOnlyWhenLuhnPasses() {
        assertThat(service.redact("打款卡号 4111111111111111 招商"))
                .isEqualTo("打款卡号 [银行卡] 招商");
        assertThat(service.redact("卡号 4111111111111112 打错了")).contains("4111111111111112");
    }

    @Test
    @DisplayName("邮箱：局部保留首尾、中间打 *")
    void masksEmailLocalPart() {
        assertThat(service.redact("抄送 zhangsan@example.com 知悉"))
                .isEqualTo("抄送 z******n@example.com 知悉");
        // 局部太短，留下等于没脱，整体记占位符
        assertThat(service.redact("发给 wu@example.com")).isEqualTo("发给 [邮箱]");
    }

    @Test
    @DisplayName("金额：只在薪酬关键词 30 字内命中「数字+元」")
    void redactsAmountOnlyNearSalaryKeywords() {
        assertThat(service.redact("本月工资 12000 元已发")).isEqualTo("本月工资 [金额]已发");
        assertThat(service.redact("年薪 25 万元，按 payroll 发放")).contains("[金额]");
        // 没有薪酬关键词的金额不脱
        assertThat(service.redact("合同总价 12000 元，分两期")).isEqualTo("合同总价 12000 元，分两期");
    }

    @Test
    @DisplayName("订单号 / 项目编码 / 合同编号一律不脱")
    void keepsBusinessCodes() {
        assertThat(service.redact("订单号 13812345678 已发货")).isEqualTo("订单号 13812345678 已发货");
        assertThat(service.redact("合同编号 4111111111111111 已归档"))
                .isEqualTo("合同编号 4111111111111111 已归档");
        assertThat(service.redact("项目编码 PRJ20261108001 对应里程碑"))
                .isEqualTo("项目编码 PRJ20261108001 对应里程碑");
    }

    @Test
    @DisplayName("总开关关掉：原样透传")
    void disabledButterflyKeepsText() {
        ReflectionTestUtils.setField(service, "enabled", false);
        assertThat(service.redact("电话 13812345678，身份证 11010519491231002X"))
                .isEqualTo("电话 13812345678，身份证 11010519491231002X");
    }

    @Test
    @DisplayName("单类开关关掉：只影响这一类")
    void singleSwitchOffOnlyAffectsItsOwnCategory() {
        ReflectionTestUtils.setField(service, "phoneEnabled", false);
        assertThat(service.redact("电话 13812345678，卡号 4111111111111111"))
                .isEqualTo("电话 13812345678，卡号 [银行卡]");
    }

    @Test
    @DisplayName("空值不炸：null 与空串原样返回")
    void handlesEmptyInput() {
        assertThat(service.redact(null)).isNull();
        assertThat(service.redact("")).isEmpty();
        assertThat(service.redact("没有敏感数据的普通说明")).isEqualTo("没有敏感数据的普通说明");
    }
}
