CREATE TABLE IF NOT EXISTS `person_flow_record` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `store_id`    BIGINT       NULL     COMMENT '店铺ID',
    `user_id`     BIGINT       NULL     COMMENT '用户ID',
    `chip_id`     VARCHAR(64)  NULL     COMMENT '设备编号/摄像头设备chipId',
    `source`      VARCHAR(32)  NOT NULL DEFAULT 'UPLOAD' COMMENT '来源: UPLOAD/CAMERA',
    `person_count` INT         NOT NULL COMMENT '检测人数',
    `confidence`  DOUBLE       NULL     COMMENT '平均置信度',
    `processing_time` DOUBLE   NULL     COMMENT '处理时间，单位毫秒',
    `detect_time` DATETIME     NOT NULL COMMENT '检测时间',
    `image_name`  VARCHAR(255) NULL     COMMENT '原图文件名',
    `remark`      VARCHAR(255) NULL     COMMENT '备注',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_store_id` (`store_id`),
    KEY `idx_detect_time` (`detect_time`),
    KEY `idx_chip_id` (`chip_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='人流检测记录表';
