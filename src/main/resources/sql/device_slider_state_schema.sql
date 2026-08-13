CREATE TABLE IF NOT EXISTS device_slider_state (
    chip_id VARCHAR(64) NOT NULL COMMENT 'Actual Lamp chipId controlling the slider',
    store_id BIGINT NULL,
    current_position_mm DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'Last completed or estimated slider position',
    target_position_mm DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT 'Latest commanded slider target',
    speed_mode VARCHAR(16) NOT NULL DEFAULT 'normal' COMMENT 'slow, normal or fast',
    motion_started_at DATETIME(3) NULL,
    motion_end_at DATETIME(3) NULL,
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (chip_id),
    KEY idx_device_slider_state_store (store_id),
    KEY idx_device_slider_state_motion_end (motion_end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Backend estimated slider position and speed state';
