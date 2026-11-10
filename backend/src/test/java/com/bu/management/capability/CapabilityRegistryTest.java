package com.bu.management.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bu.management.annotation.Capability;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * 注册表启动期校验的回归测试：合法注册、重复 id、非法 schema 三种情况。
 */
class CapabilityRegistryTest {

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    /** 起一个只含给定 Bean 的容器，手工调 init()——注册表不在容器内也能测。 */
    private CapabilityRegistry boot(Class<?>... beanClasses) {
        context = new AnnotationConfigApplicationContext();
        context.register(beanClasses);
        context.refresh();
        return new CapabilityRegistry(context, new ObjectMapper());
    }

    @Test
    @DisplayName("合法 Bean 注册成功：描述符字段与 schema 都能取到")
    void registersValidBean() {
        CapabilityRegistry registry = boot(EchoCapability.class);

        registry.init();

        assertThat(registry.size()).isEqualTo(1);
        Optional<CapabilityDescriptor> found = registry.find("demo.echo");
        assertThat(found).isPresent();
        CapabilityDescriptor descriptor = found.get();
        assertThat(descriptor.kind()).isEqualTo(CapabilityKind.TOOL);
        assertThat(descriptor.risk()).isEqualTo(CapabilityRisk.READ);
        assertThat(descriptor.title()).isEqualTo("回声");
        assertThat(descriptor.whenToUse()).isEqualTo("联调用");
        assertThat(descriptor.timeoutMs()).isEqualTo(1234);
        // 嵌套测试类注册后 Bean 名为 "capabilityRegistryTest.EchoCapability"，这里只断言承载者是哪个类
        assertThat(descriptor.beanName()).contains("EchoCapability");
        assertThat(descriptor.inputSchema().isObject()).isTrue();
        assertThat(descriptor.inputSchema().path("type").asText()).isEqualTo("object");
        assertThat(registry.list(CapabilityKind.TOOL)).hasSize(1);
        assertThat(registry.list(CapabilityKind.JOB)).isEmpty();
        assertThat(registry.describeForAgent()).extracting(CapabilityDescriptor::id)
                .containsExactly("demo.echo");
        assertThat(registry.find("不存在的能力")).isEmpty();
    }

    @Test
    @DisplayName("重复 id：启动直接失败，并指出两个 Bean")
    void failsOnDuplicateId() {
        CapabilityRegistry registry = boot(EchoCapability.class, AnotherEchoCapability.class);

        assertThatThrownBy(registry::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("能力 id 重复")
                .hasMessageContaining("demo.echo");
    }

    @Test
    @DisplayName("schema 非法：启动直接失败，并指出能力 id")
    void failsOnInvalidSchema() {
        CapabilityRegistry registry = boot(BrokenSchemaCapability.class);

        assertThatThrownBy(registry::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inputSchema 不是合法 JSON")
                .hasMessageContaining("demo.broken");
    }

    @Test
    @DisplayName("非对象 schema 同样拒绝")
    void failsOnNonObjectSchema() {
        CapabilityRegistry registry = boot(ArraySchemaCapability.class);

        assertThatThrownBy(registry::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inputSchema 必须是 JSON 对象");
    }

    @Test
    @DisplayName("标了注解但没实现 Handler：启动失败并列出 beanName")
    void failsOnBeanWithoutHandler() {
        CapabilityRegistry registry = boot(NotAHandler.class);

        assertThatThrownBy(registry::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未实现 CapabilityHandler")
                .hasMessageContaining("NotAHandler");
    }

    @Capability(
            id = "demo.echo",
            kind = CapabilityKind.TOOL,
            risk = CapabilityRisk.READ,
            title = "回声",
            whenToUse = "联调用",
            inputSchema = "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}",
            timeoutMs = 1234)
    static class EchoCapability implements CapabilityHandler {
        @Override
        public CapabilityResult execute(JsonNode input, CapabilityContext ctx) {
            return CapabilityResult.ok("{\"echo\":true}");
        }
    }

    @Capability(id = "demo.echo", title = "重复回声")
    static class AnotherEchoCapability implements CapabilityHandler {
        @Override
        public CapabilityResult execute(JsonNode input, CapabilityContext ctx) {
            return CapabilityResult.ok("{}");
        }
    }

    @Capability(id = "demo.broken", inputSchema = "not-a-json")
    static class BrokenSchemaCapability implements CapabilityHandler {
        @Override
        public CapabilityResult execute(JsonNode input, CapabilityContext ctx) {
            return CapabilityResult.ok("{}");
        }
    }

    @Capability(id = "demo.array", inputSchema = "[1,2,3]")
    static class ArraySchemaCapability implements CapabilityHandler {
        @Override
        public CapabilityResult execute(JsonNode input, CapabilityContext ctx) {
            return CapabilityResult.ok("{}");
        }
    }

    @Capability(id = "demo.notHandler")
    static class NotAHandler {
        public String hello() {
            return "hi";
        }
    }
}
