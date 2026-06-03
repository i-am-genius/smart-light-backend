USE smart_light;

SET @add_last_seen_at = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE device ADD COLUMN last_seen_at DATETIME NULL COMMENT ''Last time the device was seen online'' AFTER ip',
    'SELECT ''last_seen_at already exists'''
  )
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'device'
    AND column_name = 'last_seen_at'
);

PREPARE stmt FROM @add_last_seen_at;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
