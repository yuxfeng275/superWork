CREATE TABLE quotation_brand_scope (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    quotation_id BIGINT NOT NULL COMMENT '报价单ID',
    brand VARCHAR(200) COMMENT '品牌',
    store VARCHAR(200) COMMENT '店铺',
    description VARCHAR(500) COMMENT '说明',
    target VARCHAR(500) COMMENT '目标',
    sort_order INT NOT NULL DEFAULT 0,
    INDEX idx_qbs_quotation_id (quotation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价品牌范围';