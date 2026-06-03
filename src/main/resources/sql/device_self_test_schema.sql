USE smart_light;

SET @add_self_test_json = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE device ADD COLUMN self_test_json JSON NULL COMMENT ''Latest device self-test result'' AFTER ota_status',
    'SELECT ''self_test_json already exists'''
  )
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'device'
    AND column_name = 'self_test_json'
);

PREPARE stmt FROM @add_self_test_json;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_self_test_time = (
  SELECT IF(
    COUNT(*) = 0,
    'ALTER TABLE device ADD COLUMN self_test_time DATETIME NULL COMMENT ''Latest device self-test time'' AFTER self_test_json',
    'SELECT ''self_test_time already exists'''
  )
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'device'
    AND column_name = 'self_test_time'
);

PREPARE stmt FROM @add_self_test_time;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
