package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.AiModel;
import com.bu.management.entity.Connector;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.mapper.AiModelMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * AI 模型管理：模型注册表（模型名 / 用途 / 默认）的唯一管理入口。
 * 连接器提供地址与凭据，模型表决定「用哪个模型、给谁用」；
 * AI 助手可选模型、助手运行参数、邮件摘要与周报纪要模型都从这里解析。
 *
 * @author BU Team
 * @since 2026-09-17
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelConfigService {

    /** 历史会话里的 GLM 提供方标识，统一归一到连接器编码 glm。 */
    public static final String LEGACY_PROVIDER_ZHIPU = "zhipu";
    public static final String PROVIDER_GLM = "glm";
    public static final String PROVIDER_DEEPSEEK = "deepseek";

    private final AiModelMapper mapper;
    private final ConnectorRegistryService registryService;

    /** 管理端列表项：附带提供方（连接器）就绪状态与名称。 */
    public record ModelView(Long id, String providerCode, String providerName, boolean providerReady,
            String model, String displayName, boolean assistantEnabled, boolean digestEnabled,
            boolean isDefault, boolean enabled, int sortOrder) {}

    public record ModelSaveRequest(String providerCode, String model, String displayName,
            Boolean assistantEnabled, Boolean digestEnabled, Boolean isDefault,
            Boolean enabled, Integer sortOrder) {}

    /** AI 助手可选模型（前端下拉）。 */
    public record ModelOption(String provider, String model, String label) {}

    /** 传给侧车的模型参数：地址与凭据来自连接器，模型名来自本表。 */
    public record ModelConfig(String baseUrl, String model, String apiKey) {}

    /** 摘要 / 周报纪要使用的模型。 */
    public record DigestModel(String providerCode, String baseUrl, String model, String apiKey) {}

    // ==================== 管理端 ====================

    public List<ModelView> list() {
        return ordered().stream().map(this::toView).toList();
    }

    @Transactional
    public ModelView create(ModelSaveRequest request) {
        AiModel entity = new AiModel();
        applyRequest(entity, request);
        requireProvider(entity.getProviderCode());
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
            requireProvider(request.providerCode().trim());
            patch.setProviderCode(request.providerCode().trim());
        }
        if (StringUtils.hasText(request.model())) patch.setModel(request.model().trim());
        if (StringUtils.hasText(request.displayName())) patch.setDisplayName(request.displayName().trim());
        if (request.assistantEnabled() != null) patch.setAssistantEnabled(request.assistantEnabled() ? 1 : 0);
        if (request.digestEnabled() != null) patch.setDigestEnabled(request.digestEnabled() ? 1 : 0);
        if (request.enabled() != null) patch.setEnabled(request.enabled() ? 1 : 0);
        if (request.sortOrder() != null) patch.setSortOrder(request.sortOrder());
        if (Boolean.TRUE.equals(request.isDefault())) patch.setIsDefault(1);
        if (Boolean.FALSE.equals(request.isDefault())) patch.setIsDefault(0);
        // 唯一性：改了模型名或提供方时校验不冲突
        String provider = StringUtils.hasText(patch.getProviderCode()) ? patch.getProviderCode() : stored.getProviderCode();
        String model = StringUtils.hasText(patch.getModel()) ? patch.getModel() : stored.getModel();
        if (!provider.equals(stored.getProviderCode()) || !model.equals(stored.getModel())) {
            if (mapper.selectCount(new LambdaQueryWrapper<AiModel>()
                    .eq(AiModel::getProviderCode, provider)
                    .eq(AiModel::getModel, model)
                    .ne(AiModel::getId, id)) > 0) {
                throw new IllegalArgumentException("该提供方下模型已存在：" + model);
            }
        }
        mapper.updateById(patch);
        if (Integer.valueOf(1).equals(patch.getIsDefault())) clearOtherDefaults(id);
        return toView(require(id));
    }

    @Transactional
    public void delete(Long id) {
        require(id);
        mapper.deleteById(id);
    }

    // ==================== 运行期 ====================

    /**
     * AI 助手可选模型：启用 + 勾选「助手可用」+ 提供方连接器就绪。
     */
    public List<ModelOption> listAvailableModels() {
        List<ModelOption> options = new ArrayList<>();
        for (AiModel entity : ordered()) {
            if (!available(entity, true)) continue;
            options.add(new ModelOption(entity.getProviderCode(), entity.getModel(),
                    StringUtils.hasText(entity.getDisplayName()) ? entity.getDisplayName() : entity.getModel()));
        }
        return options;
    }

    /**
     * 解析助手运行的模型参数：优先会话指定的模型，其次默认模型，最后该提供方排序最前的模型。
     *
     * @param provider 提供方（连接器编码，兼容历史值 zhipu）
     * @param model    会话上记录的模型名，可为空
     */
    public ModelConfig resolveModelConfig(String provider, String model) {
        String code = normalizeProvider(provider);
        Connector connector = registryService.findByCode(code)
                .orElseThrow(() -> new IllegalStateException("模型提供方不存在：" + code));
        AiModel entity = findForAssistant(code, model)
                .orElseThrow(() -> new IllegalStateException(
                        "AI 模型未配置或未启用，请在「模型管理」中配置 " + code + " 的可用模型"));
        if (!"READY".equals(registryService.status(connector))) {
            throw new IllegalStateException("模型提供方连接未就绪，请在「连接器管理」补全 " + code + " 的连接配置");
        }
        return new ModelConfig(connector.getBaseUrl(), entity.getModel(),
                registryService.credential(connector, "token"));
    }

    /** 摘要 / 周报纪要使用的模型（第一条启用且提供方就绪的行）。 */
    public Optional<DigestModel> digestModel() {
        for (AiModel entity : ordered()) {
            if (!available(entity, false)) continue;
            Connector connector = registryService.findByCode(entity.getProviderCode()).orElse(null);
            if (connector == null) continue;
            return Optional.of(new DigestModel(entity.getProviderCode(), connector.getBaseUrl(),
                    entity.getModel(), registryService.credential(connector, "token")));
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

    /** 提供方编码归一：历史 zhipu → glm（连接器编码）。 */
    public String normalizeProvider(String provider) {
        if (!StringUtils.hasText(provider)) return PROVIDER_GLM;
        String value = provider.trim().toLowerCase(Locale.ROOT);
        return LEGACY_PROVIDER_ZHIPU.equals(value) ? PROVIDER_GLM : value;
    }

    // ==================== 内部 ====================

    /** 已配置（启用 + 用途勾选），不要求提供方连接就绪。 */
    private boolean configured(AiModel entity, boolean assistant) {
        if (!Integer.valueOf(1).equals(entity.getEnabled())) return false;
        return assistant
                ? Integer.valueOf(1).equals(entity.getAssistantEnabled())
                : Integer.valueOf(1).equals(entity.getDigestEnabled());
    }

    /** 可用 = 已配置 + 提供方连接器就绪。 */
    private boolean available(AiModel entity, boolean assistant) {
        if (!configured(entity, assistant)) return false;
        Connector connector = registryService.findByCode(entity.getProviderCode()).orElse(null);
        return connector != null && "READY".equals(registryService.status(connector));
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

    private void applyRequest(AiModel entity, ModelSaveRequest request) {
        if (!StringUtils.hasText(request.providerCode())) {
            throw new IllegalArgumentException("提供方不能为空");
        }
        if (!StringUtils.hasText(request.model())) {
            throw new IllegalArgumentException("模型名不能为空");
        }
        entity.setProviderCode(request.providerCode().trim());
        entity.setModel(request.model().trim());
        entity.setDisplayName(StringUtils.hasText(request.displayName())
                ? request.displayName().trim() : request.model().trim());
        entity.setAssistantEnabled(Boolean.TRUE.equals(request.assistantEnabled()) ? 1 : 0);
        entity.setDigestEnabled(Boolean.TRUE.equals(request.digestEnabled()) ? 1 : 0);
        entity.setIsDefault(Boolean.TRUE.equals(request.isDefault()) ? 1 : 0);
        entity.setEnabled(Boolean.FALSE.equals(request.enabled()) ? 0 : 1);
        entity.setSortOrder(request.sortOrder() == null ? 100 : request.sortOrder());
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

    private void requireProvider(String providerCode) {
        String code = normalizeProvider(providerCode);
        registryService.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("提供方连接器不存在：" + providerCode));
    }

    private AiModel require(Long id) {
        AiModel entity = mapper.selectById(id);
        if (entity == null) {
            throw new ResourceNotFoundException("模型不存在");
        }
        return entity;
    }

    private ModelView toView(AiModel entity) {
        Connector connector = registryService.findByCode(entity.getProviderCode()).orElse(null);
        return new ModelView(entity.getId(), entity.getProviderCode(),
                connector == null ? entity.getProviderCode() : connector.getName(),
                connector != null && "READY".equals(registryService.status(connector)),
                entity.getModel(), entity.getDisplayName(),
                Integer.valueOf(1).equals(entity.getAssistantEnabled()),
                Integer.valueOf(1).equals(entity.getDigestEnabled()),
                Integer.valueOf(1).equals(entity.getIsDefault()),
                Integer.valueOf(1).equals(entity.getEnabled()),
                entity.getSortOrder() == null ? 100 : entity.getSortOrder());
    }
}
