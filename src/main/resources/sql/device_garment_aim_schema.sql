USE smart_light;

SET @add_garment_aim_enabled = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE device ADD COLUMN garment_aim_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''Use latest detected garment coordinates for lamp aim'' AFTER auto_mode',
    'SELECT ''garment_aim_enabled already exists'''
  )
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'device'
    AND column_name = 'garment_aim_enabled'
);

PREPARE stmt FROM @add_garment_aim_enabled;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
