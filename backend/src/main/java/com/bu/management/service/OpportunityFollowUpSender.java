package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.EmailWeComMapping;
import com.bu.management.entity.OpportunityFollowupPrompt;
import com.bu.management.integration.WeComClient;
import com.bu.management.mapper.EmailWeComMappingMapper;
import com.bu.management.mapper.OpportunityFollowupPromptMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 商机跟进问句发送器（<b>试点模式</b>）。
 *
 * <p>口径：
 * <ul>
 *   <li><b>试点期问句一律单发给机器人创建人，不发给商机负责人</b>。接收人身份不写死在逻辑里：
 *       用户 ID 取自部署配置 {@code opportunity.followup.pilot-owner-user-id}
 *       （环境变量 {@code OPPORTUNITY_FOLLOWUP_PILOT_OWNER_USER_ID} 可覆盖），
 *       企微 userid 取自系统配置或该用户自己的企微绑定映射；</li>
 *   <li>发送链优先级：企微机器人单聊（{@code wecom-cli message send}，渠道 {@code WECOM_BOT}）
 *       → 企微应用消息（渠道 {@code WECOM_APP}，仅当绑定映射存在且 enabled=1）
 *       → 只留站内（渠道 {@code INAPP}）；</li>
 *   <li>每一级都单独 try/catch 降级，<b>任何异常都不向上抛</b>，绝不影响问句台账落库。</li>
 * </ul>
 *
 * @author BU Team
 * @since 2026-11-20
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpportunityFollowUpSender {

    /** 试点配置项所在配置组（配置管理 → 系统配置）。 */
    static final String PILOT_CONFIG_GROUP = "opportunity";
    /** 试点目标企微 userid 的配置键（完整键名 {@code opportunity.followup.pilot-wecom-userid}）。 */
    static final String PILOT_WECOM_USERID_KEY = "followup.pilot-wecom-userid";

    private final WeComCliService weComCliService;
    private final WeComClient weComClient;
    private final EmailWeComMappingMapper weComMappingMapper;
    private final OpportunityFollowupPromptMapper promptMapper;
    private final SystemConfigService systemConfigService;

    /**
     * 试点期实际接收人（系统 user id）：机器人创建人。
     *
     * <p>由部署配置提供，不在业务代码里写死；正式期改为商机负责人后此处整体失效。
     */
    @Value("${opportunity.followup.pilot-owner-user-id:25}")
    Long pilotOwnerUserId;

    /** 试点期实际接收人的系统 user id（配额计数与台账都用它）。 */
    public Long pilotReceiverUserId() {
        return pilotOwnerUserId;
    }

    /**
     * 试点目标企微 userid：先读系统配置，为空再取该用户的企微绑定映射；都为空返回 null。
     */
    public String pilotWecomUserId() {
        String configured = configuredPilotWecomUserId();
        if (StringUtils.hasText(configured)) {
            return configured.strip();
        }
        return boundWecomUserId(pilotOwnerUserId);
    }

    /**
     * 发一条问句：机器人单聊 → 应用消息 → 站内。
     *
     * <p>回写台账的 {@code channel} 与 {@code wecom_userid}（记录实际发送目标：试点期一律是创建人）；
     * 回写失败也只记日志，不影响调用方。
     *
     * @return 实际渠道：WECOM_BOT / WECOM_APP / INAPP
     */
    public String send(OpportunityFollowupPrompt prompt) {
        if (prompt == null || !StringUtils.hasText(prompt.getQuestionText())) {
            return OpportunityFollowUpService.CHANNEL_INAPP;
        }
        String text = prompt.getQuestionText();

        String pilotWecomUserId = null;
        try {
            pilotWecomUserId = pilotWecomUserId();
        } catch (Exception e) {
            log.warn("商机问句试点目标解析失败，转应用消息兜底: promptId={} err={}",
                    prompt.getId(), e.getMessage());
        }

        // 1) 企微机器人单聊（首选）：未授权/未安装/发送失败都会抛异常，统一落到下一级
        if (StringUtils.hasText(pilotWecomUserId)) {
            try {
                weComCliService.sendMessage(pilotWecomUserId, text);
                return record(prompt, OpportunityFollowUpService.CHANNEL_WECOM_BOT, pilotWecomUserId);
            } catch (Exception e) {
                log.warn("商机问句机器人单聊失败，回退应用消息: promptId={} err={}",
                        prompt.getId(), e.getMessage());
            }
        }

        // 2) 企微应用消息：只在试点目标有启用中的企微绑定映射时可用
        try {
            String boundWecomUserId = boundWecomUserId(pilotOwnerUserId);
            if (StringUtils.hasText(boundWecomUserId)) {
                weComClient.pushText(boundWecomUserId, text);
                return record(prompt, OpportunityFollowUpService.CHANNEL_WECOM_APP, boundWecomUserId);
            }
        } catch (Exception e) {
            log.warn("商机问句应用消息回退失败，降级为站内: promptId={} err={}",
                    prompt.getId(), e.getMessage());
        }

        // 3) 都不可用：只留站内，不报错
        return OpportunityFollowUpService.CHANNEL_INAPP;
    }

    // ==================== 内部工具 ====================

    /** 系统配置里的试点目标 wecom userid；配置缺失或为空返回 null。 */
    private String configuredPilotWecomUserId() {
        return systemConfigService.getValue(PILOT_CONFIG_GROUP, PILOT_WECOM_USERID_KEY, null);
    }

    /** 企微 userid 口径：有映射且 enabled=1 才取值。 */
    private String boundWecomUserId(Long userId) {
        if (userId == null) {
            return null;
        }
        List<EmailWeComMapping> mappings = weComMappingMapper.selectList(
                new LambdaQueryWrapper<EmailWeComMapping>()
                        .eq(EmailWeComMapping::getOwnerUserId, userId)
                        .eq(EmailWeComMapping::getEnabled, 1)
                        .last("limit 1"));
        if (mappings == null || mappings.isEmpty()) {
            return null;
        }
        EmailWeComMapping mapping = mappings.get(0);
        if (mapping == null || mapping.getEnabled() == null || mapping.getEnabled() != 1) {
            return null;
        }
        return mapping.getWecomUserId();
    }

    /** 回写台账渠道与「实际发送目标」；回写异常只记日志。 */
    private String record(OpportunityFollowupPrompt prompt, String channel, String wecomUserId) {
        try {
            prompt.setChannel(channel);
            prompt.setWecomUserid(limit(wecomUserId, 64));
            promptMapper.updateById(prompt);
        } catch (Exception e) {
            log.warn("商机问句渠道回写失败（台账已落库，不影响）: promptId={} err={}",
                    prompt.getId(), e.getMessage());
        }
        return channel;
    }

    private String limit(String value, int max) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
