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
 * 商机跟进问句发送器。
 *
 * <p><b>两条路由（由系统配置 {@code opportunity.followup.pilot-enabled} 控制，缺省 true）</b>：
 * <ul>
 *   <li><b>试点开关 on</b>：问句一律单发给机器人创建人，不发给商机负责人。用户 ID 取自部署配置
 *       {@code opportunity.followup.pilot-owner-user-id}（环境变量 {@code OPPORTUNITY_FOLLOWUP_PILOT_OWNER_USER_ID}
 *       可覆盖），企微 userid 取自系统配置或该用户自己的企微绑定映射；</li>
 *   <li><b>试点开关 off</b>：按问句的 {@code targetUserId}（即商机负责人）解析企微 userid，口径为
 *       系统配置 {@code opportunity.followup.wecom-userid.<userId>} → {@code email_wecom_mapping}（enabled=1）；
 *       两个都空就<b>直落站内，不猜、不回落到试点人</b>。</li>
 * </ul>
 *
 * <p>发送链（两条路由共用）：企微机器人单聊（{@code wecom-cli message aibot send}，渠道 {@code WECOM_BOT}）
 * → 企微应用消息（渠道 {@code WECOM_APP}，仅当绑定映射存在且 enabled=1）
 * → 只留站内（渠道 {@code INAPP}）；每一级都单独 try/catch 降级，<b>任何异常都不向上抛</b>，
 * 绝不影响问句台账落库。
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
    /** 试点路由开关（完整键名 {@code opportunity.followup.pilot-enabled}）：缺省 true = 保持试点行为。 */
    static final String PILOT_ENABLED_KEY = "followup.pilot-enabled";
    /** 按负责人路由时，每个用户可单独配置的企微 userid 键前缀（完整键名 {@code opportunity.followup.wecom-userid.<userId>}）。 */
    static final String OWNER_WECOM_USERID_KEY_PREFIX = "followup.wecom-userid.";

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
     * 试点路由是否开启：读系统配置 {@code opportunity.followup.pilot-enabled}，缺省/读失败一律 true。
     *
     * <p>缺省 true 是为了守住现网行为：开关不配就还是全发试点人，不会因为一个未配项把问句发错人。
     */
    public boolean pilotEnabled() {
        try {
            String value = systemConfigService.getValue(PILOT_CONFIG_GROUP, PILOT_ENABLED_KEY, "true");
            return !StringUtils.hasText(value) || Boolean.parseBoolean(value.strip());
        } catch (Exception e) {
            log.warn("商机问句试点开关读取失败，按试点行为处理: err={}", e.getMessage());
            return true;
        }
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
     * <p>接收人由试点开关决定：on 一律试点人（创建人）；off 按 {@code prompt.targetUserId}（商机负责人）解析企微
     * userid，解析不到就直落站内——<b>不回落到试点人</b>。
     *
     * <p>回写台账的 {@code channel} 与 {@code wecom_userid}（记录实际发送目标）；回写失败也只记日志。
     *
     * @return 实际渠道：WECOM_BOT / WECOM_APP / INAPP
     */
    public String send(OpportunityFollowupPrompt prompt) {
        if (prompt == null || !StringUtils.hasText(prompt.getQuestionText())) {
            return OpportunityFollowUpService.CHANNEL_INAPP;
        }
        String text = prompt.getQuestionText();

        boolean pilot = pilotEnabled();
        // 试点期：实际接收人是机器创建人；正式路由：实际接收人就是商机负责人
        Long receiverUserId = pilot ? pilotOwnerUserId : prompt.getTargetUserId();
        if (!pilot && receiverUserId == null) {
            // 负责人没映射到账号：直接站内留档，不猜、不回落试点人
            return OpportunityFollowUpService.CHANNEL_INAPP;
        }

        String wecomUserId = null;
        try {
            wecomUserId = pilot ? pilotWecomUserId() : ownerWecomUserId(receiverUserId);
        } catch (Exception e) {
            log.warn("商机问句接收人企微 userid 解析失败，转应用消息兜底: promptId={} err={}",
                    prompt.getId(), e.getMessage());
        }

        // 1) 企微机器人单聊（首选）：未授权/未安装/发送失败都会抛异常，统一落到下一级
        if (StringUtils.hasText(wecomUserId)) {
            try {
                weComCliService.sendMessage(wecomUserId, text);
                return record(prompt, OpportunityFollowUpService.CHANNEL_WECOM_BOT, wecomUserId);
            } catch (Exception e) {
                log.warn("商机问句机器人单聊失败，回退应用消息: promptId={} err={}",
                        prompt.getId(), e.getMessage());
            }
        }

        // 2) 企微应用消息：只在接收人有启用中的企微绑定映射时可用
        try {
            String boundWecomUserId = boundWecomUserId(receiverUserId);
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

    /**
     * 「商机负责人」的企微 userid：系统配置 {@code followup.wecom-userid.<userId>} 优先，其次才看绑定映射。
     *
     * <p>两个都取不到返回 null，由调用方直落站内（不猜、不回落到试点人）。
     */
    private String ownerWecomUserId(Long userId) {
        if (userId == null) {
            return null;
        }
        String configured = systemConfigService.getValue(PILOT_CONFIG_GROUP,
                OWNER_WECOM_USERID_KEY_PREFIX + userId, null);
        if (StringUtils.hasText(configured)) {
            return configured.strip();
        }
        return boundWecomUserId(userId);
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
