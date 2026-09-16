-- V76: OA 组织/人员落库 + vReport 合同导出配置
-- 设计文档见 docs/data-flow-unification-design.md

CREATE TABLE oa_org_department (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    oa_id VARCHAR(64) NOT NULL COMMENT 'OA 部门ID',
    name VARCHAR(200) NOT NULL COMMENT '部门名称',
    parent_oa_id VARCHAR(64) DEFAULT NULL COMMENT 'OA 父部门ID',
    parent_name VARCHAR(200) DEFAULT NULL COMMENT '父部门名称',
    sort_order INT DEFAULT NULL,
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '本次同步仍存在=1，否则=0（不物理删除）',
    synced_at DATETIME DEFAULT NULL COMMENT '最近同步时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_oa_department (oa_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 部门（每日全量同步）';

CREATE TABLE oa_org_member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    oa_id VARCHAR(64) NOT NULL COMMENT 'OA 人员ID',
    name VARCHAR(100) NOT NULL COMMENT '姓名',
    login_name VARCHAR(100) DEFAULT NULL COMMENT '登录名',
    department_oa_id VARCHAR(64) DEFAULT NULL COMMENT 'OA 部门ID',
    department_name VARCHAR(200) DEFAULT NULL COMMENT '部门名称',
    email VARCHAR(200) DEFAULT NULL,
    mobile VARCHAR(50) DEFAULT NULL,
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '本次同步仍存在=1，否则=0（不物理删除）',
    synced_at DATETIME DEFAULT NULL COMMENT '最近同步时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_oa_member (oa_id),
    INDEX idx_member_name (name),
    INDEX idx_member_dept (department_oa_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA 人员（每日全量同步）';

-- vReport 销售合同导出地址（联调抓包后填入；留空则 oa-contract 任务报错提示未配置）
INSERT INTO system_config_item
(group_code, group_name, group_description, config_key, config_name, config_description,
 value_type, config_value, is_sensitive, is_required, sort_order, status)
SELECT 'oa-vreport', 'OA 报表（vReport）', 'OA vReport 报表导出接口配置', 'sales-contract-export-url',
       '销售合同导出地址', '「销售合同查询(全域-云鹿and会员通and微信定制)」导出按钮对应的完整请求地址（含查询参数），从浏览器抓包获取',
       'URL', NULL, 0, 0, 10, 1
FROM dual
WHERE NOT EXISTS (
    SELECT 1 FROM system_config_item
    WHERE group_code = 'oa-vreport' AND config_key = 'sales-contract-export-url'
);

-- OA 组织/人员采集器已落地，启用每日同步
UPDATE sync_task SET enabled = 1 WHERE task_code = 'oa-org';
