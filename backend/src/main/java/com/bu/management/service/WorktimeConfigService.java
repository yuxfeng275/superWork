package com.bu.management.service;

import com.bu.management.config.WorktimeProperties;
import com.bu.management.config.WorktimeRuntimeConfig;
import com.bu.management.entity.Connector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 工时系统运行时配置：连接参数唯一来源是连接器注册表（code=worktime），
 * 环境变量作为兜底（未在页面上配置时生效）。页面配置入口统一在「连接器管理」。
 *
 * @author BU Team
 */
@Service
@RequiredArgsConstructor
public class WorktimeConfigService {

    private final ConnectorRegistryService registryService;
    private final WorktimeProperties environment;

    public WorktimeRuntimeConfig getRuntimeConfig() {
        Connector connector = registryService.findByCode(ConnectorRegistryService.CODE_WORKTIME).orElse(null);
        if (connector == null) {
            return new WorktimeRuntimeConfig(
                    environment.isEnabled(),
                    environment.getBaseUrl(),
                    environment.getEmployeeNo(),
                    environment.getPassword(),
                    StringUtils.hasText(environment.getEmployeeNo()) ? "ENVIRONMENT" : "NONE",
                    null,
                    null,
                    null
            );
        }
        String employeeNo = environment.getEmployeeNo();
        String password = environment.getPassword();
        String source = StringUtils.hasText(employeeNo) ? "ENVIRONMENT" : "NONE";
        if (StringUtils.hasText(connector.getEncryptedUsername())) {
            employeeNo = registryService.credential(connector, "username");
            source = "PAGE";
        }
        if (StringUtils.hasText(connector.getEncryptedPassword())) {
            password = registryService.credential(connector, "password");
        }
        return new WorktimeRuntimeConfig(
                Integer.valueOf(1).equals(connector.getEnabled()),
                StringUtils.hasText(connector.getBaseUrl()) ? connector.getBaseUrl() : environment.getBaseUrl(),
                employeeNo,
                password,
                source,
                connector.getLastTestedAt(),
                connector.getLastTestStatus(),
                connector.getLastTestMessage()
        );
    }
}
