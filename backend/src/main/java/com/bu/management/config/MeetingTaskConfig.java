package com.bu.management.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 会议转写/总结任务线程池。
 * 不复用 emailTaskExecutor：转写动辄数十分钟，会饿死邮件同步。
 */
@Configuration
public class MeetingTaskConfig {

    @Bean("meetingTaskExecutor")
    public Executor meetingTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("meeting-task-");
        executor.initialize();
        return executor;
    }
}
