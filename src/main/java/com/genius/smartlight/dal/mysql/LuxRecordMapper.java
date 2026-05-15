package com.genius.smartlight.dal.mysql;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.genius.smartlight.dal.dataobject.LuxRecordDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface LuxRecordMapper extends BaseMapper<LuxRecordDO> {

    @Insert("""
        INSERT INTO lux_record
        (device_id, store_id, chip_id, lux_value, collect_time, create_time)
        VALUES
        (#{deviceId}, #{storeId}, #{chipId}, #{luxValue}, #{collectTime}, #{createTime})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertDeviceLux(LuxRecordDO record);
}
