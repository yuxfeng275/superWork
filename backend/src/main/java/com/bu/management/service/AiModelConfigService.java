package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.bu.management.config.EmailCredentialCipher;
import com.bu.management.entity.AiModel;
import com.bu.management.entity.Connector;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.mapper.AiModelMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * AI 模型管理：模型注册表的唯一入口。
 * 模型可自带协议 / 地址 / API Key（官方、中转站、自建 OpenAI 兼容）；
 * 未填时回落同名连接器。连接器不再是模型的前置条件。
 *
 * @author BU Team
 * @since 2026-09-17
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelConfigService {

    /** 历史会话里的 GLM 提供方标识，统一归一到 glm。 */
    public static final String LEGACY_PROVIDER_ZHIPU = "zhipu";
    public static final String PROVIDER_GLM = "glm";
    public static final String PROVIDER_DEEPSEEK = "deepseek";
    public static final String PROVIDER_TYPESAFE = "typesafe";
    public static final String PROTOCOL_OPENAI = "openai-compat";
    public static final String PROTOCOL_TYPESAFE = "typesafe";

    private static final Pattern PROVIDER_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]{1,31}$");

    private final AiModelMapper mapper;
    private final ConnectorRegistryService registryService;
    private final EmailCredentialCipher cipher;
    /** 在线拉取模型列表 / 测试连接用的 HTTP 客户端（字段初始化，不进构造器，免改测试装配）。 */
    private final ObjectMapper httpJson = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    /** 管理端列表项。 */
    public record ModelView(Long id, String providerCode, String providerName, boolean providerReady,
            String apiProtocol, String model, String displayName, String baseUrl, boolean apiKeyConfigured,
            boolean assistantEnabled, boolean digestEnabled, boolean decisionEnabled, boolean isDefault,
            boolean enabled, int sortOrder) {}

    public record ModelSaveRequest(String providerCode, String apiProtocol, String model, String displayName,
            String baseUrl, String apiKey, Boolean clearApiKey, Boolean assistantEnabled, Boolean digestEnabled,
            Boolean decisionEnabled, Boolean isDefault, Boolean enabled, Integer sortOrder) {}

    /** AI 助手可选模型（前端下拉）。 */
    public record ModelOption(String provider, String model, String label) {}

    /** 传给侧车 / 摘要客户端的运行参数。 */
    public record ModelConfig(String baseUrl, String model, String apiKey) {}

    /** 摘要 / 周报纪要使用的模型。 */
    public record DigestModel(String providerCode, String baseUrl, String model, String apiKey) {}

    /** 决策层（Jev / System One）。 */
    public record DecisionModel(String providerCode, String baseUrl, String model, String apiKey) {}

    private record Endpoint(String baseUrl, String apiKey) {}

    /** 拉取远程模型列表：id 优先（用已存模型的地址/Key），否则用表单里的地址/Key/提供方回落。 */
    public record RemoteModelsRequest(Long id, String providerCode, String baseUrl, String apiKey) {}

    /** 模型连接测试：id 优先；否则按表单内容组装探测目标。 */
    public record ModelTestRequest(Long id, String providerCode, String apiProtocol, String model,
            String baseUrl, String apiKey) {}

    /** 测试结果。 */
    public record ModelTestResult(boolean success, String message, Long latencyMs) {}

    // ==================== 管理端 ====================

    public List<ModelView> list() {
        return ordered().stream().map(this::toView).toList();
    }

    @Transactional
    public ModelView create(ModelSaveRequest request) {
        AiModel entity = new AiModel();
        applyRequest(entity, request, true);
        if (mapper.selectCount(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getProviderCode, entity.getProviderCode())
                .eq(AiModel::getModel, entity.getModel())) > 0) {
            throw new IllegalArgumentException("该提供方下模型已存在：" + entity.getModel());
        }
        mapper.insert(entity);
        if (Integer.valueOf(1).equals(entity.getIsDefault())) clearOtherDefaults(entity.getId());
        return toView(require(entity.getId()));
    }

    @Transactional
    public ModelView update(Long id, ModelSaveRequest request) {
        AiModel stored = require(id);
        AiModel patch = new AiModel();
        patch.setId(id);
        if (StringUtils.hasText(request.providerCode())) {
            patch.setProviderCode(normalizeProvider(request.providerCode()));
        }
        if (StringUtils.hasText(request.apiProtocol())) {
            patch.setApiProtocol(normalizeProtocol(request.apiProtocol(), null, false));
        }
        if (StringUtils.hasText(request.model())) patch.setModel(request.model().trim());
        if (StringUtils.hasText(request.displayName())) patch.setDisplayName(request.displayName().trim());
        if (request.baseUrl() != null) {
            patch.setBaseUrl(trimUrl(request.baseUrl()));
        }
        if (Boolean.TRUE.equals(request.clearApiKey())) {
            patch.setEncryptedApiKey(null);
        } else if (StringUtils.hasText(request.apiKey())) {
            patch.setEncryptedApiKey(cipher.encrypt(request.apiKey().trim()));
        }
        if (request.assistantEnabled() != null) patch.setAssistantEnabled(request.assistantEnabled() ? 1 : 0);
        if (request.digestEnabled() != null) patch.setDigestEnabled(request.digestEnabled() ? 1 : 0);
        if (request.decisionEnabled() != null) patch.setDecisionEnabled(request.decisionEnabled() ? 1 : 0);
        if (request.enabled() != null) patch.setEnabled(request.enabled() ? 1 : 0);
        if (request.sortOrder() != null) patch.setSortOrder(request.sortOrder());
        if (Boolean.TRUE.equals(request.isDefault())) patch.setIsDefault(1);
        if (Boolean.FALSE.equals(request.isDefault())) patch.setIsDefault(0);

        String provider = StringUtils.hasText(patch.getProviderCode())
                ? patch.getProviderCode() : stored.getProviderCode();
        String model = StringUtils.hasText(patch.getModel()) ? patch.getModel() : stored.getModel();
        if (!provider.equals(stored.getProviderCode()) || !model.equals(stored.getModel())) {
            if (mapper.selectCount(new LambdaQueryWrapper<AiModel>()
                    .eq(AiModel::getProviderCode, provider)
                    .eq(AiModel::getModel, model)
                    .ne(AiModel::getId, id)) > 0) {
                throw new IllegalArgumentException("该提供方下模型已存在：" + model);
            }
        }
        boolean decision = request.decisionEnabled() != null
                ? Boolean.TRUE.equals(request.decisionEnabled())
                : Integer.valueOf(1).equals(stored.getDecisionEnabled());
        String protocol = StringUtils.hasText(patch.getApiProtocol())
                ? patch.getApiProtocol() : stored.getApiProtocol();
        patch.setApiProtocol(normalizeProtocol(protocol, provider, decision));
        if (PROTOCOL_TYPESAFE.equals(patch.getApiProtocol())) {
            patch.setAssistantEnabled(0);
            patch.setDigestEnabled(0);
            patch.setIsDefault(0);
        }
        mapper.updateById(patch);
        if (Boolean.TRUE.equals(request.clearApiKey())
                || (request.baseUrl() != null && !StringUtils.hasText(trimUrl(request.baseUrl())))) {
            LambdaUpdateWrapper<AiModel> clear = new LambdaUpdateWrapper<AiModel>().eq(AiModel::getId, id);
            if (Boolean.TRUE.equals(request.clearApiKey())) {
                clear.set(AiModel::getEncryptedApiKey, null);
            }
            if (request.baseUrl() != null && !StringUtils.hasText(trimUrl(request.baseUrl()))) {
                clear.set(AiModel::getBaseUrl, null);
            }
            mapper.update(null, clear);
        }
        if (Integer.valueOf(1).equals(patch.getIsDefault())) clearOtherDefaults(id);
        return toView(require(id));
    }

    @Transactional
    public void delete(Long id) {
        require(id);
        mapper.deleteById(id);
    }

    // ==================== 在线探测（模型列表 / 连接测试） ====================

    /**
     * 拉取 OpenAI 兼容端点的模型清单（GET {baseUrl}/models）。
     * 第三方中转站不知道模型 ID 时用：填好地址和 Key 后一键拉取可选模型。
     */
    public List<String> fetchRemoteModels(RemoteModelsRequest request) {
        Endpoint endpoint = resolveProbeEndpoint(request.id(), request.providerCode(),
                request.baseUrl(), request.apiKey());
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(endpoint.baseUrl() + "/models"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + endpoint.apiKey())
                .GET()
                .build();
        JsonNode body = send(httpRequest, "拉取模型列表");
        JsonNode data = body.path("data");
        if (!data.isArray()) {
            throw new IllegalStateException("模型列表响应无效：缺少 data 数组");
        }
        TreeSet<String> ids = new TreeSet<>();
        for (JsonNode item : data) {
            String id = item.path("id").asText(null);
            if (StringUtils.hasText(id)) ids.add(id.trim());
        }
        if (ids.isEmpty()) {
            throw new IllegalStateException("提供方返回了空的模型列表");
        }
        return List.copyOf(ids);
    }

    /**
     * 测试模型连通性：先发 GET /models 验证地址与凭据，再发一条最小化对话请求验证模型可用。
     * TypeSafe 决策模型不走 OpenAI 兼容协议，不支持在线测试。
     */
    public ModelTestResult testModel(ModelTestRequest request) {
        AiModel stored = request.id() != null ? require(request.id()) : null;
        String protocol = StringUtils.hasText(request.apiProtocol()) ? request.apiProtocol()
                : stored != null ? stored.getApiProtocol() : PROTOCOL_OPENAI;
        if (PROTOCOL_TYPESAFE.equals(protocol)) {
            throw new IllegalArgumentException("TypeSafe 决策模型不走 OpenAI 兼容协议，请在连接器管理测试");
        }
        String model = StringUtils.hasText(request.model()) ? request.model().trim()
                : stored != null ? stored.getModel() : null;
        if (!StringUtils.hasText(model)) {
            throw new IllegalArgumentException("请先填写模型名再测试");
        }
        Endpoint endpoint = stored != null ? resolveEndpoint(stored)
                : resolveProbeEndpoint(null, request.providerCode(), request.baseUrl(), request.apiKey());
        long start = System.nanoTime();
        try {
            Map<String, Object> payload = Map.of(
                    "model", model,
                    "max_tokens", 1,
                    "messages", List.of(Map.of("role", "user", "content", "ping")));
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint.baseUrl() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + endpoint.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(httpJson.writeValueAsString(payload)))
                    .build();
            JsonNode body = send(httpRequest, "模型测试");
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            if (body.path("choices").isArray() && !body.path("choices").isEmpty()) {
                return new ModelTestResult(true, "对话接口正常", latencyMs);
            }
            return new ModelTestResult(true, "接口连通，但响应缺少 choices（兼容实现，可按通）", latencyMs);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("模型测试失败：" + e.getMessage());
        }
    }

    /** 组装探测目标：已存模型优先；否则用表单地址/Key，缺省时回落同名连接器凭据。 */
    private Endpoint resolveProbeEndpoint(Long id, String providerCode, String baseUrl, String apiKey) {
        if (id != null) {
            return resolveEndpoint(require(id));
        }
        if (!StringUtils.hasText(baseUrl) && !StringUtils.hasText(providerCode)) {
            throw new IllegalArgumentException("请先填写接口地址（或提供方编码以回落连接器凭据）");
        }
        AiModel probe = new AiModel();
        probe.setProviderCode(StringUtils.hasText(providerCode)
                ? normalizeProvider(providerCode) : PROVIDER_GLM);
        probe.setBaseUrl(trimUrl(baseUrl));
        if (StringUtils.hasText(apiKey)) {
            probe.setEncryptedApiKey(cipher.encrypt(apiKey.trim()));
        }
        return resolveEndpoint(probe);
    }

    /** 发送请求并把非 2xx 响应翻译成可读错误。 */
    private JsonNode send(HttpRequest request, String action) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(action + "被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException(action + "失败：无法连接接口地址（" + e.getMessage() + "）", e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String detail = response.body() == null ? "" : response.body().replaceAll("\\s+", " ").trim();
            if (detail.length() > 200) detail = detail.substring(0, 200);
            throw new IllegalStateException(action + "失败（HTTP " + response.statusCode() + "）"
                    + (detail.isEmpty() ? "" : "：" + detail));
        }
        try {
            return httpJson.readTree(response.body());
        } catch (Exception e) {
            throw new IllegalStateException(action + "失败：响应不是合法 JSON", e);
        }
    }

    // ==================== 运行期 ====================

    /**
     * AI 助手可选模型：启用 + 勾选「助手可用」+ 接入就绪（自带地址或连接器就绪）。
     */
    public List<ModelOption> listAvailableModels() {
        List<AiModel> available = ordered().stream()
                .filter(entity -> available(entity, true))
                .filter(entity -> !isDecisionProtocol(entity))
                .toList();
        Map<String, Long> labelCounts = available.stream().collect(java.util.stream.Collectors.groupingBy(
                this::labelOf, java.util.stream.Collectors.counting()));
        List<ModelOption> options = new ArrayList<>();
        for (AiModel entity : available) {
            String label = labelOf(entity);
            if (labelCounts.getOrDefault(label, 0L) > 1L) {
                label = label + "（" + providerName(entity.getProviderCode()) + "）";
            }
            options.add(new ModelOption(entity.getProviderCode(), entity.getModel(), label));
        }
        return options;
    }

    /**
     * 解析助手运行的模型参数：优先会话指定的模型，其次默认模型，最后该提供方排序最前的模型。
     */
    public ModelConfig resolveModelConfig(String provider, String model) {
        String code = normalizeProvider(provider);
        AiModel entity = findForAssistant(code, model)
                .orElseThrow(() -> new IllegalStateException(
                        "AI 模型未配置或未启用，请在「模型管理」中配置 " + code + " 的可用模型"));
        Endpoint endpoint = resolveEndpoint(entity);
        return new ModelConfig(endpoint.baseUrl(), entity.getModel(), endpoint.apiKey());
    }

    /** 摘要 / 周报纪要使用的模型（启用 + 勾选摘要 + 接入就绪，按排序取第一条）。 */
    public Optional<DigestModel> digestModel() {
        for (AiModel entity : orderedBySort()) {
            if (!available(entity, false) || isDecisionProtocol(entity)) continue;
            try {
                Endpoint endpoint = resolveEndpoint(entity);
                return Optional.of(new DigestModel(entity.getProviderCode(), endpoint.baseUrl(),
                        entity.getModel(), endpoint.apiKey()));
            } catch (IllegalStateException ignored) {
                // 下一条
            }
        }
        return Optional.empty();
    }

    /** 决策层模型：启用 + 勾选决策 + 接入就绪，按排序取第一条。 */
    public Optional<DecisionModel> decisionModel() {
        for (AiModel entity : orderedBySort()) {
            if (!Integer.valueOf(1).equals(entity.getEnabled())) continue;
            if (!Integer.valueOf(1).equals(entity.getDecisionEnabled())) continue;
            if (!endpointReady(entity)) continue;
            try {
                Endpoint endpoint = resolveEndpoint(entity);
                return Optional.of(new DecisionModel(entity.getProviderCode(), endpoint.baseUrl(),
                        entity.getModel(), endpoint.apiKey()));
            } catch (IllegalStateException ignored) {
                // 下一条
            }
        }
        return Optional.empty();
    }

    /** 默认模型（AI 助手未指定模型时使用）。 */
    public Optional<ModelOption> defaultModel() {
        return ordered().stream()
                .filter(entity -> available(entity, true) && Integer.valueOf(1).equals(entity.getIsDefault()))
                .map(entity -> new ModelOption(entity.getProviderCode(), entity.getModel(),
                        StringUtils.hasText(entity.getDisplayName()) ? entity.getDisplayName() : entity.getModel()))
                .findFirst();
    }

    /** 提供方展示名（找不到同名连接器时返回编码本身）。 */
    public String providerDisplayName(String providerCode) {
        return providerName(providerCode);
    }

    /** 提供方编码归一：历史 zhipu → glm。 */
    public String normalizeProvider(String provider) {
        if (!StringUtils.hasText(provider)) return PROVIDER_GLM;
        String value = provider.trim().toLowerCase(Locale.ROOT);
        return LEGACY_PROVIDER_ZHIPU.equals(value) ? PROVIDER_GLM : value;
    }

    // ==================== 内部 ====================

    private boolean configured(AiModel entity, boolean assistant) {
        if (!Integer.valueOf(1).equals(entity.getEnabled())) return false;
        return assistant
                ? Integer.valueOf(1).equals(entity.getAssistantEnabled())
                : Integer.valueOf(1).equals(entity.getDigestEnabled());
    }

    private boolean available(AiModel entity, boolean assistant) {
        return configured(entity, assistant) && endpointReady(entity);
    }

    private boolean isDecisionProtocol(AiModel entity) {
        return PROTOCOL_TYPESAFE.equals(entity.getApiProtocol())
                || PROVIDER_TYPESAFE.equals(entity.getProviderCode());
    }

    private boolean endpointReady(AiModel entity) {
        boolean ownUrl = StringUtils.hasText(entity.getBaseUrl());
        boolean ownKey = StringUtils.hasText(entity.getEncryptedApiKey());
        if (ownUrl && ownKey) return true;
        Connector connector = registryService.findByCode(entity.getProviderCode()).orElse(null);
        if (connector == null || !"READY".equals(registryService.status(connector))) return false;
        return true;
    }

    private Endpoint resolveEndpoint(AiModel entity) {
        boolean ownUrl = StringUtils.hasText(entity.getBaseUrl());
        boolean ownKey = StringUtils.hasText(entity.getEncryptedApiKey());
        if (ownUrl && ownKey) {
            return new Endpoint(trimUrl(entity.getBaseUrl()), cipher.decrypt(entity.getEncryptedApiKey()));
        }
        Connector connector = registryService.findByCode(entity.getProviderCode()).orElse(null);
        if (connector == null) {
            throw new IllegalStateException("模型未配置接口地址或 API Key，请在「模型管理」补全接口地址");
        }
        if (!ownUrl && !"READY".equals(registryService.status(connector))) {
            throw new IllegalStateException("模型提供方连接未就绪，请在「连接器管理」补全 "
                    + entity.getProviderCode() + " 的连接配置，或在「模型管理」直接填写接口地址");
        }
        String url = ownUrl ? trimUrl(entity.getBaseUrl()) : trimUrl(connector.getBaseUrl());
        String key = ownKey
                ? cipher.decrypt(entity.getEncryptedApiKey())
                : registryService.credential(connector, "token");
        if (!StringUtils.hasText(url) || !StringUtils.hasText(key)) {
            throw new IllegalStateException("模型未配置接口地址或 API Key，请在「模型管理」补全接口地址");
        }
        return new Endpoint(url, key);
    }

    private String labelOf(AiModel entity) {
        return StringUtils.hasText(entity.getDisplayName()) ? entity.getDisplayName() : entity.getModel();
    }

    private String providerName(String providerCode) {
        return registryService.findByCode(providerCode)
                .map(Connector::getName)
                .filter(StringUtils::hasText)
                .orElse(providerCode);
    }

    private Optional<AiModel> findForAssistant(String providerCode, String model) {
        List<AiModel> candidates = ordered().stream()
                .filter(entity -> providerCode.equals(entity.getProviderCode()))
                .filter(entity -> configured(entity, true))
                .toList();
        if (StringUtils.hasText(model)) {
            Optional<AiModel> matched = candidates.stream()
                    .filter(entity -> model.trim().equals(entity.getModel()))
                    .findFirst();
            if (matched.isPresent()) return matched;
        }
        Optional<AiModel> fallback = candidates.stream()
                .filter(entity -> Integer.valueOf(1).equals(entity.getIsDefault()))
                .findFirst();
        return fallback.isPresent() ? fallback : candidates.stream().findFirst();
    }

    private List<AiModel> ordered() {
        return mapper.selectList(new LambdaQueryWrapper<AiModel>()
                .orderByDesc(AiModel::getIsDefault)
                .orderByAsc(AiModel::getSortOrder)
                .orderByAsc(AiModel::getId));
    }

    private List<AiModel> orderedBySort() {
        return mapper.selectList(new LambdaQueryWrapper<AiModel>()
                .orderByAsc(AiModel::getSortOrder)
                .orderByAsc(AiModel::getId));
    }

    private void applyRequest(AiModel entity, ModelSaveRequest request, boolean creating) {
        if (!StringUtils.hasText(request.providerCode())) {
            throw new IllegalArgumentException("提供方不能为空");
        }
        if (!StringUtils.hasText(request.model())) {
            throw new IllegalArgumentException("模型名不能为空");
        }
        String provider = normalizeProvider(request.providerCode());
        if (!PROVIDER_PATTERN.matcher(provider).matches()) {
            throw new IllegalArgumentException("提供方编码仅允许小写字母开头的字母数字与下划线");
        }
        boolean decision = Boolean.TRUE.equals(request.decisionEnabled());
        entity.setProviderCode(provider);
        entity.setApiProtocol(normalizeProtocol(request.apiProtocol(), provider, decision));
        entity.setModel(request.model().trim());
        entity.setDisplayName(StringUtils.hasText(request.displayName())
                ? request.displayName().trim() : request.model().trim());
        entity.setBaseUrl(trimUrl(request.baseUrl()));
        if (StringUtils.hasText(request.apiKey())) {
            entity.setEncryptedApiKey(cipher.encrypt(request.apiKey().trim()));
        } else if (creating) {
            entity.setEncryptedApiKey(null);
        }
        entity.setAssistantEnabled(Boolean.TRUE.equals(request.assistantEnabled()) ? 1 : 0);
        entity.setDigestEnabled(Boolean.TRUE.equals(request.digestEnabled()) ? 1 : 0);
        entity.setDecisionEnabled(decision ? 1 : 0);
        entity.setIsDefault(Boolean.TRUE.equals(request.isDefault()) ? 1 : 0);
        entity.setEnabled(Boolean.FALSE.equals(request.enabled()) ? 0 : 1);
        entity.setSortOrder(request.sortOrder() == null ? 100 : request.sortOrder());
        if (PROTOCOL_TYPESAFE.equals(entity.getApiProtocol())) {
            entity.setAssistantEnabled(0);
            entity.setDigestEnabled(0);
            entity.setIsDefault(0);
        }
    }

    private String normalizeProtocol(String raw, String provider, boolean decision) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (decision || PROVIDER_TYPESAFE.equals(provider) || PROTOCOL_TYPESAFE.equals(value)) {
            return PROTOCOL_TYPESAFE;
        }
        return PROTOCOL_OPENAI;
    }

    private String trimUrl(String value) {
        if (!StringUtils.hasText(value)) return null;
        String url = value.trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private void clearOtherDefaults(Long keepId) {
        for (AiModel entity : mapper.selectList(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getIsDefault, 1)
                .ne(AiModel::getId, keepId))) {
            AiModel patch = new AiModel();
            patch.setId(entity.getId());
            patch.setIsDefault(0);
            mapper.updateById(patch);
        }
    }

    private AiModel require(Long id) {
        AiModel entity = mapper.selectById(id);
        if (entity == null) {
            throw new ResourceNotFoundException("模型不存在");
        }
        return entity;
    }

    private ModelView toView(AiModel entity) {
        return new ModelView(entity.getId(), entity.getProviderCode(),
                providerName(entity.getProviderCode()),
                endpointReady(entity),
                StringUtils.hasText(entity.getApiProtocol()) ? entity.getApiProtocol() : PROTOCOL_OPENAI,
                entity.getModel(), entity.getDisplayName(),
                entity.getBaseUrl(),
                StringUtils.hasText(entity.getEncryptedApiKey()),
                Integer.valueOf(1).equals(entity.getAssistantEnabled()),
                Integer.valueOf(1).equals(entity.getDigestEnabled()),
                Integer.valueOf(1).equals(entity.getDecisionEnabled()),
                Integer.valueOf(1).equals(entity.getIsDefault()),
                Integer.valueOf(1).equals(entity.getEnabled()),
                entity.getSortOrder() == null ? 100 : entity.getSortOrder());
    }
}
