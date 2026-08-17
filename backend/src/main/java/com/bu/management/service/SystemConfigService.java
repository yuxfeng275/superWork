package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.config.EmailCredentialCipher;
import com.bu.management.dto.SystemConfigGroupRequest;
import com.bu.management.entity.SystemConfigItem;
import com.bu.management.exception.ResourceNotFoundException;
import com.bu.management.mapper.SystemConfigItemMapper;
import com.bu.management.vo.SystemConfigGroupSummary;
import com.bu.management.vo.SystemConfigGroupView;
import com.bu.management.vo.SystemConfigItemView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SystemConfigService {
    private static final Set<String> VALUE_TYPES = Set.of("STRING", "PASSWORD", "BOOLEAN", "URL", "NUMBER");

    private final SystemConfigItemMapper mapper;
    private final EmailCredentialCipher cipher;

    public List<SystemConfigGroupSummary> listGroups() {
        Map<String, List<SystemConfigItem>> groups = loadItems(null).stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        SystemConfigItem::getGroupCode, LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        return groups.values().stream().map(items -> {
            SystemConfigItem first = items.get(0);
            int configured = (int) items.stream().filter(this::isConfigured).count();
            return new SystemConfigGroupSummary(first.getGroupCode(), first.getGroupName(),
                    first.getGroupDescription(), items.size(), configured);
        }).toList();
    }

    public SystemConfigGroupView getGroup(String groupCode) {
        List<SystemConfigItem> items = loadItems(groupCode);
        if (items.isEmpty()) throw new ResourceNotFoundException("配置组不存在");
        SystemConfigItem first = items.get(0);
        return new SystemConfigGroupView(first.getGroupCode(), first.getGroupName(),
                first.getGroupDescription(), items.stream().map(this::toView).toList());
    }

    public String getValue(String groupCode, String key, String fallback) {
        SystemConfigItem item = find(groupCode, key);
        if (item == null || !StringUtils.hasText(item.getConfigValue())) return fallback;
        return sensitive(item) ? cipher.decrypt(item.getConfigValue()) : item.getConfigValue();
    }

    public boolean getBoolean(String groupCode, String key, boolean fallback) {
        String value = getValue(groupCode, key, String.valueOf(fallback));
        return Boolean.parseBoolean(value);
    }

    @Transactional
    public SystemConfigGroupView saveGroup(String groupCode, SystemConfigGroupRequest request, Long userId) {
        List<SystemConfigItem> items = loadItems(groupCode);
        if (items.isEmpty()) throw new ResourceNotFoundException("配置组不存在");
        Map<String, SystemConfigItem> byKey = items.stream().collect(
                java.util.stream.Collectors.toMap(SystemConfigItem::getConfigKey, item -> item));
        for (String key : request.getValues().keySet()) {
            if (!byKey.containsKey(key)) throw new IllegalStateException("未知配置项：" + key);
        }
        for (SystemConfigItem item : items) {
            if (!request.getValues().containsKey(item.getConfigKey())) continue;
            String input = request.getValues().get(item.getConfigKey());
            if (sensitive(item) && !StringUtils.hasText(input)) continue;
            String normalized = normalize(item, input);
            if (required(item) && !StringUtils.hasText(normalized)) {
                throw new IllegalStateException(item.getConfigName() + "不能为空");
            }
            item.setConfigValue(sensitive(item) && StringUtils.hasText(normalized)
                    ? cipher.encrypt(normalized) : normalized);
            item.setUpdatedBy(userId);
            item.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(item);
        }
        validateGroup(groupCode, items);
        return getGroup(groupCode);
    }

    private void validateGroup(String groupCode, List<SystemConfigItem> items) {
        if (!EmailIntegrationConfigService.GROUP.equals(groupCode)) return;
        Map<String, SystemConfigItem> values = items.stream().collect(
                java.util.stream.Collectors.toMap(SystemConfigItem::getConfigKey, item -> item));
        if (booleanValue(values.get("deepseek.enabled")) && !isConfigured(values.get("deepseek.api-key"))) {
            throw new IllegalStateException("启用 DeepSeek 前必须配置 API Key");
        }
        if (booleanValue(values.get("wecom.enabled"))
                && (!isConfigured(values.get("wecom.corp-id"))
                || !isConfigured(values.get("wecom.agent-id"))
                || !isConfigured(values.get("wecom.secret")))) {
            throw new IllegalStateException("启用企业微信前必须配置 CorpId、AgentId 和 Secret");
        }
    }

    private boolean booleanValue(SystemConfigItem item) {
        return item != null && Boolean.parseBoolean(item.getConfigValue());
    }

    private List<SystemConfigItem> loadItems(String groupCode) {
        LambdaQueryWrapper<SystemConfigItem> query = new LambdaQueryWrapper<SystemConfigItem>()
                .eq(SystemConfigItem::getStatus, 1)
                .orderByAsc(SystemConfigItem::getGroupCode)
                .orderByAsc(SystemConfigItem::getSortOrder)
                .orderByAsc(SystemConfigItem::getId);
        if (StringUtils.hasText(groupCode)) query.eq(SystemConfigItem::getGroupCode, groupCode);
        return mapper.selectList(query);
    }

    private SystemConfigItem find(String groupCode, String key) {
        return mapper.selectOne(new LambdaQueryWrapper<SystemConfigItem>()
                .eq(SystemConfigItem::getGroupCode, groupCode)
                .eq(SystemConfigItem::getConfigKey, key)
                .eq(SystemConfigItem::getStatus, 1));
    }

    private SystemConfigItemView toView(SystemConfigItem item) {
        boolean configured = isConfigured(item);
        return new SystemConfigItemView(item.getConfigKey(), item.getConfigName(),
                item.getConfigDescription(), item.getValueType(),
                sensitive(item) ? null : item.getConfigValue(), sensitive(item), configured,
                required(item), item.getSortOrder() == null ? 0 : item.getSortOrder());
    }

    private String normalize(SystemConfigItem item, String input) {
        String value = input == null ? null : input.trim();
        String type = item.getValueType();
        if (!VALUE_TYPES.contains(type)) throw new IllegalStateException("不支持的配置类型：" + type);
        if ("BOOLEAN".equals(type) && StringUtils.hasText(value)
                && !Set.of("true", "false").contains(value.toLowerCase())) {
            throw new IllegalStateException(item.getConfigName() + "必须是 true 或 false");
        }
        if ("NUMBER".equals(type) && StringUtils.hasText(value)) {
            try { Long.parseLong(value); } catch (NumberFormatException exception) {
                throw new IllegalStateException(item.getConfigName() + "必须是数字");
            }
        }
        if ("URL".equals(type) && StringUtils.hasText(value)
                && !(value.startsWith("http://") || value.startsWith("https://"))) {
            throw new IllegalStateException(item.getConfigName() + "必须是 HTTP(S) 地址");
        }
        return value;
    }

    private boolean isConfigured(SystemConfigItem item) {
        return StringUtils.hasText(item.getConfigValue());
    }

    private boolean sensitive(SystemConfigItem item) {
        return Integer.valueOf(1).equals(item.getIsSensitive());
    }

    private boolean required(SystemConfigItem item) {
        return Integer.valueOf(1).equals(item.getIsRequired());
    }
}
