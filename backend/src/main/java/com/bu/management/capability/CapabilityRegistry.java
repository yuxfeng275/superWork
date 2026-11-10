package com.bu.management.capability;

import com.bu.management.annotation.Capability;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 能力注册表：启动期扫一遍 {@code @Capability} Bean，编译成不可变的能力目录。
 *
 * <p>刻意不用 AOP / 不搞动态代理：能力发现是纯扫描，一跳就能查到某个 id 落在哪个 Bean，
 * 排查「为什么模型看不到这个工具」时不需要去猜代理链。执行期的门禁、超时、审计
 * 全部集中在 {@link CapabilityGateway} 一个地方，也不依赖切面。
 *
 * <p>启动期强校验（任何一条不满足就抛 {@link IllegalStateException}，让服务起不来）：
 * <ul>
 *   <li>标了 {@code @Capability} 的 Bean 必须实现 {@link CapabilityHandler}</li>
 *   <li>能力 id 不能为空、不能重复</li>
 *   <li>{@code inputSchema} 必须是能解析的 JSON 对象</li>
 * </ul>
 * 这些错误放线上只会变成「能力存在但永远不会被调用」，属于最难查的一类问题。
 *
 * @author BU Team
 * @since 2026-11-20
 */
@Slf4j
@Component
public class CapabilityRegistry {

    /**
     * 注册项：描述符 + 执行者。
     *
     * @param descriptor 能力描述符
     * @param handler    能力实现
     */
    public record Registered(CapabilityDescriptor descriptor, CapabilityHandler handler) {}

    private final ApplicationContext context;
    private final ObjectMapper objectMapper;

    /** 启动期一次写入，之后只读；用 volatile 保证多线程读到的都是完整视图。 */
    private volatile Map<String, Registered> capabilities = Map.of();

    public CapabilityRegistry(ApplicationContext context, ObjectMapper objectMapper) {
        this.context = context;
        this.objectMapper = objectMapper;
    }

    /**
     * 扫描并注册。异常是刻意的：注册表残缺比服务起不来更危险。
     */
    @PostConstruct
    public void init() {
        Map<String, Object> beans = context.getBeansWithAnnotation(Capability.class);
        Map<String, Registered> built = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            String beanName = entry.getKey();
            Object bean = entry.getValue();
            Capability annotation = resolveAnnotation(beanName, bean);
            if (annotation == null) {
                errors.add("Bean[" + beanName + "] 找不到 @Capability 注解元数据");
                continue;
            }
            if (!(bean instanceof CapabilityHandler handler)) {
                errors.add("Bean[" + beanName + "] 标注了 @Capability 但未实现 CapabilityHandler，"
                        + "契约不完整无法执行");
                continue;
            }
            String id = annotation.id();
            if (!StringUtils.hasText(id)) {
                errors.add("Bean[" + beanName + "] 的能力 id 为空");
                continue;
            }
            if (built.containsKey(id)) {
                errors.add("能力 id 重复：" + id + " 同时出现在 Bean["
                        + built.get(id).descriptor().beanName() + "] 与 Bean[" + beanName + "]");
                continue;
            }
            JsonNode schema = parseSchema(beanName, id, annotation.inputSchema(), errors);
            if (schema == null) {
                continue;
            }
            CapabilityDescriptor descriptor = new CapabilityDescriptor(
                    id,
                    annotation.kind(),
                    annotation.risk(),
                    annotation.title(),
                    annotation.whenToUse(),
                    schema,
                    annotation.timeoutMs(),
                    annotation.idempotent(),
                    beanName);
            built.put(id, new Registered(descriptor, handler));
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException("能力注册失败（共 " + errors.size() + " 项）："
                    + String.join("；", errors));
        }
        this.capabilities = Map.copyOf(built);
        log.info("能力注册表初始化完成：共 {} 项，其中助手工具 {} 项",
                capabilities.size(), describeForAgent().size());
    }

    /** 按 id 查描述符；找不到返回 empty，由网关转成 UNKNOWN_CAPABILITY。 */
    public Optional<CapabilityDescriptor> find(String id) {
        if (!StringUtils.hasText(id)) {
            return Optional.empty();
        }
        Registered registered = capabilities.get(id);
        return registered == null ? Optional.empty() : Optional.of(registered.descriptor());
    }

    /**
     * 按 id 查完整注册项（含执行者）。网关执行用；
     * 对外暴露描述符走 {@link #find(String)}，别让调用方拿到 Handler 自己绕过门禁。
     */
    public Optional<Registered> lookup(String id) {
        if (!StringUtils.hasText(id)) {
            return Optional.empty();
        }
        return Optional.ofNullable(capabilities.get(id));
    }

    /** 按类别列描述符，按 id 排序保证输出稳定（模型侧 prompt 缓存友好）。kind 为 null 时返回全部。 */
    public List<CapabilityDescriptor> list(CapabilityKind kind) {
        return capabilities.values().stream()
                .map(Registered::descriptor)
                .filter(d -> kind == null || d.kind() == kind)
                .sorted(Comparator.comparing(CapabilityDescriptor::id))
                .toList();
    }

    /** 下发给助手的能力清单：只含 TOOL 类，JOB / REMOTE / CONNECTOR 不进模型可选范围。 */
    public List<CapabilityDescriptor> describeForAgent() {
        return list(CapabilityKind.TOOL);
    }

    /** 已注册能力总数，供自检 / 监控打点用。 */
    public int size() {
        return capabilities.size();
    }

    /** 注解可能在 CGLIB 代理上，优先让容器按 Bean 名解析，失败再退回原始类型。 */
    private Capability resolveAnnotation(String beanName, Object bean) {
        Capability annotation = context.findAnnotationOnBean(beanName, Capability.class);
        if (annotation != null) {
            return annotation;
        }
        return bean.getClass().getAnnotation(Capability.class);
    }

    /** 解析 JSON Schema：必须是 JSON 对象，否则记错误并返回 null。 */
    private JsonNode parseSchema(String beanName, String id, String raw, List<String> errors) {
        String text = StringUtils.hasText(raw) ? raw : "{}";
        JsonNode schema;
        try {
            schema = objectMapper.readTree(text);
        } catch (Exception e) {
            errors.add("能力 " + id + "（Bean[" + beanName + "]）的 inputSchema 不是合法 JSON："
                    + e.getMessage());
            return null;
        }
        if (schema == null || !schema.isObject()) {
            errors.add("能力 " + id + "（Bean[" + beanName + "]）的 inputSchema 必须是 JSON 对象");
            return null;
        }
        return schema;
    }
}
