package com.genius.smartlight.dal.mysql;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.genius.smartlight.dal.dataobject.DurationRecordDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Mapper
public interface DurationRecordMapper extends BaseMapper<DurationRecordDO> {

    @Insert("""
        INSERT INTO duration_record
        (device_id, store_id, chip_id, stat_date, duration_value, collect_time, create_time, update_time)
        VALUES
        (#{deviceId}, #{storeId}, #{chipId}, #{statDate}, #{durationValue}, #{collectTime}, NOW(), NOW())
        ON DUPLICATE KEY UPDATE
        duration_value = duration_value + VALUES(duration_value),
        collect_time = VALUES(collect_time),
        update_time = NOW()
        """)
    int insertOrIncrease(@Param("deviceId") Long deviceId,
                         @Param("storeId") Long storeId,
                         @Param("chipId") String chipId,
                         @Param("statDate") LocalDate statDate,
                         @Param("durationValue") Long durationValue,
                         @Param("collectTime") LocalDateTime collectTime);
}
