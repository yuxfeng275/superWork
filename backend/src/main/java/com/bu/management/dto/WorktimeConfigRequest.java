package com.bu.management.dto;

import lombok.Data;

@Data
public class WorktimeConfigRequest {
    private Boolean enabled;
    private String baseUrl;
    /** 登录工号；不传表示保持已有配置 */
    private String employeeNo;
    /** 登录密码；不传表示保持已有配置 */
    private String password;
}
