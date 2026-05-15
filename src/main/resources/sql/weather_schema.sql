ALTER TABLE store
  ADD COLUMN latitude DECIMAL(10,6) DEFAULT NULL COMMENT '纬度' AFTER city,
  ADD COLUMN longitude DECIMAL(10,6) DEFAULT NULL COMMENT '经度' AFTER latitude;

CREATE TABLE IF NOT EXISTS weather_record (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  store_id BIGINT NOT NULL COMMENT '店铺ID',
  province VARCHAR(64) DEFAULT NULL COMMENT '省份',
  city VARCHAR(64) DEFAULT NULL COMMENT '城市',
  latitude DECIMAL(10,6) DEFAULT NULL COMMENT '纬度',
  longitude DECIMAL(10,6) DEFAULT NULL COMMENT '经度',
  temperature DECIMAL(6,2) DEFAULT NULL COMMENT '温度，单位℃',
  apparent_temperature DECIMAL(6,2) DEFAULT NULL COMMENT '体感温度，单位℃',
  humidity DECIMAL(6,2) DEFAULT NULL COMMENT '相对湿度，单位%',
  wind_speed DECIMAL(6,2) DEFAULT NULL COMMENT '风速，单位km/h',
  weather_code INT DEFAULT NULL COMMENT '天气代码',
  weather_text VARCHAR(32) DEFAULT NULL COMMENT '天气文本',
  temp_max DECIMAL(6,2) DEFAULT NULL COMMENT '当天最高温，单位℃',
  temp_min DECIMAL(6,2) DEFAULT NULL COMMENT '当天最低温，单位℃',
  collect_time DATETIME NOT NULL COMMENT '采集时间',
  create_time DATETIME NOT NULL COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_weather_store_collect_time (store_id, collect_time),
  KEY idx_weather_collect_time (collect_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='天气历史记录';
