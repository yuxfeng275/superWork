package com.bu.management.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 商机负责人姓名 → 企微 userid 映射（方案 B）。
 *
 * <p>现网 {@code sales_opportunity.owner_user_id} 恒为 NULL（18 个系统账号里没有商机负责人），
 * 按 user id 解析企微 userid 走不通，所以这里按 {@code sales_opportunity.owner} 的<b>姓名文本</b>对齐，
 * 不动账号体系。{@code enabled=0} 的行保留但不参与发送解析。
 *
 * @author BU Team
 * @since 2026-11-21
 */
@Data
@TableName("opportunity_owner_wecom")
public class OpportunityOwnerWecom {

    /** 人工维护。 */
    public static final String SOURCE_MANUAL = "MANUAL";
    /** 由日程参会人推导。 */
    public static final String SOURCE_CALENDAR = "CALENDAR";
    /** 由机器人会话推导。 */
    public static final String SOURCE_SESSION = "SESSION";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 负责人姓名，与 {@code sales_opportunity.owner} 对齐（唯一键 uk_owner_name）。 */
    private String ownerName;

    /** 企业微信 userid。 */
    private String wecomUserid;

    /** 1=参与发送解析，0=保留但不使用。 */
    private Integer enabled;

    /** MANUAL / CALENDAR / SESSION。 */
    private String source;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
