package com.bu.management.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 商机 AI 教练的 LLM 生成线程池。
 *
 * <p>为什么不复用邮件/会议池：
 * <ul>
 *   <li>单次教练生成要调一次大模型，10~30s 才返回，塞进 emailTaskExecutor 会饿死邮件同步；</li>
 *   <li>会议池（meetingTaskExecutor）是给几十分钟的转写用的，队列被长任务占满后教练永远排不上；</li>
 *   <li>教练是<b>可丢建议</b>：并发上限给 2、队列 50，排满了直接拒绝入队（调用方 catch 后不影响回复落库）。</li>
 * </ul>
 *
 * @author BU Team
 * @since 2026-12-01
 */
@Configuration
public class OpportunityTaskConfig {

    @Bean("opportunityTaskExecutor")
    public Executor opportunityTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("coach-");
        executor.initialize();
        return executor;
    }
}
