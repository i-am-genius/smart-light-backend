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

SET @add_garment_default_pan = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE device ADD COLUMN garment_default_pan DECIMAL(6,2) NOT NULL DEFAULT 0 COMMENT ''Default garment Pan angle'' AFTER garment_aim_enabled',
    'SELECT ''garment_default_pan already exists'''
  )
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'device' AND column_name = 'garment_default_pan'
);
PREPARE stmt FROM @add_garment_default_pan;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_garment_default_tilt = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE device ADD COLUMN garment_default_tilt DECIMAL(6,2) NOT NULL DEFAULT 20 COMMENT ''Default garment Tilt angle'' AFTER garment_default_pan',
    'SELECT ''garment_default_tilt already exists'''
  )
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'device' AND column_name = 'garment_default_tilt'
);
PREPARE stmt FROM @add_garment_default_tilt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_person_default_pan = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE device ADD COLUMN person_default_pan DECIMAL(6,2) NOT NULL DEFAULT 0 COMMENT ''Default person Pan angle'' AFTER garment_default_tilt',
    'SELECT ''person_default_pan already exists'''
  )
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'device' AND column_name = 'person_default_pan'
);
PREPARE stmt FROM @add_person_default_pan;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_person_default_tilt = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE device ADD COLUMN person_default_tilt DECIMAL(6,2) NOT NULL DEFAULT -30 COMMENT ''Default person Tilt angle'' AFTER person_default_pan',
    'SELECT ''person_default_tilt already exists'''
  )
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'device' AND column_name = 'person_default_tilt'
);
PREPARE stmt FROM @add_person_default_tilt;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
