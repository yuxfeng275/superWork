package com.bu.management.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bu.management.entity.EmailWeComMapping;
import com.bu.management.mapper.EmailWeComMappingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 企业微信自由对话：把发送人映射到系统用户，再以该用户身份跑一轮助手。
 *
 * <p>与商机回复的边界：先由 sidecar 尝试商机问句匹配；没匹配到时才落到这里。
 * 未绑定企微的用户不猜身份，直接返回未绑定。
 *
 * @author BU Team
 * @since 2026-11-18
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeComChatService {

    private static final int MAX_TEXT = 2000;

    private final EmailWeComMappingMapper mappingMapper;
    private final AutomationRunService automationRunService;

    /**
     * 一次对话结果。
     *
     * @param matched 是否找到绑定用户并成功运行
     * @param text    助手回复（匹配成功时）
     * @param reason  未匹配/失败原因（人话）
     */
    public record Reply(boolean matched, String text, String reason) {
    }

    /** 以企微发送人身份回复一句。 */
    public Reply reply(String senderKey, String text) {
        if (!StringUtils.hasText(senderKey) || !StringUtils.hasText(text)) {
            return new Reply(false, null, "发送人或消息为空");
        }
        EmailWeComMapping mapping;
        try {
            mapping = mappingMapper.selectOne(new LambdaQueryWrapper<EmailWeComMapping>()
                    .eq(EmailWeComMapping::getWecomUserId, senderKey.strip())
                    .eq(EmailWeComMapping::getEnabled, 1)
                    .last("limit 1"));
        } catch (Exception e) {
            log.warn("企微对话：查询绑定失败 sender={}: {}", senderKey, e.getMessage());
            return new Reply(false, null, "查询企微绑定失败");
        }
        if (mapping == null || mapping.getOwnerUserId() == null) {
            return new Reply(false, null, "未绑定企业微信");
        }
        String prompt = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
        try {
            AutomationRunService.RunResult result = automationRunService.runChat(
                    mapping.getOwnerUserId(), "企微对话", prompt);
            if (!result.ok()) {
                return new Reply(false, null, result.error());
            }
            String answer = StringUtils.hasText(result.text()) ? result.text() : "我这轮没有生成回复，请换个说法。";
            return new Reply(true, answer, null);
        } catch (Exception e) {
            log.warn("企微对话运行失败 sender={}: {}", senderKey, e.getMessage());
            return new Reply(false, null, "助手暂时不可用");
        }
    }
}
