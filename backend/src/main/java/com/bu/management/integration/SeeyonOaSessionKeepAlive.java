package com.bu.management.integration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * OA 网页会话保活：每 10 分钟轻量 ping 一次（仅当已授权），
 * 让 JSESSIONID 不因空闲过期；失效时清除会话，页面提示重新授权。
 *
 * @author BU Team
 * @since 2026-09-17
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeeyonOaSessionKeepAlive {

    private final SeeyonOaWebChannel webChannel;

    @Scheduled(fixedDelay = 10 * 60 * 1000, initialDelay = 60 * 1000)
    public void keepAlive() {
        try {
            webChannel.keepAlive();
        } catch (Exception e) {
            log.warn("OA 会话保活任务异常：{}", e.getMessage());
        }
    }
}
