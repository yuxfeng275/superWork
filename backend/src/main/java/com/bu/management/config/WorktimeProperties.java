package com.bu.management.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Data
@Component
@ConfigurationProperties(prefix = "worktime")
public class WorktimeProperties {
    private boolean enabled;
    private String baseUrl = "https://worktime.lucidata.cn";
    private String employeeNo;
    private String password;
    private String configEncryptionKey;
    /** 合同明细每日同步 cron，默认每天 06:30（工时系统 02:00 从 OA 同步之后） */
    private String contractSyncCron = "0 30 6 * * *";
    /** 工时/成本月度同步检查 cron，默认每天 07:00 */
    private String monthlySyncCron = "0 0 7 * * *";

    public boolean isConfigured() {
        return enabled && StringUtils.hasText(baseUrl)
                && StringUtils.hasText(employeeNo) && StringUtils.hasText(password);
    }
}
