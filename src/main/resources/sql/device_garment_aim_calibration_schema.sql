USE smart_light;

SET @add_garment_source_result_json = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE device ADD COLUMN garment_source_result_json LONGTEXT NULL COMMENT ''Latest garment recognition result by capture source'' AFTER garment_result_json',
    'SELECT ''garment_source_result_json already exists'''
  )
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'device'
    AND column_name = 'garment_source_result_json'
);

PREPARE stmt FROM @add_garment_source_result_json;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_garment_aim_calibration_json = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE device ADD COLUMN garment_aim_calibration_json LONGTEXT NULL COMMENT ''Garment coordinate to lamp pose calibration document'' AFTER garment_source_result_json',
    'SELECT ''garment_aim_calibration_json already exists'''
  )
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'device'
    AND column_name = 'garment_aim_calibration_json'
);

PREPARE stmt FROM @add_garment_aim_calibration_json;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
