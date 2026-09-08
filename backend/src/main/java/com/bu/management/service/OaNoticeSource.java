package com.bu.management.service;

import com.bu.management.integration.SeeyonOaClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * OA 待办通知源：致远 OA 当前用户待办数量。
 * 需要身份映射（ai_connector_identity connector_code=oa）；未映射或调用失败降级为无通知。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OaNoticeSource {

    public static final String KIND = "OA_PENDING";

    private final SeeyonOaClient oaClient;
    private final AiConnectorIdentityService identityService;

    public AiNoticeService.Notice compute(Long userId, LocalDate today) {
        try {
            String memberId = identityService.resolve(userId, AiConnectorIdentityService.CONNECTOR_OA);
            if (memberId == null) {
                return null;
            }
            List<JsonNode> affairs = oaClient.listPendingAffairs();
            int mine = 0;
            for (JsonNode affair : affairs) {
                String owner = firstText(affair, "memberId", "hmemberId", "senderId", "principalId");
                if (owner != null && owner.equals(memberId)) {
                    mine++;
                }
            }
            if (mine == 0) {
                return null;
            }
            return new AiNoticeService.Notice(KIND,
                    "OA 待办提醒",
                    "你在致远 OA 有 " + mine + " 条待办事项待处理。",
                    "/ai-assistant",
                    today,
                    false);
        } catch (Exception e) {
            log.debug("OA 待办通知获取失败（降级）: {}", e.getMessage());
            return null;
        }
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (!value.isBlank()) return value;
        }
        return null;
    }
}
