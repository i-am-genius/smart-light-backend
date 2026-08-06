USE smart_light;

SET @add_garment_result_json = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE device ADD COLUMN garment_result_json JSON NULL COMMENT ''Latest structured garment recognition result'' AFTER main_color_rgb',
    'SELECT ''garment_result_json already exists'''
  )
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'device'
    AND column_name = 'garment_result_json'
);

PREPARE stmt FROM @add_garment_result_json;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
